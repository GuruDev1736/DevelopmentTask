@file:SuppressLint("AccessibilityService", "SwitchIntDef")

package com.guruprasad.developmenttask.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility Service that monitors UI interactions across the device.
 *
 * ## What it does
 * - Detects screen change events ([AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED])
 * - Detects user click events ([AccessibilityEvent.TYPE_VIEW_CLICKED])
 * - Reads all visible text on the screen when a window changes or a click occurs
 * - Captures the app package name of the active window
 * - Logs all captured information to Android Logcat under tag [TAG]
 *
 * ## How to enable
 * Navigate to: **Settings → Accessibility → BLE App Accessibility Logger → Enable**
 *
 * ## Permissions
 * Declared in AndroidManifest.xml with `android.permission.BIND_ACCESSIBILITY_SERVICE`.
 * Configured via `res/xml/accessibility_service_config.xml`.
 *
 * ## Privacy note
 * This service only logs to Logcat for debugging and testing purposes.
 * No data is stored, transmitted, or shared externally.
 */
@SuppressLint("AccessibilityService")
class BleAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BleAccessibility"
        private const val MAX_TEXT_LENGTH = 500
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        Log.i(TAG, "BleAccessibilityService connected and ready")
    }

    @SuppressLint("SwitchIntDef")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val packageName = event.packageName?.toString() ?: "unknown"
        val eventType = AccessibilityEvent.eventTypeToString(event.eventType)

        when (event.eventType) {

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val activityName = event.className?.toString() ?: "unknown"
                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d(TAG, "SCREEN CHANGE detected")
                Log.d(TAG, "  Package   : $packageName")
                Log.d(TAG, "  Activity  : $activityName")
                Log.d(TAG, "  EventType : $eventType")
                readAllVisibleText(packageName)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val clickedText = extractNodeText(event)
                val contentDesc = event.contentDescription?.toString()
                val viewId = event.source?.viewIdResourceName ?: "no-id"
                val className = event.className?.toString() ?: "unknown"

                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d(TAG, "CLICK EVENT detected")
                Log.d(TAG, "  Package       : $packageName")
                Log.d(TAG, "  View class    : $className")
                Log.d(TAG, "  View ID       : $viewId")
                Log.d(TAG, "  Clicked text  : ${clickedText.ifEmpty { "(none)" }}")
                Log.d(TAG, "  Content desc  : ${contentDesc ?: "(none)"}")
                readAllVisibleText(packageName)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val contentDesc = event.contentDescription?.toString()
                if (!contentDesc.isNullOrBlank()) {
                    Log.v(TAG, "CONTENT CHANGE | Package: $packageName | Desc: $contentDesc")
                }
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val focusedText = extractNodeText(event)
                val viewId = event.source?.viewIdResourceName ?: "no-id"
                if (focusedText.isNotBlank()) {
                    Log.d(TAG, "FOCUS EVENT | Package: $packageName | ID: $viewId | Text: $focusedText")
                }
            }

            else -> { /* Other event types not relevant — ignored */ }
        }
    }

    /**
     * Traverses the entire active window node tree and collects all visible text.
     * Results are logged as a structured list to Logcat.
     *
     * @param packageName The package name of the active window (for context in logs).
     */
    @Suppress("DEPRECATION")
    private fun readAllVisibleText(packageName: String) {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "readAllVisibleText: rootInActiveWindow is null for $packageName")
            return
        }

        val visibleTexts = mutableListOf<String>()
        collectVisibleText(rootNode, visibleTexts)
        rootNode.recycle()

        if (visibleTexts.isEmpty()) {
            Log.d(TAG, "  Visible text  : (none found on screen)")
        } else {
            Log.d(TAG, "  Visible text on screen (${visibleTexts.size} items) [$packageName]:")
            visibleTexts.forEachIndexed { index, text ->
                Log.d(TAG, "    [$index] $text")
            }
        }
    }

    /**
     * Recursively walks the [AccessibilityNodeInfo] tree collecting all visible,
     * non-empty text and content descriptions into [results].
     *
     * @param node The root node to start traversal from.
     * @param results Accumulator list for collected text strings.
     */
    @Suppress("DEPRECATION")
    private fun collectVisibleText(node: AccessibilityNodeInfo?, results: MutableList<String>) {
        node ?: return

        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()
        } else null

        when {
            !text.isNullOrEmpty() && text.length <= MAX_TEXT_LENGTH -> results.add(text)
            !contentDesc.isNullOrEmpty() && contentDesc.length <= MAX_TEXT_LENGTH -> results.add("[$contentDesc]")
            !hintText.isNullOrEmpty() -> results.add("(hint: $hintText)")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectVisibleText(child, results)
            child?.recycle()
        }
    }

    /**
     * Extracts the primary text from an [AccessibilityEvent] by checking
     * event text list, content description, and source node text in order.
     *
     * @param event The accessibility event to extract text from.
     * @return The extracted text, or an empty string if nothing found.
     */
    private fun extractNodeText(event: AccessibilityEvent): String {
        val eventText = event.text.joinToString(separator = " ") { it.toString() }.trim()
        if (eventText.isNotEmpty()) return eventText

        val contentDesc = event.contentDescription?.toString()?.trim()
        if (!contentDesc.isNullOrEmpty()) return contentDesc

        return event.source?.text?.toString()?.trim() ?: ""
    }

    override fun onInterrupt() {
        Log.w(TAG, "BleAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "BleAccessibilityService destroyed")
    }
}
