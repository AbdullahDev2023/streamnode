package com.akdevelopers.streamnode.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * StreamNodeAccessibilityService — bridges Android's AccessibilityService lifecycle
 * to [InputInjector] so remote input events can be dispatched without root.
 *
 * To activate: Settings → Accessibility → StreamNode → Enable.
 * Once enabled, the OS keeps this service running and [InputInjector] can dispatch
 * gestures via [AccessibilityService.dispatchGesture] and global actions.
 *
 * Declared in feature:stream AndroidManifest.xml with:
 *   android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
 *   and a matching res/xml/accessibility_service_config.xml.
 */
class StreamNodeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SN_A11yService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "onServiceConnected")
        serviceInfo = serviceInfo.apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            notificationTimeout = 100
        }
        InputInjector.bind(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event processing needed — we only dispatch gestures, not consume events.
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt")
        InputInjector.unbind()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        InputInjector.unbind()
        super.onDestroy()
    }
}
