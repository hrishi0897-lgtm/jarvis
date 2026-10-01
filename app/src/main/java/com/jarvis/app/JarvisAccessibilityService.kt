package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Battery preservation: event stream ignored while idle
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun getScreenContent(allowedPackages: Set<String>): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().apply {
            put("status", "error")
            put("message", "No active window found or screen is off.")
        }

        val currentPackage = root.packageName?.toString() ?: ""
        if (!allowedPackages.contains(currentPackage)) {
            return JSONObject().apply {
                put("status", "error")
                put("message", "Current foreground app ($currentPackage) is not in the allowed list.")
            }
        }

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

        if (text.isNotBlank() || contentDesc.isNotBlank() || isClickable || isEditable) {
            elements.put(JSONObject().apply {
                if (text.isNotBlank()) put("text", text)
                if (contentDesc.isNotBlank()) put("description", contentDesc)
                if (viewId.isNotBlank()) put("id", viewId)
                put("clickable", isClickable)
                put("editable", isEditable)
            })
        }

        for (i in 0 until node.childCount) {
            collectInteractiveElements(node.getChild(i), elements)
        }
    }

    fun clickElement(identifier: String, allowedPackages: Set<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val currentPackage = root.packageName?.toString() ?: ""
        if (!allowedPackages.contains(currentPackage)) return false

        val target = findNode(root, identifier) ?: return false
        return if (target.isClickable) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            target.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }
    }

    fun inputText(identifier: String, text: String, allowedPackages: Set<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val currentPackage = root.packageName?.toString() ?: ""
        if (!allowedPackages.contains(currentPackage)) return false

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
            id.equals(query, ignoreCase = true)) {
            return root
        }

        for (i in 0 until root.childCount) {
            val result = findNode(root.getChild(i), query)
            if (result != null) return result
        }
        return null
    }
}
