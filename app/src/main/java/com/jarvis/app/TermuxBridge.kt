package com.jarvis.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

class TermuxBridge(
    private val context: Context,
    private val confirmCallback: suspend (String) -> Boolean
) {

    companion object {
        private const val TERMUX_PACKAGE = "com.termux"
        private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

        private const val EXTRA_RUN_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_RUN_COMMAND_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_RUN_COMMAND_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_RUN_COMMAND_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        private const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME_PATH = "/data/data/com.termux/files/home"

        private val SAFE_EXACT_COMMANDS = setOf(
            "ls", "pwd", "date", "whoami", "uname", "uname -a",
            "df", "df -h", "uptime", "termux-battery-status",
            "termux-wifi-connectioninfo", "git status", "git log", "git branch"
        )

        private val SHELL_CONTROL_OPERATORS = listOf(";", "&", "|", "<", ">", "`", "$(")
    }

    private fun isCommandSafe(command: String): Boolean {
        val trimmed = command.trim()
        if (SHELL_CONTROL_OPERATORS.any { trimmed.contains(it) }) {
            return false
        }
        return SAFE_EXACT_COMMANDS.contains(trimmed)
    }

    suspend fun runCommand(command: String): TermuxExecutionResult = withContext(Dispatchers.IO) {
        val trimmedCommand = command.trim()
        if (trimmedCommand.isEmpty()) {
            return@withContext TermuxExecutionResult("", "Command cannot be empty", -1)
        }

        if (!isCommandSafe(trimmedCommand)) {
            val approved = confirmCallback("Jarvis wants to execute this Termux command:\n\n$trimmedCommand")
            if (!approved) {
                return@withContext TermuxExecutionResult("", "Execution denied by user.", -1)
            }
        }

        val executionId = UUID.randomUUID().toString()

        val callbackIntent = Intent(context, TermuxResultReceiver::class.java).apply {
            action = TermuxResultReceiver.ACTION_TERMUX_RESULT
            putExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, executionId)
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT
        } else {
            PendingIntent.FLAG_ONE_SHOT
        }

        val resultPendingIntent = PendingIntent.getBroadcast(
            context,
            executionId.hashCode(),
            callbackIntent,
            pendingIntentFlags
        )

        val runIntent = Intent(ACTION_RUN_COMMAND).apply {
            component = ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(EXTRA_RUN_COMMAND_PATH, TERMUX_BASH_PATH)
            putExtra(EXTRA_RUN_COMMAND_ARGUMENTS, arrayOf("-c", trimmedCommand))
            putExtra(EXTRA_RUN_COMMAND_WORKDIR, TERMUX_HOME_PATH)
            putExtra(EXTRA_RUN_COMMAND_BACKGROUND, true)
            putExtra(EXTRA_PENDING_INTENT, resultPendingIntent)
        }

        val rawResult = withTimeoutOrNull(25000L) {
            suspendCancellableCoroutine<TermuxExecutionResult> { continuation: CancellableContinuation<TermuxExecutionResult> ->
                TermuxResultReceiver.pendingContinuations[executionId] = continuation
                continuation.invokeOnCancellation {
                    TermuxResultReceiver.pendingContinuations.remove(executionId)
                }

                try {
                    ContextCompat.startForegroundService(context, runIntent)
                } catch (e: Exception) {
                    TermuxResultReceiver.pendingContinuations.remove(executionId)
                    if (continuation.isActive) {
                        continuation.resume(
                            TermuxExecutionResult("", "Failed to start Termux service: ${e.message}", -1)
                        )
                    }
                }
            }
        }

        val finalResult = rawResult ?: TermuxExecutionResult("", "Command execution timed out after 25 seconds.", -1)

        val truncatedStdout = if (finalResult.stdout.length > 4000) {
            finalResult.stdout.take(4000) + "\n...[stdout truncated]"
        } else {
            finalResult.stdout
        }

        val truncatedStderr = if (finalResult.stderr.length > 2000) {
            finalResult.stderr.take(2000) + "\n...[stderr truncated]"
        } else {
            finalResult.stderr
        }

        TermuxExecutionResult(truncatedStdout, truncatedStderr, finalResult.exitCode)
    }
}
