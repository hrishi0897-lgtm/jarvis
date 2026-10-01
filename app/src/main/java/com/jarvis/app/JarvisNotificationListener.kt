package com.jarvis.app

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
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
        private var instance: JarvisNotificationListener? = null
        private val idCounter = AtomicInteger(1)
        private val messageList = mutableListOf<CapturedMessage>()
        private val messageMap = ConcurrentHashMap<Int, CapturedMessage>()
        private const val MAX_MESSAGES = 40

        fun isConnected(): Boolean = instance != null

        fun getRecentMessages(context: Context, limit: Int): List<CapturedMessage> {
            // Actively harvest existing notifications from the shade if connected
            instance?.harvestActiveNotifications()

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

        fun requestRebindIfDead(context: Context) {
            try {
                requestRebind(ComponentName(context, JarvisNotificationListener::class.java))
            } catch (_: Exception) {}
        }
    }

    private lateinit var prefsManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefsManager = PreferencesManager(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        harvestActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun harvestActiveNotifications() {
        try {
            val active = activeNotifications ?: return
            for (sbn in active) {
                processNotification(sbn)
            }
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        processNotification(sbn)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val allowed = prefsManager.getAllowedPackages()
        if (!allowed.contains(pkg)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // 1. Extract title/sender
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
            ?: "Unknown"

        // 2. Extract content (covers standard text, big text, WhatsApp message lines)
        var content = ""

        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!textLines.isNullOrEmpty()) {
            content = textLines.lastOrNull()?.toString() ?: ""
        }

        if (content.isBlank()) {
            content = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        }

        if (content.isBlank()) {
            content = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        }

        if (content.isBlank()) return

        // 3. Search for inline reply action
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

        // Avoid adding duplicate entries for same package + sender + text within close timeframe
        synchronized(messageList) {
            val isDuplicate = messageList.any { 
                it.packageName == pkg && it.sender == title && it.text == content && (sbn.postTime - it.timestamp < 3000)
            }
            if (isDuplicate) return

            val captured = CapturedMessage(
                id = idCounter.getAndIncrement(),
                packageName = pkg,
                sender = title,
                text = content,
                timestamp = sbn.postTime,
                replyAction = inlineAction
            )

            messageList.add(0, captured)
            messageMap[captured.id] = captured

            if (messageList.size > MAX_MESSAGES) {
                val removed = messageList.removeAt(messageList.size - 1)
                messageMap.remove(removed.id)
            }
        }
    }
}
