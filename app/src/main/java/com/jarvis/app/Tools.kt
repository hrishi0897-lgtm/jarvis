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

        toolsArray.put(JSONObject().apply {
            put("name", "get_device_status")
            put("description", "Returns the current date, time, battery percentage, and charging state immediately without running shell commands.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        })

        toolsArray.put(JSONObject().apply {
            put("name", "type_and_send")
            put("description", "Types text directly into the active messaging input box on screen and sends it. Use whenever the user asks to type, write, or send a message.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "The message text to type and send.")
                    })
                    put("send_immediately", JSONObject().apply {
                        put("type", "boolean")
                        put("description", "Whether to automatically tap Send. Defaults to true.")
                    })
                })
                put("required", JSONArray().apply { put("text") })
            })
        })

        toolsArray.put(JSONObject().apply {
            put("name", "open_app")
            put("description", "Opens an application by its package name or label.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("app_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "Package name or common app name.")
                    })
                })
                put("required", JSONArray().apply { put("app_name") })
            })
        })

        toolsArray.put(JSONObject().apply {
            put("name", "phone_control")
            put("description", "Executes phone-level navigation: 'home', 'back', 'recents' (app switcher), 'notifications', 'quick_settings', or 'split_screen'.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().apply {
                        put("type", "string")
                        put("description", "One of: 'home', 'back', 'recents', 'notifications', 'quick_settings', 'split_screen'.")
                    })
                })
                put("required", JSONArray().apply { put("action") })
            })
        })

        toolsArray.put(JSONObject().apply {
            put("name", "swipe_screen")
            put("description", "Swipes the screen in a direction: 'up', 'down', 'left', or 'right' to scroll or change tabs/pages.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("direction", JSONObject().apply {
                        put("type", "string")
                        put("description", "'up', 'down', 'left', or 'right'.")
                    })
                })
                put("required", JSONArray().apply { put("direction") })
            })
        })

        toolsArray.put(JSONObject().apply {
            put("name", "read_screen")
            put("description", "Inspects and returns all interactive elements, text, and descriptions from the current screen.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        })

        toolsArray.put(JSONObject().apply {
            put("name", "click_element")
            put("description", "Taps on a visible element by label, description, or id.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("identifier", JSONObject().apply {
                        put("type", "string")
                        put("description", "Visible text, description, or element id.")
                    })
                })
                put("required", JSONArray().apply { put("identifier") })
            })
        })

        toolsArray.put(JSONObject().apply {
            put("name", "input_text_element")
            put("description", "Types text into a specific identified input field.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("identifier", JSONObject().apply {
                        put("type", "string")
                        put("description", "Input field hint, text, or id.")
                    })
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "Text to enter.")
                    })
                })
                put("required", JSONArray().apply {
                    put("identifier")
                    put("text")
                })
            })
        })

        toolsArray.put(JSONObject().apply {
            put("name", "request_destructive_action")
            put("description", "Requests user confirmation before performing any destructive operation (e.g. deleting chats, messages, files, clearing data, uninstalling apps).")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("description", JSONObject().apply {
                        put("type", "string")
                        put("description", "Clear statement of what will be permanently deleted.")
                    })
                })
                put("required", JSONArray().apply { put("description") })
            })
        })

        toolsArray.put(JSONObject().apply {
            put("name", "run_termux_command")
            put("description", "Runs bash shell commands in Termux. ONLY use when user explicitly asks for shell, terminal, bash script, or developer commands.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "The shell command to run.")
                    })
                })
                put("required", JSONArray().apply { put("command") })
            })
        })

        toolsArray.put(JSONObject().apply {
            put("name", "set_alarm")
            put("description", "Sets an alarm.")
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

                "type_and_send" -> {
                    val msgText = args.getString("text")
                    val sendImmediately = args.optBoolean("send_immediately", true)
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Jarvis Accessibility Service is not active.")
                    } else {
                        val ok = service.typeAndSend(msgText, prefsManager.getAllowedPackages(), sendImmediately)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Typed message into input field." else "Could not find active text input box on screen.")
                    }
                }

                "phone_control" -> {
                    val action = args.getString("action")
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Jarvis Accessibility Service is not active.")
                    } else {
                        val ok = service.triggerGlobalAction(action)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Triggered $action" else "Failed to perform $action")
                    }
                }

                "swipe_screen" -> {
                    val dir = args.getString("direction")
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Jarvis Accessibility Service is not active.")
                    } else {
                        val ok = service.swipe(dir)
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Swiped $dir" else "Failed to swipe $dir")
                    }
                }

                "read_screen" -> {
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Jarvis Accessibility Service is not active.")
                    } else {
                        return@withContext service.getScreenContent(prefsManager.getAllowedPackages())
                    }
                }

                "click_element" -> {
                    val id = args.getString("identifier")
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Jarvis Accessibility Service is not active.")
                    } else {
                        val ok = service.clickElement(id, prefsManager.getAllowedPackages())
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Tapped $id" else "Element $id not found")
                    }
                }

                "input_text_element" -> {
                    val id = args.getString("identifier")
                    val text = args.getString("text")
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Jarvis Accessibility Service is not active.")
                    } else {
                        val ok = service.inputText(id, text, prefsManager.getAllowedPackages())
                        result.put("status", if (ok) "success" else "error")
                        result.put("message", if (ok) "Entered text" else "Field $id not found")
                    }
                }

                "request_destructive_action" -> {
                    val desc = args.getString("description")
                    val approved = confirmCallback(desc)
                    result.put("status", if (approved) "success" else "denied")
                    result.put("approved", approved)
                    result.put("message", if (approved) "User approved deletion." else "User cancelled deletion.")
                }

                "open_app" -> {
                    val appQuery = args.getString("app_name").trim()
                    val pm = context.packageManager
                    val allowed = prefsManager.getAllowedPackages()
                    var targetPkg: String? = null

                    if (allowed.contains(appQuery)) {
                        targetPkg = appQuery
                    } else {
                        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                        }
                        val apps = pm.queryIntentActivities(mainIntent, 0)
                        for (app in apps) {
                            val pkg = app.activityInfo.packageName
                            val label = app.loadLabel(pm).toString()
                            if (label.contains(appQuery, ignoreCase = true) || pkg.contains(appQuery, ignoreCase = true)) {
                                targetPkg = pkg
                                break
                            }
                        }
                    }

                    if (targetPkg != null) {
                        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                            result.put("status", "success")
                            result.put("message", "Opened $targetPkg")
                        } else {
                            result.put("status", "error")
                            result.put("message", "Unable to open $targetPkg")
                        }
                    } else {
                        result.put("status", "error")
                        result.put("message", "Application '$appQuery' not found.")
                    }
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
                    result.put("message", "Unknown tool '$name'")
                }
            }
        } catch (e: Exception) {
            result.put("status", "error")
            result.put("message", e.message ?: "Execution error")
        }
        result
    }
}
