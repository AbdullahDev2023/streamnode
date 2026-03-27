package com.akdevelopers.streamnode.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.util.Log
import android.view.WindowManager
import com.akdevelopers.streamnode.core.AppConstants

/**
 * InputInjector — dispatches remote pointer/key input onto the device screen.
 *
 * Phase 2b strategy: [AccessibilityService.dispatchGesture] — no root required,
 * passes Play Store review, works on unmodified consumer devices.
 *
 * The service (StreamNodeAccessibilityService, declared separately in the
 * feature:stream manifest) holds the live [AccessibilityService] instance and
 * passes it here via [bind] / [unbind].
 *
 * All coordinates arriving from the browser are normalised 0–1 ratios.
 * [inject] converts them to physical pixels using the screen's real resolution.
 *
 * JSON format (from browser /control WS):
 *   { "type":"input", "kind":"tap",   "x":0.42, "y":0.71 }
 *   { "type":"input", "kind":"swipe", "x1":0.5, "y1":0.8, "x2":0.5, "y2":0.2, "durationMs":300 }
 *   { "type":"input", "kind":"key",   "keycode":3 }
 */
object InputInjector {

    private const val TAG = "AC_InputInjector"

    @Volatile private var service: AccessibilityService? = null
    private var screenW = 0
    private var screenH = 0

    /** Called by StreamNodeAccessibilityService.onServiceConnected(). */
    fun bind(svc: AccessibilityService) {
        service = svc
        refreshScreenSize(svc)
        Log.i(TAG, "bind: AccessibilityService attached ${screenW}x${screenH}")
    }

    /** Called by StreamNodeAccessibilityService.onInterrupt() / onDestroy(). */
    fun unbind() {
        service = null
        Log.i(TAG, "unbind: AccessibilityService detached")
    }

    private fun refreshScreenSize(svc: AccessibilityService) {
        val wm   = svc.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val size = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(size)
        screenW = size.x; screenH = size.y
    }

    /**
     * Dispatch a remote input event.
     *
     * @param kind     [AppConstants.INPUT_KIND_TAP] | [AppConstants.INPUT_KIND_SWIPE]
     *                 | [AppConstants.INPUT_KIND_KEY]
     * @param x, y    Normalised coords (0–1) for tap.
     * @param x1,y1,x2,y2  Normalised coords for swipe.
     * @param durationMs   Swipe duration.
     * @param keycode  Android KEYCODE_* integer for key events.
     */
    fun inject(
        kind:       String,
        x:          Float = 0f, y: Float = 0f,
        x1:         Float = 0f, y1: Float = 0f,
        x2:         Float = 0f, y2: Float = 0f,
        durationMs: Long  = 150L,
        keycode:    Int   = 0,
    ) {
        val svc = service ?: run {
            Log.w(TAG, "inject: AccessibilityService not bound — is it enabled?")
            return
        }
        when (kind) {
            AppConstants.INPUT_KIND_TAP   -> dispatchTap(svc, x, y)
            AppConstants.INPUT_KIND_SWIPE -> dispatchSwipe(svc, x1, y1, x2, y2, durationMs)
            AppConstants.INPUT_KIND_KEY   -> dispatchKey(svc, keycode)
            else -> Log.w(TAG, "inject: unknown kind '$kind'")
        }
    }

    // ── Gesture helpers ────────────────────────────────────────────────────────

    private fun px(norm: Float, dim: Int): Float = norm * dim

    private fun dispatchTap(svc: AccessibilityService, nx: Float, ny: Float) {
        val px = px(nx, screenW)
        val py = px(ny, screenH)
        Log.d(TAG, "tap → ($px, $py)")
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 1L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        svc.dispatchGesture(gesture, null, null)
    }

    private fun dispatchSwipe(
        svc: AccessibilityService,
        nx1: Float, ny1: Float, nx2: Float, ny2: Float, durationMs: Long
    ) {
        val px1 = px(nx1, screenW); val py1 = px(ny1, screenH)
        val px2 = px(nx2, screenW); val py2 = px(ny2, screenH)
        Log.d(TAG, "swipe ($px1,$py1) → ($px2,$py2) ${durationMs}ms")
        val path = Path().apply { moveTo(px1, py1); lineTo(px2, py2) }
        val dur  = durationMs.coerceIn(50L, 3_000L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, dur)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        svc.dispatchGesture(gesture, null, null)
    }

    private fun dispatchKey(svc: AccessibilityService, keycode: Int) {
        Log.d(TAG, "key keycode=$keycode")
        when (keycode) {
            AppConstants.KEYCODE_HOME       -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            AppConstants.KEYCODE_BACK       -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            AppConstants.KEYCODE_APP_SWITCH -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            else -> Log.w(TAG, "dispatchKey: unhandled keycode $keycode")
        }
    }
}
