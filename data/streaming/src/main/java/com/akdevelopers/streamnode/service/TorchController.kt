package com.akdevelopers.streamnode.service

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.akdevelopers.streamnode.analytics.StreamNodeLogger

/**
 * TorchController — toggles the device flashlight via CameraManager.setTorchMode().
 *
 * Works independently of camera streaming:
 *  • When NO camera is open:  CameraManager.setTorchMode() fires directly.
 *  • When CameraOrchestrator holds the camera open: setTorchMode() will throw
 *    CameraAccessException — in that case the torch state is stored and the
 *    active CaptureSession should apply it via FLASH_MODE_TORCH on its request.
 *    (See CameraOrchestrator.applyTorchMode for the in-session path.)
 *
 * No CAMERA permission is required for torch-only mode.
 *
 * Feature 7 — Torch / Flashlight Remote Toggle
 */
object TorchController {

    private const val TAG = "AC_Torch"

    /** Last requested torch state — readable by CameraOrchestrator for in-session use. */
    @Volatile var torchEnabled: Boolean = false
        private set

    /**
     * Toggle the torch ON or OFF.
     *
     * @param context Any context — used to obtain CameraManager.
     * @param enable  true = ON, false = OFF.
     */
    fun setTorch(context: Context, enable: Boolean) {
        torchEnabled = enable
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = findFlashCameraId(cm)
            if (cameraId == null) {
                Log.w(TAG, "setTorch($enable): no flash-capable camera found on this device")
                return
            }
            cm.setTorchMode(cameraId, enable)
            Log.i(TAG, "Torch → ${if (enable) "ON" else "OFF"} (cameraId=$cameraId)")
        } catch (e: CameraAccessException) {
            // Another app (or CameraOrchestrator) holds the camera open.
            // State is persisted in torchEnabled; CameraOrchestrator.applyTorchMode()
            // should be called by the orchestrator when it detects a state change.
            Log.w(TAG, "setTorch($enable): camera busy — state saved for in-session apply. ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "setTorch($enable): invalid cameraId — ${e.message}")
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /** Returns the first camera ID that reports FLASH_INFO_AVAILABLE == true, or null. */
    private fun findFlashCameraId(cm: CameraManager): String? =
        cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
}
