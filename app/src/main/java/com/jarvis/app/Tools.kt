package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class Tools(
    private val context: Context,
    private val prefsManager: PreferencesManager,
    private val termuxBridge: TermuxBridge,
    private val confirmCallback: suspend (String) -> Boolean
) {

    fun getToolDeclarations(): JSONArray {
        val toolsArray = JSONArray()

        val openApp = JSONObject().apply {
            put("name", "open_app")
            put("description", "Opens an allowed Android application using its package name or common app name.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("app_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "The package name or common label of the app to launch.")
                    })
                })
                put("required", JSONArray().apply { put("app_name") })
            })
        }

        val setAlarm = JSONObject().apply {
            put("name", "set_alarm")
            put("description", "Sets an alarm for a specific hour, minute, and optional message label.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("hour", JSONObject().apply {
                        put("type", "integer")
                        put("description", "The hour of the day in 24-hour format (0-23).")
                    })
                    put("minutes", JSONObject().apply {
                        put("type", "integer")
                        put("description", "The minute value (0-59).")
                    })
                    put("message", JSONObject().apply {
                        put("type", "string")
                        put("description", "Label or message for the alarm.")
                    })
                })
                put("required", JSONArray().apply {
                    put("hour")
                    put("minutes")
                })
            })
        }

        val listAllowedApps = JSONObject().apply {
            put("name", "list_allowed_apps")
            put("description", "Returns the list of package names that the user has granted permission to interact with.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }

        val runTermux = JSONObject().apply {
            put("name", "run_termux_command")
            put("description", "Executes a shell command inside Termux and captures the output.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "The exact bash shell command to run.")
                    })
                })
                put("required", JSONArray().apply { put("command") })
            })
        }

        val listRecentMessages = JSONObject().apply {
            put("name", "list_recent_messages")
            put("description", "Fetches captured notifications and messaging previews from allowed apps.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Maximum number of recent messages to return (default 10).")
                    })
                })
            })
        }

        val replyNotification = JSONObject().apply {
            put("name", "reply_notification")
            put("description", "Sends an inline reply to a captured messaging notification by its ID.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("message_id", JSONObject().apply {
                        put("type", "integer")
                        put("description", "The unique integer ID of the notification to reply to.")
                    })
                    put("reply_text", JSONObject().apply {
                        put("type", "string")
                        put("description", "The reply message content.")
                    })
                })
                put("required", JSONArray().apply {
                    put("message_id")
                    put("reply_text")
                })
            })
        }

        val readScreen = JSONObject().apply {
            put("name", "read_screen")
            put("description", "Inspects and returns the text, descriptions, and interactive elements of the active foreground allowed app.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }

        val clickElem = JSONObject().apply {
            put("name", "click_element")
            put("description", "Clicks a button or interactive UI element in the active allowed app by its text, description, or resource ID.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("identifier", JSONObject().apply {
                        put("type", "string")
                        put("description", "The visible label, content description, or resource ID of the element to tap.")
                    })
                })
                put("required", JSONArray().apply { put("identifier") })
            })
        }

        val inputTextElem = JSONObject().apply {
            put("name", "input_text_element")
            put("description", "Enters text into an editable input field in the active allowed app.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("identifier", JSONObject().apply {
                        put("type", "string")
                        put("description", "The hint, current text, or ID of the target input field.")
                    })
                    put("text", JSONObject().apply {
                        put("type", "string")
                        put("description", "The text to insert into the field.")
                    })
                })
                put("required", JSONArray().apply {
                    put("identifier")
                    put("text")
                })
            })
        }

        toolsArray.put(openApp)
        toolsArray.put(setAlarm)
        toolsArray.put(listAllowedApps)
        toolsArray.put(runTermux)
        toolsArray.put(listRecentMessages)
        toolsArray.put(replyNotification)
        toolsArray.put(readScreen)
        toolsArray.put(clickElem)
        toolsArray.put(inputTextElem)

        return JSONArray().apply {
            put(JSONObject().apply {
                put("function_declarations", toolsArray)
            })
        }
        val scrollScreen = JSONObject().apply {
    put("name", "scroll_screen")
    put("description", "Scrolls the active allowed application up or down to reveal more items.")
    put("parameters", JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("direction", JSONObject().apply {
                put("type", "string")
                put("description", "Direction to scroll: 'down' (to see lower items) or 'up' (to see higher items).")
            })
        })
        put("required", JSONArray().apply { put("direction") })
    })
}
toolsArray.put(scrollScreen)

    }

    suspend fun execute(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val result = JSONObject()
        try {
            when (name) {
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
                            if (allowed.contains(pkg)) {
                                val label = app.loadLabel(pm).toString()
                                if (label.contains(appQuery, ignoreCase = true) || pkg.contains(appQuery, ignoreCase = true)) {
                                    targetPkg = pkg
                                    break
                                }
                            }
                        }
                    }

                    if (targetPkg == null) {
                        result.put("status", "error")
                        result.put("message", "App '$appQuery' is not installed or not added to your allowed apps list.")
                    } else {
                        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                            result.put("status", "success")
                            result.put("message", "Opened $targetPkg")
                        } else {
                            result.put("status", "error")
                            result.put("message", "Unable to create launch intent for $targetPkg")
                        }
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

                    try {
                        context.startActivity(alarmIntent)
                        result.put("status", "success")
                        result.put("message", "Alarm set for %02d:%02d with label '$message'".format(hour, minutes))
                    } catch (e: Exception) {
                        result.put("status", "error")
                        result.put("message", "Failed to set alarm: ${e.message}")
                    }
                }

                "list_allowed_apps" -> {
                    val allowed = prefsManager.getAllowedPackages()
                    val array = JSONArray()
                    for (pkg in allowed) {
                        array.put(pkg)
                    }
                    result.put("status", "success")
                    result.put("allowed_apps", array)
                }

                "run_termux_command" -> {
                    val cmd = args.getString("command")
                    val output = termuxBridge.runCommand(cmd)
                    result.put("status", "success")
                    result.put("output", output)
                }

                "list_recent_messages" -> {
                    JarvisNotificationListener.requestRebindIfDead(context)
                    val limit = args.optInt("limit", 10)
                    val messages = JarvisNotificationListener.getRecentMessages(context, limit)
                    val array = JSONArray()
                    for (msg in messages) {
                        array.put(JSONObject().apply {
                            put("id", msg.id)
                            put("package_name", msg.packageName)
                            put("sender", msg.sender)
                            put("text", msg.text)
                            put("timestamp", msg.timestamp)
                            put("can_reply", msg.replyAction != null)
                        })
                    }
                    result.put("status", "success")
                    result.put("connected", JarvisNotificationListener.isConnected())
                    result.put("messages", array)
                }

                "reply_notification" -> {
                    val id = args.getInt("message_id")
                    val replyText = args.getString("reply_text")

                    val msg = JarvisNotificationListener.getMessageById(id)
                    if (msg == null) {
                        result.put("status", "error")
                        result.put("message", "Notification with ID $id was not found or is no longer active.")
                    } else {
                        val prompt = "Send reply to ${msg.sender} via ${msg.packageName}:\n\"$replyText\""
                        val approved = confirmCallback(prompt)
                        if (!approved) {
                            result.put("status", "error")
                            result.put("message", "Reply action was cancelled by the user.")
                        } else {
                            val success = JarvisNotificationListener.sendReply(context, id, replyText)
                            if (success) {
                                result.put("status", "success")
                                result.put("message", "Reply sent to ${msg.sender}.")
                            } else {
                                result.put("status", "error")
                                result.put("message", "Failed to dispatch reply through notification system.")
                            }
                        }
                    }
                }

                "read_screen" -> {
                    val service = JarvisAccessibilityService.instance
                    if (service == null) {
                        result.put("status", "error")
                        result.put("message", "Jarvis Automation Service is not enabled in Android Accessibility settings.")
                    } else {
                        return@withContext service.getScreenContent(prefsManager.getAllowedPackages())
                    }
                }

                "click_element" -> {
                    val identifier = args.getString("identifier")
                    val approved = confirmCallback("Jarvis wants to tap on '$identifier' on screen.")
                    if (!approved) {
                        result.put("status", "error")
                        result.put("message", "Click action cancelled by user.")
                    } else {
                        val service = JarvisAccessibilityService.instance
                        if (service == null) {
                            result.put("status", "error")
                            result.put("message", "Accessibility Service is not enabled.")
                        } else {
                            val success = service.clickElement(identifier, prefsManager.getAllowedPackages())
                            result.put("status", if (success) "success" else "error")
                            result.put("message", if (success) "Clicked '$identifier'." else "Element '$identifier' not found or current foreground app is not allowed.")
                        }
                    }
                }

                "input_text_element" -> {
                    val identifier = args.getString("identifier")
                    val text = args.getString("text")
                    val approved = confirmCallback("Jarvis wants to type \"$text\" into '$identifier'.")
                    if (!approved) {
                        result.put("status", "error")
                        result.put("message", "Typing action cancelled by user.")
                    } else {
                        val service = JarvisAccessibilityService.instance
                        if (service == null) {
                            result.put("status", "error")
                            result.put("message", "Accessibility Service is not enabled.")
                        } else {
                            val success = service.inputText(identifier, text, prefsManager.getAllowedPackages())
                            result.put("status", if (success) "success" else "error")
                            result.put("message", if (success) "Entered text into '$identifier'." else "Input field not found or current foreground app is not allowed.")
                        }
                    }
                }

                else -> {
                    result.put("status", "error")
                    result.put("message", "Unknown tool function '$name'.")
                }
            }
        } catch (e: Exception) {
            result.put("status", "error")
            result.put("message", "Exception during execution: ${e.message}")
        }
        result
    }
}
