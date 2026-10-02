package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private fun getTargetAppRoot(allowedPackages: Set<String>): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null) {
            val pkg = active.packageName?.toString() ?: ""
            if (pkg != packageName && (allowedPackages.isEmpty() || allowedPackages.contains(pkg))) {
                return active
            }
        }

        val allWindows = try { windows } catch (_: Exception) { null }
        if (!allWindows.isNullOrEmpty()) {
            for (w in allWindows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString() ?: ""
                    if (pkg != packageName && (allowedPackages.isEmpty() || allowedPackages.contains(pkg))) {
                        return root
                    }
                }
            }
        }
        return active
    }

    fun getScreenContent(allowedPackages: Set<String>): JSONObject {
        val root = getTargetAppRoot(allowedPackages) ?: return JSONObject().apply {
            put("status", "error")
            put("message", "No foreground window found.")
        }

        val currentPackage = root.packageName?.toString() ?: ""
        val elementsArray = JSONArray()
        collectInteractiveElements(root, elementsArray)

        return JSONObject().apply {
            put("status", "success")
            put("package_name", currentPackage)
            put("elements", elementsArray)
        }
    }

    private fun collectInteractiveElements(node: AccessibilityNodeInfo?, elements: JSONArray) {
        if (node == null) return

        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable

        if (text.isNotBlank() || contentDesc.isNotBlank() || isClickable || isEditable || isScrollable) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            elements.put(JSONObject().apply {
                if (text.isNotBlank()) put("text", text)
                if (contentDesc.isNotBlank()) put("description", contentDesc)
                if (viewId.isNotBlank()) put("id", viewId)
                put("clickable", isClickable)
                put("editable", isEditable)
                put("scrollable", isScrollable)
                put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
            })
        }

        for (i in 0 until node.childCount) {
            collectInteractiveElements(node.getChild(i), elements)
        }
    }

    fun triggerGlobalAction(actionName: String): Boolean {
        return when (actionName.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "split_screen" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                } else {
                    false
                }
            }
            else -> false
        }
    }

    fun swipe(direction: String): Boolean {
        val metrics: DisplayMetrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        var startX = centerX
        var startY = centerY
        var endX = centerX
        var endY = centerY

        when (direction.lowercase()) {
            "down" -> {
                startY = height * 0.70f
                endY = height * 0.30f
            }
            "up" -> {
                startY = height * 0.30f
                endY = height * 0.70f
            }
            "left" -> {
                startX = width * 0.85f
                endX = width * 0.15f
            }
            "right" -> {
                startX = width * 0.15f
                endX = width * 0.85f
            }
            else -> return false
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    fun typeAndSend(text: String, allowedPackages: Set<String>, autoSend: Boolean = true): Boolean {
        val root = getTargetAppRoot(allowedPackages) ?: return false
        val inputNode = findChatComposer(root) ?: findEditableNode(root) ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!textSet) return false

        if (!autoSend) return true

        val sendBtn = findSendButton(root)
        if (sendBtn != null) {
            if (sendBtn.isClickable && sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            val bounds = Rect()
            sendBtn.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                return tapCoordinates(bounds.exactCenterX(), bounds.exactCenterY())
            }
        }
        return true
    }

    fun sendWhatsAppMessage(recipient: String, messageText: String, allowedPackages: Set<String>): Boolean {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp") ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)

        var root: AccessibilityNodeInfo? = null
        for (attempt in 0..6) {
            try { Thread.sleep(250) } catch (_: Exception) {}
            root = getTargetAppRoot(allowedPackages)
            if (root?.packageName?.toString() == "com.whatsapp") break
        }
        if (root == null) return false

        val contactNode = findNode(root, recipient)
        if (contactNode != null) {
            clickElement(recipient, allowedPackages)
        } else {
            val searchNode = findNode(root, "Search") ?: findEditableNode(root)
            if (searchNode != null) {
                if (searchNode.isEditable) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, recipient)
                    }
                    searchNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } else {
                    searchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    try { Thread.sleep(300) } catch (_: Exception) {}
                    val updatedRoot = getTargetAppRoot(allowedPackages)
                    val editable = findEditableNode(updatedRoot)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, recipient)
                    }
                    editable?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }

                try { Thread.sleep(600) } catch (_: Exception) {}
                val resultsRoot = getTargetAppRoot(allowedPackages)
                val resultContact = findNode(resultsRoot, recipient)
                if (resultContact != null) {
                    clickElement(recipient, allowedPackages)
                } else {
                    return false
                }
            }
        }

        try { Thread.sleep(500) } catch (_: Exception) {}
        val chatRoot = getTargetAppRoot(allowedPackages) ?: return false

        val composer = findChatComposer(chatRoot) ?: findEditableNode(chatRoot) ?: return false
        val textArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, messageText)
        }
        composer.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs)

        try { Thread.sleep(200) } catch (_: Exception) {}
        val sendBtn = findSendButton(getTargetAppRoot(allowedPackages) ?: chatRoot)
        if (sendBtn != null) {
            if (sendBtn.isClickable && sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            val bounds = Rect()
            sendBtn.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                return tapCoordinates(bounds.exactCenterX(), bounds.exactCenterY())
            }
        }

        return true
    }

    private fun findChatComposer(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        if (node.isEditable && (text.contains("Message", ignoreCase = true) || 
            desc.contains("Message", ignoreCase = true) || 
            id.endsWith("entry"))) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = findChatComposer(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node

        for (i in 0 until node.childCount) {
            val found = findEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        if (desc.equals("Send", ignoreCase = true) ||
            desc.contains("send", ignoreCase = true) ||
            id.endsWith("send_btn") ||
            id.endsWith("send")) {
            return node
        }

        for (i in 0 until node.childCount) {
            val found = findSendButton(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    fun clickElement(identifier: String, allowedPackages: Set<String>): Boolean {
        val root = getTargetAppRoot(allowedPackages) ?: return false
        val target = findNode(root, identifier) ?: return false

        // 1. Direct click on node if clickable
        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 2. Climb up parent hierarchy to find clickable wrapper (cards, tiles, list items)
        var p = target.parent
        while (p != null) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            p = p.parent
        }

        // 3. Fallback: Dispatch exact coordinate tap on the center of the element bounds
        val targetBounds = Rect()
        target.getBoundsInScreen(targetBounds)
        if (!targetBounds.isEmpty) {
            return tapCoordinates(targetBounds.exactCenterX(), targetBounds.exactCenterY())
        }

        return false
    }

    fun tapCoordinates(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun inputText(identifier: String, text: String, allowedPackages: Set<String>): Boolean {
        val root = getTargetAppRoot(allowedPackages) ?: return false
        val target = findNode(root, identifier) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findNode(root: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (root == null) return null

        val text = root.text?.toString() ?: ""
        val desc = root.contentDescription?.toString() ?: ""
        val id = root.viewIdResourceName ?: ""

        if (text.contains(query, ignoreCase = true) ||
            desc.contains(query, ignoreCase = true) ||
            id.contains(query, ignoreCase = true)) {
            return root
        }

        for (i in 0 until root.childCount) {
            val res = findNode(root.getChild(i), query)
            if (res != null) return res
        }
        return null
    }
}
