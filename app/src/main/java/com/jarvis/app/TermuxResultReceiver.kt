package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellableContinuation
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

data class TermuxExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

class TermuxResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TERMUX_RESULT = "com.jarvis.app.TERMUX_RESULT"
        const val EXTRA_EXECUTION_ID = "execution_id"

        val pendingContinuations = ConcurrentHashMap<String, CancellableContinuation<TermuxExecutionResult>>()
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TERMUX_RESULT) return

        val executionId = intent.getStringExtra(EXTRA_EXECUTION_ID) ?: return
        val continuation = pendingContinuations.remove(executionId) ?: return

        val resultBundle = intent.getBundleExtra("result")
        val stdout = resultBundle?.getString("stdout") ?: ""
        val stderr = resultBundle?.getString("stderr") ?: ""
        val exitCode = resultBundle?.getInt("exitCode", -1) ?: -1

        if (continuation.isActive) {
            continuation.resume(TermuxExecutionResult(stdout, stderr, exitCode))
        }
    }
}
