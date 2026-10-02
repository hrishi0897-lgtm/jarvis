package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
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

    fun getToolDeclarations(): JSONArray {
        val toolsArray = JSONArray()

        // 0: get_device_status
        toolsArray.put(JSONObject().apply {
            put("name", "get_device_status")
            put("description", "Returns the current date, time, battery percentage, and charging state immediately without running shell commands.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        })

        // 1: open_app
        toolsArray.put(JSONObject().apply {
            put("name", "open_app")
            put("description", "Launches an application only if it is present in the user's allowed apps list.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("app_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "Name or package of the app to launch.")
                    })
                })
                put("required", JSONArray().apply { put("app_name") })
            })
        })

        // 2: read_screen
        toolsArray.put(JSONObject().apply {
            put("name", "read_screen")
            put("description", "Returns visible elements, text, and coordinates from the foreground window.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        })

        // 3: click_element
        toolsArray.put(JSONObject().apply {
            put("name", "click_element")
            put("description", "Taps an element verified from the screen by matching visible text, description, or resource ID.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("identifier", JSONObject().apply {
                        put("type", "string")
                        put("description", "Exact or partial text, description, or id found on screen.")
                    })
                })
                put("required", JSONArray().apply { put("identifier") })
            })
        })

        // 4: type_and_send
        toolsArray.put(JSONObject().apply {
            put("name", "type_and_send")
            put("description", "Types text into the active chat box or input field on screen and sends it.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "The message or text to enter.")
                    })
                    put("send_immediately", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Whether to press send immediately after typing.")
                    })
                })
                put("required", JSONArray().apply { put("text") })
            })
        })

        // 5: send_whatsapp_message
        toolsArray.put(JSONObject().apply {
            put("name", "send_whatsapp_message")
            put("description", "Automates opening WhatsApp, navigating to a contact, typing, and sending a message.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("recipient", JSONObject().apply {
                        put("type", "string")
                        put("description", "Contact or group name.")
                    })
                    put("message", JSONObject().apply {
                        put("type", "string")
                        put("description", "The message text to send.")
                    })
                })
                put("required", JSONArray().apply {
                    put("recipient")
                    put("message")
                })
            })
        })

        // 6: phone_control
        toolsArray.put(JSONObject().apply {
            put("name", "phone_control")
            put("description", "System level actions: 'home', 'back', 'recents', 'notifications', 'quick_settings', or 'split_screen'.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("description", "'home', 'back', 'recents', 'notifications', 'quick_settings', 'split_screen'")
                    })
                })
                put("required", JSONArray().apply { put("action") })
            })
        })

        // 7: swipe_screen (Fixed schema nesting)
        toolsArray.put(JSONObject().apply {
            put("name", "swipe_screen")
            put("description", "Swipes the screen: 'up', 'down', 'left', or 'right'.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("direction", JSONObject().apply {
                        put("type", "string")
                        put("description", "'up', 'down', 'left', or 'right'")
                    })
                })
                put("required", JSONArray().apply { put("direction") })
            })
        })

        // 8: request_destructive_action
        toolsArray.put(JSONObject().apply {
            put("name", "request_destructive_action")
            put("description", "Requests explicit confirmation before deleting chats, wiping data, or uninstalling apps.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("description", JSONObject().apply {
                        put("type", "string")
                        put("description", "Description of what will be deleted.")
                    })
                })
                put("required", JSONArray().apply { put("description") })
            })
        })

        // 9: run_termux_command
        toolsArray.put(JSONObject().apply {
            put("name", "run_termux_command")
            put("description", "Runs bash shell commands in Termux ONLY when user explicitly asks for bash/terminal/script execution.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "Shell command.")
                    })
                })
                put("required", JSONArray().apply { put("command") })
            })
        })

        // 10: set_alarm
        toolsArray.put(JSONObject().apply {
            put("name", "set_alarm")
            put("description", "Sets a clock alarm.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("hour", JSONObject().apply { put("type", "integer") })
                    put("minutes", JSONObject().apply { put("type", "integer") })
                    put("message", JSONObject().apply { put("type", "string") })
                })
                put("required", JSONArray().apply {
                    put("hour")
                    put("minutes")
                })
            })
        })

        return JSONArray().apply {
            put(JSONObject().apply { put("function_declarations", toolsArray) })
        }
    }

    suspend fun execute(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject()
        try {
            when (name) {
                "get_device_status" -> {
                    val now = Date()
                    val dateFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(now)
                    val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)

                    val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { filter ->
                        context.registerReceiver(null, filter)
                    }
                    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

                    result.put("status", "success")
                    result.put("date", dateFormat)
                    result.put("time", timeFormat)
                    result.put("battery_percent", batteryPct)
                }

                "open_app" -> {
                    val appQuery = args.getString("app_name").trim()
                    val pm = context.packageManager
                    val allowed = prefsManager.getAllowedPackages()
                    var targetPkg: String? = null

                    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }
                    val apps = pm.queryIntentActivities(mainIntent, 0)

                    for (app in apps) {
                        val pkg = app.activityInfo.packageName
                        val label = app.loadLabel(pm).toString()
                        if (label.equals(appQuery, ignoreCase = true) ||
                            label.contains(appQuery, ignoreCase = true) ||
                            pkg.equals(appQuery, ignoreCase = true)) {
                            targetPkg = pkg
                            break
                        }
                    }

                    if (targetPkg == null) {
                        result.put("status", "error")
                        result.put("message", "Application '$appQuery' not found on device.")
                    } else if (!allowed.contains(targetPkg)) {
                        result.put("status", "error")
                        result.put("message", "Access denied: '$appQuery' is not toggled ON in your allowed apps list.")
                    } else {
                        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                            result.put("status", "success")
                            result.put("message", "Opened $targetPkg")
                        } else {
                            result.put("status", "error")
                            result.put("message", "Could not start launcher for $targetPkg")
                        }
                    }
                }

                "read_screen" -> {
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Accessibility Service is not active. Enable it in Android Settings.")
                    } else {
                        return@withContext service.getScreenContent(prefsManager.getAllowedPackages())
                    }
                }

                "click_element" -> {
                    val id = args.getString("identifier")
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Accessibility Service is not active.")
                    } else {
                        val ok = service.clickElement(id, prefsManager.getAllowedPackages())
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Clicked $id" else "Element '$id' not found on visible screen.")
                    }
                }

                "type_and_send" -> {
                    val msgText = args.getString("text")
                    val sendImmediately = args.optBoolean("send_immediately", true)
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Accessibility Service is not active.")
                    } else {
                        val ok = service.typeAndSend(msgText, prefsManager.getAllowedPackages(), sendImmediately)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Text entered and sent." else "Could not locate an active input box.")
                    }
                }

                "send_whatsapp_message" -> {
                    val recipient = args.getString("recipient")
                    val msg = args.getString("message")
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Accessibility Service is not active.")
                    } else {
                        val ok = service.sendWhatsAppMessage(recipient, msg, prefsManager.getAllowedPackages())
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Sent message to $recipient." else "Failed to deliver message to $recipient.")
                    }
                }

                "phone_control" -> {
                    val action = args.getString("action")
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Accessibility Service is not active.")
                    } else {
                        val ok = service.triggerGlobalAction(action)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Executed $action" else "Failed to execute $action")
                    }
                }

                "swipe_screen" -> {
                    val dir = args.getString("direction")
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Accessibility Service is not active.")
                    } else {
                        val ok = service.swipe(dir)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Swiped $dir" else "Failed to swipe $dir")
                    }
                }

                "request_destructive_action" -> {
                    val desc = args.getString("description")
                    val approved = confirmCallback(desc)
                    result.put("status", if (approved) "success" else "denied")
                    result.put("approved", approved)
                    result.put("message", if (approved) "User confirmed deletion." else "User denied deletion.")
                }

                "set_alarm" -> {
                    val hour = args.getInt("hour")
                    val minutes = args.getInt("minutes")
                    val message = args.optString("message", "Jarvis Alarm")
                    val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                        putExtra(AlarmClock.EXTRA_MESSAGE, message)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(alarmIntent)
                    result.put("status", "success")
                    result.put("message", "Alarm set for $hour:$minutes")
                }

                "run_termux_command" -> {
                    val cmd = args.getString("command")
                    val output = termuxBridge.runCommand(cmd)
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
