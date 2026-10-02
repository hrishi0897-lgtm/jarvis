package com.jarvis.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

object OverlayConfirmationManager {

    suspend fun requestConfirmation(context: Context, promptText: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return false
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val dm = context.resources.displayMetrics
                val dp = dm.density

                val layoutParams = WindowManager.LayoutParams(
                    (dm.widthPixels * 0.90f).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.CENTER
                    dimAmount = 0.55f
                }

                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
                    background = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = 24 * dp
                    }
                    elevation = 30 * dp
                }

                val title = TextView(context).apply {
                    text = "Confirm Critical Action"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#1A1A1A"))
                }

                val message = TextView(context).apply {
                    text = promptText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.parseColor("#4A4A4A"))
                    setPadding(0, (14 * dp).toInt(), 0, (24 * dp).toInt())
                    setLineSpacing(6f, 1f)
                }

                val buttonRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                }

                var isDismissed = false
                fun finish(allowed: Boolean) {
                    if (!isDismissed) {
                        isDismissed = true
                        try {
                            windowManager.removeView(card)
                        } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(allowed)
                        }
                    }
                }

                val btnDeny = Button(context).apply {
                    text = "Deny"
                    setTextColor(Color.parseColor("#5A626A"))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F1F3F5"))
                        cornerRadius = 14 * dp
                    }
                    setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
                    setOnClickListener { finish(false) }
                }

                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams((12 * dp).toInt(), 1)
                }

                val btnAllow = Button(context).apply {
                    text = "Allow"
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#D32F2F")) // Red alert color for destructive action
                        cornerRadius = 14 * dp
                    }
                    setPadding((24 * dp).toInt(), (10 * dp).toInt(), (24 * dp).toInt(), (10 * dp).toInt())
                    setOnClickListener { finish(true) }
                }

                buttonRow.addView(btnDeny)
                buttonRow.addView(spacer)
                buttonRow.addView(btnAllow)

                card.addView(title)
                card.addView(message)
                card.addView(buttonRow)

                continuation.invokeOnCancellation { finish(false) }

                try {
                    windowManager.addView(card, layoutParams)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }
    }
}
