package com.jarvis.app

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-request structured trace. Writes to:
 *   1) Android logcat (tag = JarvisTrace) — visible in Android Studio / adb
 *   2) A rolling file at Android/data/com.jarvis.app/files/trace.log — readable from Termux
 *
 * The file output exists because Termux cannot read logcat on Android 10+ without root.
 *
 * Never changes behavior — only observes.
 */
object JarvisLogger {

    private const val TAG = "JarvisTrace"
    private const val MAX_LINES = 500
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024  // 2 MB rolling

    @Volatile private var logFile: File? = null

    private val buffer = ArrayDeque<String>(MAX_LINES)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Called once from Application/Activity startup. Safe to call multiple times.
     * Uses app-specific external storage so no permissions are needed.
     */
    @Synchronized
    fun init(ctx: Context) {
        if (logFile != null) return
        try {
            // Public shared storage: /storage/emulated/0/Jarvis/logs/trace.log
            // Termux can read this on every Android version (no root, no adb).
            // Android/data/... is NOT readable by Termux on Android 11+.
            val root = Environment.getExternalStorageDirectory()
            val dir = File(root, "Jarvis/logs").apply { mkdirs() }
            logFile = File(dir, "trace.log")
            step("=== logger ready at ${logFile?.absolutePath} ===")
        } catch (e: Exception) {
            Log.w(TAG, "could not open log file: ${e.message}")
        }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        // Do NOT truncate the file — we want history across requests.
        // A separator line marks a new request.
        appendToFile("---------- new request ----------")
    }

    @Synchronized
    fun step(msg: String) {
        val line = timeFmt.format(Date()) + "  " + msg
        buffer.addLast(line)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        Log.d(TAG, msg)
        appendToFile(line)
    }

    @Synchronized
    fun toolCall(
        step: Int,
        tool: String,
        argsPreview: String,
        status: String,
        messagePreview: String,
        durationMs: Long
    ) {
        val line = buildString {
            append("step=").append(step)
            append(" tool=").append(tool)
            append(" args=").append(argsPreview.take(80))
            append(" status=").append(status)
            append(" ms=").append(durationMs)
            append(" msg=").append(messagePreview.replace('\n', ' ').take(120))
        }
        step(line)
    }

    @Synchronized
    fun dump(): String = if (buffer.isEmpty()) "(no trace)" else buffer.joinToString("\n")

    @Synchronized
    fun tail(n: Int): List<String> = buffer.toList().takeLast(n)

    // ---- internal --------------------------------------------------------

    private fun appendToFile(line: String) {
        val f = logFile ?: return
        try {
            // Roll if too big
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val bak = File(f.parentFile, "trace.log.old")
                if (bak.exists()) bak.delete()
                f.renameTo(bak)
            }
            f.appendText(line + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "file write failed: ${e.message}")
        }
    }
}
