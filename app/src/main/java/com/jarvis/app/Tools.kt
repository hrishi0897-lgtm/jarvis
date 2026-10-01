package com.jarvis.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class Tools(
    private val context: Context,
    private val prefsManager: PreferencesManager,
    private val confirmCallback: suspend (String) -> Boolean
) {

    fun getToolDeclarations(): JSONArray {
        val listApps = JSONObject().apply {
            put("name", "list_allowed_apps")
            put("description", "Lists all applications currently allowed to be accessed by Jarvis.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }

        val openApp = JSONObject().apply {
            put("name", "open_app")
            put("description", "Opens an allowed application on the phone given its package name.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package_name", JSONObject().apply {
                        put("type", "string")
                        put("description", "The Android package name of the app to launch.")
                    })
                })
                put("required", JSONArray().apply { put("package_name") })
            })
        }

        val setAlarm = JSONObject().apply {
            put("name", "set_alarm")
            put("description", "Sets an alarm for a specific hour and minute.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("hour", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Hour of day (0-23)")
                    })
                    put("minute", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Minute of hour (0-59)")
                    })
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "Optional label for the alarm")
                    })
                })
                put("required", JSONArray().apply {
                    put("hour")
                    put("minute")
                })
            })
        }

        return JSONArray().apply {
            put(JSONObject().apply { put("function_declarations", JSONArray().apply {
                put(listApps)
                put(openApp)
                put(setAlarm)
            }) })
        }
    }

    suspend fun execute(name: String, args: JSONObject): JSONObject = withContext(Dispatchers.Main) {
        val result = JSONObject()
        try {
            when (name) {
                "list_allowed_apps" -> {
                    val allowed = prefsManager.getAllowedPackages()
                    val pm = context.packageManager
                    val array = JSONArray()
                    for (pkg in allowed) {
                        try {
                            val appInfo = pm.getApplicationInfo(pkg, 0)
                            val label = pm.getApplicationLabel(appInfo).toString()
                            array.put(JSONObject().apply {
                                put("package_name", pkg)
                                put("app_name", label)
                            })
                        } catch (_: PackageManager.NameNotFoundException) {
                            array.put(JSONObject().apply {
                                put("package_name", pkg)
                                put("app_name", pkg)
                            })
                        }
                    }
                    result.put("status", "success")
                    result.put("allowed_apps", array)
                }

                "open_app" -> {
                    val pkg = args.optString("package_name")
                    val allowed = prefsManager.getAllowedPackages()
                    if (!allowed.contains(pkg)) {
                        result.put("status", "error")
                        result.put("message", "App '$pkg' is not in the allowed list.")
                    } else {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launchIntent)
                            result.put("status", "success")
                            result.put("message", "App $pkg launched.")
                        } else {
                            result.put("status", "error")
                            result.put("message", "Launch intent not found for $pkg.")
                        }
                    }
                }

                "set_alarm" -> {
                    val hour = args.getInt("hour")
                    val minute = args.getInt("minute")
                    val label = args.optString("label", "Alarm")

                    val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    if (intent.resolveActivity(context.packageManager) != null) {
                        context.startActivity(intent)
                        result.put("status", "success")
                        result.put("message", "Alarm set for %02d:%02d with label '$label'".format(hour, minute))
                    } else {
                        result.put("status", "error")
                        result.put("message", "No clock application available to handle alarms.")
                    }
                }

                else -> {
                    result.put("status", "error")
                    result.put("message", "Unknown tool function: $name")
                }
            }
        } catch (e: Exception) {
            result.put("status", "error")
            result.put("message", e.message ?: "Execution failed")
        }
        result
    }
}
