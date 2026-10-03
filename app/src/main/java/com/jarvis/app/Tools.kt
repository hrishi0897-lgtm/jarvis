package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Tools(
    private val context: Context,
    private val prefsManager: PreferencesManager,
    private val termuxBridge: TermuxBridge,
    private val confirmCallback: suspend (String) -> Boolean
) {

    companion object {
        private const val SETTLE_MS = 700L
        private const val APPROVAL_WINDOW_MS = 45_000L

        // Shared by every Tools instance so "stop" works from any screen.
        private val scrollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var scrollJob: Job? = null
    }

    @Volatile
    private var destructiveApprovedUntil = 0L

    // ------------------------------------------------------------------------------------
    // Declarations
    // ------------------------------------------------------------------------------------

    private fun fn(
        name: String,
        description: String,
        props: List<Triple<String, String, String>> = emptyList(),   // (name, type, description)
        required: List<String> = emptyList()
    ): JSONObject = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                for ((pName, pType, pDesc) in props) {
                    put(pName, JSONObject().apply {
                        put("type", pType)
                        put("description", pDesc)
                    })
                }
            })
            if (required.isNotEmpty()) put("required", JSONArray(required))
        })
    }

    fun getToolDeclarations(): JSONArray {
        val t = JSONArray()

        t.put(fn("get_device_status", "Returns current date, time and battery percentage."))

        t.put(fn("open_app", "Launches an app from outside it. Only works for apps the user allowed.",
            listOf(Triple("app_name", "string", "App name or package.")), listOf("app_name")))

        t.put(fn("read_screen", "Returns the visible elements (text, description, id, x,y tap coordinates) of the foreground app."))

        t.put(fn("click_element", "Taps a visible element by its text, description or id. Returns the new screen.",
            listOf(Triple("identifier", "string", "Text, description or id seen on screen.")), listOf("identifier")))

        t.put(fn("tap_coordinates", "Taps an exact point. Use for icon buttons with no label, taking x,y from the screen elements.",
            listOf(Triple("x", "integer", "X pixel."), Triple("y", "integer", "Y pixel.")), listOf("x", "y")))

        t.put(fn("long_press", "Long-presses a visible element (select item, open context menu).",
            listOf(Triple("identifier", "string", "Text, description or id seen on screen.")), listOf("identifier")))

        t.put(fn("type_and_send", "Types text into the active input box, then presses Send or Search/Enter. Set send_immediately=false to only type.",
            listOf(
                Triple("text", "string", "Text to enter."),
                Triple("send_immediately", "boolean", "Press send/enter after typing. Default true.")
            ), listOf("text")))

        t.put(fn("send_whatsapp_message", "Opens WhatsApp, finds the contact or group, types and sends a message.",
            listOf(
                Triple("recipient", "string", "Contact or group name."),
                Triple("message", "string", "Message text.")
            ), listOf("recipient", "message")))

        t.put(fn("phone_control", "System actions: home, back, recents, notifications, quick_settings, split_screen, lock_screen, screenshot.",
            listOf(Triple("action", "string", "One of the listed actions.")), listOf("action")))

        t.put(fn("swipe_screen", "Scrolls/swipes the screen: up, down, left or right. Returns the new screen.",
            listOf(Triple("direction", "string", "up, down, left or right.")), listOf("direction")))

        t.put(fn("open_url", "Opens an http/https link in the browser or the app that handles it.",
            listOf(Triple("url", "string", "Full http(s) URL.")), listOf("url")))

        t.put(fn("web_search", "Searches the web in the browser.",
            listOf(Triple("query", "string", "Search text.")), listOf("query")))

        t.put(fn("open_settings", "Opens an Android settings page: wifi, bluetooth, display, sound, battery, apps, location, accessibility, airplane, nfc, date, storage, main.",
            listOf(Triple("page", "string", "Settings page name.")), listOf("page")))

        t.put(fn("flashlight", "Turns the flashlight on or off.",
            listOf(Triple("on", "boolean", "true = on, false = off.")), listOf("on")))

        t.put(fn("set_volume", "Sets volume 0-100 for music, ring or alarm.",
            listOf(
                Triple("level", "integer", "0 to 100."),
                Triple("stream", "string", "music (default), ring or alarm.")
            ), listOf("level")))

        t.put(fn("media_control", "Controls playing media: play_pause, next, previous, stop.",
            listOf(Triple("action", "string", "play_pause, next, previous or stop.")), listOf("action")))

        t.put(fn("dial_number", "Opens the dialer with a number filled in. The user (or a tap on the Call button) places the call.",
            listOf(Triple("number", "string", "Phone number.")), listOf("number")))

        t.put(fn("auto_scroll", "Keeps swiping to the next video in a feed (YouTube Shorts, Reels, TikTok) every N seconds in the background. Android cannot tell when a video ends, so N should match the video length. Open the feed app first.",
            listOf(
                Triple("seconds_per_video", "integer", "Seconds to watch each video before swiping. Default 30."),
                Triple("max_videos", "integer", "Stop after this many videos. Default 20.")
            )))

        t.put(fn("stop_auto_scroll", "Stops auto_scroll."))

        t.put(fn("wait", "Waits for a screen to load (1-5 seconds).",
            listOf(Triple("seconds", "integer", "Seconds to wait.")), listOf("seconds")))

        t.put(fn("request_destructive_action", "Ask the user to confirm BEFORE deleting, clearing, uninstalling or wiping anything.",
            listOf(Triple("description", "string", "What will be deleted.")), listOf("description")))

        t.put(fn("run_termux_command", "Runs a shell command in Termux ONLY when the user explicitly asks for terminal/bash. Always needs user approval.",
            listOf(Triple("command", "string", "Shell command.")), listOf("command")))

        t.put(fn("set_alarm", "Sets a clock alarm.",
            listOf(
                Triple("hour", "integer", "0-23."),
                Triple("minutes", "integer", "0-59."),
                Triple("message", "string", "Alarm label.")
            ), listOf("hour", "minutes")))

        return JSONArray().apply {
            put(JSONObject().apply { put("function_declarations", t) })
        }
    }

    // ------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------

    private fun noService(r: JSONObject) {
        r.put("status", "error")
        r.put("message", "Accessibility Service is not active. Enable Jarvis in Android Settings > Accessibility.")
    }

    private fun blocked(r: JSONObject, why: String) {
        r.put("status", "blocked")
        r.put("message", "I can't do that: $why. Jarvis never handles payments, passwords or PINs, so please do this part yourself.")
    }

    /** Runs the safety rules for a tap/long-press. Returns true if the action may proceed. */
    private suspend fun gate(r: JSONObject, info: JarvisAccessibilityService.TargetInfo?, verb: String): Boolean {
        if (info == null) return true   // target not found: the action itself will report that

        if (SafetyGuard.isBlockedPackage(info.packageName)) {
            blocked(r, "this app (${info.packageName}) handles payments or installs")
            return false
        }

        val v = SafetyGuard.evaluateLabel(info.label)
        when (v.risk) {
            Risk.BLOCK -> {
                blocked(r, v.reason)
                return false
            }
            Risk.CONFIRM -> {
                val now = System.currentTimeMillis()
                if (now > destructiveApprovedUntil) {
                    val shown = info.display.trim().take(60).ifBlank { "this item" }
                    val ok = confirmCallback("Jarvis wants to $verb \"$shown\" because ${v.reason}.\n\nApp: ${info.packageName}\n\nAllow?")
                    if (!ok) {
                        r.put("status", "denied")
                        r.put("message", "User denied this action.")
                        return false
                    }
                }
            }
            Risk.SAFE -> {}
        }
        return true
    }

    private suspend fun attachScreen(r: JSONObject, s: JarvisAccessibilityService, allowed: Set<String>, extraDelay: Long = 0L) {
        delay(SETTLE_MS + extraDelay)
        r.put("screen_after", s.getScreenContent(allowed))
    }

    /** Starts an intent, but only if the app that would handle it is not blocked / not an unapproved app. */
    private fun launchChecked(intent: Intent, r: JSONObject, requireAllowed: Boolean): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pkg = intent.resolveActivity(context.packageManager)?.packageName
        if (pkg == null) {
            r.put("status", "error")
            r.put("message", "No app on this phone can handle that.")
            return false
        }
        if (SafetyGuard.isBlockedPackage(pkg)) {
            blocked(r, "it would open a payment or installer app")
            return false
        }
        if (requireAllowed && pkg != "android" && !prefsManager.getAllowedPackages().contains(pkg)) {
            r.put("status", "error")
            r.put("message", "The app that handles this ($pkg) is not allowed. Turn it ON in the Apps tab.")
            return false
        }
        context.startActivity(intent)
        return true
    }

    // ------------------------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------------------------

    suspend fun execute(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject()
        try {
            val allowed = prefsManager.getAllowedPackages()
            val svc = JarvisAccessibilityService.instance

            when (name) {
                "get_device_status" -> {
                    val now = Date()
                    val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    result.put("status", "success")
                    result.put("date", SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(now))
                    result.put("time", SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now))
                    result.put("battery_percent", if (level >= 0 && scale > 0) level * 100 / scale else -1)
                }

                "open_app" -> {
                    val q = args.getString("app_name").trim()
                    val pm = context.packageManager
                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }

                    var targetPkg: String? = null
                    var bestScore = 0
                    for (app in pm.queryIntentActivities(mainIntent, 0)) {
                        val pkg = app.activityInfo.packageName
                        val label = app.loadLabel(pm).toString()
                        val score = when {
                            pkg.equals(q, true) -> 4
                            label.equals(q, true) -> 3
                            label.startsWith(q, true) -> 2
                            label.contains(q, true) -> 1
                            else -> 0
                        }
                        if (score > bestScore) {
                            bestScore = score
                            targetPkg = pkg
                        }
                    }

                    val pkg = targetPkg
                    if (pkg == null) {
                        result.put("status", "error")
                        result.put("message", "Application '$q' not found on device.")
                    } else if (SafetyGuard.isBlockedPackage(pkg)) {
                        blocked(result, "'$q' is a payment, banking or installer app")
                    } else if (!allowed.contains(pkg)) {
                        result.put("status", "error")
                        result.put("message", "Access denied: '$q' is not toggled ON in the Apps tab.")
                    } else {
                        val launch = pm.getLaunchIntentForPackage(pkg)
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launch)
                            result.put("status", "success")
                            result.put("message", "Opened $pkg")
                            if (svc != null) attachScreen(result, svc, allowed, 500L)
                        } else {
                            result.put("status", "error")
                            result.put("message", "Could not start launcher for $pkg")
                        }
                    }
                }

                "read_screen" -> {
                    if (svc == null) noService(result) else return@withContext svc.getScreenContent(allowed)
                }

                "click_element" -> {
                    val id = args.getString("identifier")
                    if (svc == null) noService(result)
                    else if (gate(result, svc.inspectTarget(id, allowed), "tap")) {
                        val ok = svc.clickElement(id, allowed)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Clicked $id" else "Element '$id' not found on the visible screen.")
                        if (ok) attachScreen(result, svc, allowed)
                    }
                }

                "tap_coordinates" -> {
                    val x = args.getInt("x")
                    val y = args.getInt("y")
                    if (svc == null) noService(result)
                    else if (gate(result, svc.inspectPoint(x, y, allowed), "tap")) {
                        val ok = svc.tapCoordinates(x.toFloat(), y.toFloat())
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Tapped $x,$y" else "Tap failed.")
                        if (ok) attachScreen(result, svc, allowed)
                    }
                }

                "long_press" -> {
                    val id = args.getString("identifier")
                    if (svc == null) noService(result)
                    else if (gate(result, svc.inspectTarget(id, allowed), "long-press")) {
                        val ok = svc.longPressElement(id, allowed)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Long-pressed $id" else "Element '$id' not found on the visible screen.")
                        if (ok) attachScreen(result, svc, allowed, 300L)
                    }
                }

                "type_and_send" -> {
                    val text = args.getString("text")
                    val send = args.optBoolean("send_immediately", true)
                    if (svc == null) {
                        noService(result)
                    } else if (SafetyGuard.looksLikeCardNumber(text)) {
                        blocked(result, "that looks like a card number")
                    } else {
                        val field = svc.inspectInput(allowed)
                        val verdict = if (field != null) SafetyGuard.evaluateField(field.label, field.isPassword) else Verdict(Risk.SAFE)
                        if (field != null && SafetyGuard.isBlockedPackage(field.packageName)) {
                            blocked(result, "this app handles payments")
                        } else if (verdict.risk == Risk.BLOCK) {
                            blocked(result, verdict.reason)
                        } else {
                            val ok = svc.typeAndSend(text, allowed, send)
                            result.put("status", if (ok) "success" else "error")
                            result.put("message", if (ok) (if (send) "Text entered and sent." else "Text entered.") else "Could not find an active input box.")
                            if (ok) attachScreen(result, svc, allowed)
                        }
                    }
                }

                "send_whatsapp_message" -> {
                    val recipient = args.getString("recipient")
                    val msg = args.getString("message")
                    if (svc == null) noService(result)
                    else if (!allowed.contains("com.whatsapp")) {
                        result.put("status", "error")
                        result.put("message", "WhatsApp is not toggled ON in the Apps tab.")
                    } else {
                        val ok = svc.sendWhatsAppMessage(recipient, msg, allowed)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Sent message to $recipient." else "Failed to deliver message to $recipient.")
                    }
                }

                "phone_control" -> {
                    val action = args.getString("action")
                    if (svc == null) noService(result) else {
                        val ok = svc.triggerGlobalAction(action)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Executed $action" else "Failed to execute $action")
                        if (ok && action.lowercase() in setOf("back", "notifications", "quick_settings")) {
                            attachScreen(result, svc, allowed)
                        }
                    }
                }

                "swipe_screen" -> {
                    val dir = args.getString("direction")
                    if (svc == null) noService(result) else {
                        val ok = svc.swipe(dir)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Swiped $dir" else "Failed to swipe $dir")
                        if (ok) attachScreen(result, svc, allowed)
                    }
                }

                "open_url", "web_search" -> {
                    val url = if (name == "web_search")
                        "https://www.google.com/search?q=" + Uri.encode(args.getString("query"))
                    else args.getString("url").trim()
                    val v = SafetyGuard.evaluateUrl(url)
                    if (v.risk == Risk.BLOCK) {
                        blocked(result, v.reason)
                    } else if (launchChecked(Intent(Intent.ACTION_VIEW, Uri.parse(url)), result, requireAllowed = true)) {
                        result.put("status", "success")
                        result.put("message", "Opened $url")
                        if (svc != null) attachScreen(result, svc, allowed, 800L)
                    }
                }

                "open_settings" -> {
                    val action = when (args.getString("page").lowercase().trim()) {
                        "wifi" -> Settings.ACTION_WIFI_SETTINGS
                        "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                        "display" -> Settings.ACTION_DISPLAY_SETTINGS
                        "sound" -> Settings.ACTION_SOUND_SETTINGS
                        "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
                        "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                        "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                        "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                        "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
                        "nfc" -> Settings.ACTION_NFC_SETTINGS
                        "date" -> Settings.ACTION_DATE_SETTINGS
                        "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                        else -> Settings.ACTION_SETTINGS
                    }
                    if (launchChecked(Intent(action), result, requireAllowed = false)) {
                        result.put("status", "success")
                        result.put("message", "Opened settings. To tap things inside Settings, the Settings app must be ON in the Apps tab.")
                        if (svc != null && allowed.contains("com.android.settings")) attachScreen(result, svc, allowed, 300L)
                    }
                }

                "flashlight" -> {
                    val on = args.getBoolean("on")
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val camId = cm.cameraIdList.firstOrNull { id ->
                        val c = cm.getCameraCharacteristics(id)
                        c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                    }
                    if (camId == null) {
                        result.put("status", "error")
                        result.put("message", "No flashlight found.")
                    } else {
                        cm.setTorchMode(camId, on)
                        result.put("status", "success")
                        result.put("message", if (on) "Flashlight on." else "Flashlight off.")
                    }
                }

                "set_volume" -> {
                    val level = args.getInt("level").coerceIn(0, 100)
                    val stream = when (args.optString("stream", "music").lowercase()) {
                        "ring" -> AudioManager.STREAM_RING
                        "alarm" -> AudioManager.STREAM_ALARM
                        else -> AudioManager.STREAM_MUSIC
                    }
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(stream)
                    am.setStreamVolume(stream, level * max / 100, 0)
                    result.put("status", "success")
                    result.put("message", "Volume set to $level%.")
                }

                "media_control" -> {
                    val code = when (args.getString("action").lowercase()) {
                        "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                        else -> -1
                    }
                    if (code == -1) {
                        result.put("status", "error")
                        result.put("message", "Unknown media action.")
                    } else {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                        result.put("status", "success")
                        result.put("message", "Done.")
                    }
                }

                "dial_number" -> {
                    val number = args.getString("number").filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                    if (number.isEmpty()) {
                        result.put("status", "error")
                        result.put("message", "Invalid number.")
                    } else if (launchChecked(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))), result, requireAllowed = false)) {
                        result.put("status", "success")
                        result.put("message", "Dialer opened with $number.")
                        if (svc != null) attachScreen(result, svc, allowed, 400L)
                    }
                }

                "auto_scroll" -> {
                    val seconds = args.optInt("seconds_per_video", 30).coerceIn(5, 180)
                    val videos = args.optInt("max_videos", 20).coerceIn(1, 100)
                    if (svc == null) {
                        noService(result)
                    } else {
                        val startPkg = svc.foregroundPackage()
                        if (startPkg.isEmpty() || startPkg == context.packageName) {
                            result.put("status", "error")
                            result.put("message", "Open the app with the feed first (for example YouTube), then start auto scroll.")
                        } else if (SafetyGuard.isBlockedPackage(startPkg) || !allowed.contains(startPkg)) {
                            result.put("status", "error")
                            result.put("message", "That app is not allowed or is blocked.")
                        } else {
                            scrollJob?.cancel()
                            scrollJob = scrollScope.launch {
                                var done = 0
                                while (isActive && done < videos) {
                                    delay(seconds * 1000L)
                                    val s = JarvisAccessibilityService.instance ?: break
                                    val fg = s.foregroundPackage()
                                    if (fg == context.packageName) continue
                                    if (fg != startPkg) break
                                    s.swipe("down")
                                    done++
                                }
                            }
                            result.put("status", "success")
                            result.put("message", "Auto scroll started: next video every $seconds s, up to $videos videos. Say stop to end it.")
                        }
                    }
                }

                "stop_auto_scroll" -> {
                    scrollJob?.cancel()
                    scrollJob = null
                    result.put("status", "success")
                    result.put("message", "Auto scroll stopped.")
                }

                "wait" -> {
                    val sec = args.optInt("seconds", 2).coerceIn(1, 5)
                    delay(sec * 1000L)
                    result.put("status", "success")
                    result.put("message", "Waited $sec s.")
                    if (svc != null) result.put("screen_after", svc.getScreenContent(allowed))
                }

                "request_destructive_action" -> {
                    val desc = args.getString("description")
                    val approved = confirmCallback(desc)
                    if (approved) destructiveApprovedUntil = System.currentTimeMillis() + APPROVAL_WINDOW_MS
                    result.put("status", if (approved) "success" else "denied")
                    result.put("approved", approved)
                    result.put("message", if (approved) "User confirmed. Proceed now." else "User denied. Do not delete anything.")
                }

                "set_alarm" -> {
                    val hour = args.getInt("hour")
                    val minutes = args.getInt("minutes")
                    val message = args.optString("message", "Jarvis Alarm")
                    context.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                        putExtra(AlarmClock.EXTRA_MESSAGE, message)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    result.put("status", "success")
                    result.put("message", "Alarm set for %d:%02d".format(hour, minutes))
                }

                "run_termux_command" -> {
                    val output = termuxBridge.runCommand(args.getString("command"))
                    result.put("status", "success")
                    result.put("output", output)
                }

                else -> {
                    result.put("status", "error")
                    result.put("message", "Unknown action $name")
                }
            }
        } catch (e: Exception) {
            result.put("status", "error")
            result.put("message", e.message ?: "Execution error")
        }
        result
    }
}
