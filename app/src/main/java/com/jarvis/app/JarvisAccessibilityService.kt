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
        val allWindows = try { windows } catch (_: Exception) { null }
        if (!allWindows.isNullOrEmpty()) {
            for (w in allWindows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString() ?: ""
                    if (pkg != packageName && allowedPackages.contains(pkg)) {
                        return root
                    }
                }
            }
        }

        val active = rootInActiveWindow ?: return null
        val activePkg = active.packageName?.toString() ?: ""
        if (activePkg != packageName && allowedPackages.contains(activePkg)) {
            return active
        }
        return null
    }

    fun getScreenContent(allowedPackages: Set<String>): JSONObject {
        val root = getTargetAppRoot(allowedPackages) ?: return JSONObject().apply {
            put("status", "error")
            put("message", "No allowed background or foreground application window found. Ensure the target app is running and enabled in Apps tab.")
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

    fun scrollScreen(direction: String, allowedPackages: Set<String>): Boolean {
        val root = getTargetAppRoot(allowedPackages) ?: return false

        val scrollableNode = findScrollableNode(root)
        if (scrollableNode != null) {
            val action = if (direction.equals("down", ignoreCase = true)) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollableNode.performAction(action)) {
                return true
            }
        }

        val metrics: DisplayMetrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val centerX = width / 2f

        val startY: Float
        val endY: Float

        if (direction.equals("down", ignoreCase = true)) {
            startY = height * 0.75f
            endY = height * 0.25f
        } else {
            startY = height * 0.25f
            endY = height * 0.75f
        }

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node

        for (i in 0 until node.childCount) {
            val found = findScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
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

            val clickPath = Path().apply {
                moveTo(tapX, tapY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 80))
                .build()
            return dispatchGesture(gesture, null, null)
        }

        return false
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
