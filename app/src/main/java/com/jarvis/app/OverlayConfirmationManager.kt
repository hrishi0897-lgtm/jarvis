package com.jarvis.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object OverlayConfirmationManager {

    suspend fun requestConfirmation(context: Context, promptText: String): Boolean {
        // Fallback if overlay permission is not granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return false
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                }

                // Inflate or construct simple card
                val cardView = View.inflate(context, android.R.layout.simple_list_item_2, null) // Simple fallback or custom layout
                val container = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(48, 48, 48, 48)
                    setBackgroundColor(0xF0FFFFFF.toInt())
                    elevation = 20f
                }

                val title = TextView(context).apply {
                    text = "Jarvis Confirmation"
                    textSize = 18f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(0xFF1E1E1E.toInt())
                }

                val message = TextView(context).apply {
                    text = promptText
                    textSize = 14f
                    setTextColor(0xFF424242.toInt())
                    setPadding(0, 16, 0, 24)
                }

                val buttonRow = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }

                var isDismissed = false
                fun cleanup(result: Boolean) {
                    if (!isDismissed) {
                        isDismissed = true
                        try {
                            windowManager.removeView(container)
                        } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                }

                val btnDeny = Button(context).apply {
                    text = "Deny"
                    setOnClickListener { cleanup(false) }
                }

                val btnAllow = Button(context).apply {
                    text = "Allow"
                    setOnClickListener { cleanup(true) }
                }

                buttonRow.addView(btnDeny)
                buttonRow.addView(btnAllow)

                container.addView(title)
                container.addView(message)
                container.addView(buttonRow)

                continuation.invokeOnCancellation {
                    cleanup(false)
                }

                try {
                    windowManager.addView(container, layoutParams)
                } catch (e: Exception) {
                    continuation.resume(false)
                }
            }
        }
    }
}
