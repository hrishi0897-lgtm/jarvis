package com.jarvis.app

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class CapturedMessage(
    val id: Int,
    val packageName: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
    val replyAction: Notification.Action?
)

class JarvisNotificationListener : NotificationListenerService() {

    companion object {
        private val idCounter = AtomicInteger(1)
        private val messageList = mutableListOf<CapturedMessage>()
        private val messageMap = ConcurrentHashMap<Int, CapturedMessage>()
        private const val MAX_MESSAGES = 40

        fun getRecentMessages(limit: Int): List<CapturedMessage> {
            synchronized(messageList) {
                return messageList.take(limit.coerceIn(1, MAX_MESSAGES)).toList()
            }
        }

        fun getMessageById(id: Int): CapturedMessage? {
            return messageMap[id]
        }

        fun sendReply(context: Context, id: Int, replyText: String): Boolean {
            val message = messageMap[id] ?: return false
            val action = message.replyAction ?: return false

            val remoteInputs = action.remoteInputs ?: return false
            val intent = Intent()
            val bundle = Bundle()

            for (input in remoteInputs) {
                bundle.putCharSequence(input.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)

            return try {
                action.actionIntent.send(context, 0, intent)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private lateinit var prefsManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName

        // Filter against allowlist
        val allowed = prefsManager.getAllowedPackages()
        if (!allowed.contains(pkg)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Extract title/sender and text
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Unknown"
        var content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Handle messaging style if available
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messages.isNullOrEmpty()) {
            val lastMsgBundle = messages.last() as? Bundle
            val body = lastMsgBundle?.getCharSequence("text")?.toString()
            if (!body.isNullOrBlank()) {
                content = body
            }
        }

        if (content.isBlank()) return

        // Search for inline reply action
        var inlineAction: Notification.Action? = null
        val actions = notification.actions
        if (actions != null) {
            for (act in actions) {
                val inputs = act.remoteInputs
                if (inputs != null) {
                    for (input in inputs) {
                        if (input.allowFreeFormInput) {
                            inlineAction = act
                            break
                        }
                    }
                }
                if (inlineAction != null) break
            }
        }

        val captured = CapturedMessage(
            id = idCounter.getAndIncrement(),
            packageName = pkg,
            sender = title,
            text = content,
            timestamp = sbn.postTime,
            replyAction = inlineAction
        )

        synchronized(messageList) {
            messageList.add(0, captured)
            messageMap[captured.id] = captured
            if (messageList.size > MAX_MESSAGES) {
                val removed = messageList.removeAt(messageList.size - 1)
                messageMap.remove(removed.id)
            }
        }
    }
}
