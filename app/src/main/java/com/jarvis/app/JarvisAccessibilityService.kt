package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
        val inputNode = findEditableNode(root) ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val textSet = inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!textSet) return false

        if (!autoSend) return true

        // Directly find and tap the send button immediately
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

        var bestContainer: AccessibilityNodeInfo? = null
        var p = target.parent
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels

        while (p != null) {
            val pBounds = Rect()
            p.getBoundsInScreen(pBounds)
            if (pBounds.width() > screenWidth * 0.5f) {
                bestContainer = p
                break
            }
            p = p.parent
        }

        if (bestContainer != null && bestContainer.isClickable && bestContainer.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        val targetBounds = Rect()
        target.getBoundsInScreen(targetBounds)
        if (target.isClickable && targetBounds.width() > 100 && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        val clickBounds = if (bestContainer != null) {
            val b = Rect()
            bestContainer.getBoundsInScreen(b)
            b
        } else {
            targetBounds
        }

        if (!clickBounds.isEmpty) {
            val tapX = (clickBounds.left + clickBounds.width() * 0.60f).coerceIn(clickBounds.left.toFloat(), clickBounds.right.toFloat())
            val tapY = clickBounds.exactCenterY()
            return tapCoordinates(tapX, tapY)
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
