package com.akdevelopers.streamnode.service

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.akdevelopers.streamnode.audio.CameraCaptureEngine
import com.akdevelopers.streamnode.audio.CameraWebSocketClient

/**
 * CameraOrchestrator — owns up to two simultaneous camera capture sessions (front + back).
 *
 * Each [CameraFacing] has its own [CameraCaptureEngine] + [CameraWebSocketClient] pair.
 * Errors trigger exponential-backoff restart, matching [MicOrchestrator.scheduleRestart].
 *
 * Call [startCamera] once per facing, [stopCamera] to tear down one, [stopAll] to stop both.
 */
class CameraOrchestrator(private val context: Context) {

    companion object { private const val TAG = "AC_CameraOrch" }

    private val handler = Handler(Looper.getMainLooper())

    private data class CameraSession(
        var engine:   CameraCaptureEngine?,
        var wsClient: CameraWebSocketClient?,
        var restartAttempts: Int = 0,
        @Volatile var released: Boolean = false
    )

    private val sessions = mutableMapOf<CameraFacing, CameraSession>()

    val isFrontActive: Boolean get() = sessions[CameraFacing.FRONT]?.engine != null
    val isBackActive:  Boolean get() = sessions[CameraFacing.BACK]?.engine  != null

    fun startCamera(facing: CameraFacing, serverUrl: String) {
        if (sessions[facing]?.engine != null) {
            Log.w(TAG, "startCamera[$facing]: already active")
            return
        }
        Log.i(TAG, "startCamera[$facing]")
        val session = CameraSession(engine = null, wsClient = null)
        sessions[facing] = session
        spawnSession(facing, session, serverUrl)
    }

    fun stopCamera(facing: CameraFacing) {
        val session = sessions.remove(facing) ?: return
        Log.i(TAG, "stopCamera[$facing]")
        tearDownSession(session)
    }

    fun stopAll() {
        sessions.keys.toList().forEach { stopCamera(it) }
    }

    private fun spawnSession(facing: CameraFacing, session: CameraSession, serverUrl: String) {
        val wsClient = CameraWebSocketClient(facing).also {
            it.connect(serverUrl, context)
            session.wsClient = it
        }
        val engine = CameraCaptureEngine(
            context   = context,
            facing    = facing,
            onNalUnit = { nal -> wsClient.sendFrame(nal) },
            onError   = { msg ->
                Log.e(TAG, "CameraCaptureEngine[$facing] error: $msg")
                scheduleRestart(facing, session, serverUrl, msg)
            }
        ).also {
            it.start()
            session.engine = it
            session.restartAttempts = 0
        }
        Log.i(TAG, "spawnSession[$facing] engine=$engine")
    }

    private fun scheduleRestart(
        facing:    CameraFacing,
        session:   CameraSession,
        serverUrl: String,
        reason:    String
    ) {
        session.engine?.stop(); session.engine = null
        session.wsClient?.disconnect(); session.wsClient = null
        if (session.released) return
        val delayMs = minOf(3_000L shl session.restartAttempts, 30_000L)
        session.restartAttempts++
        Log.i(TAG, "scheduleRestart[$facing] attempt=${session.restartAttempts} delay=${delayMs}ms reason=$reason")
        handler.postDelayed({
            if (!session.released && sessions[facing] === session) {
                spawnSession(facing, session, serverUrl)
            }
        }, delayMs)
    }

    private fun tearDownSession(session: CameraSession) {
        session.released = true
        handler.removeCallbacksAndMessages(null)
        session.engine?.stop();       session.engine   = null
        session.wsClient?.disconnect(); session.wsClient = null
    }

    /**
     * Feature 6 — Snapshot capture.
     *
     * Opens the first available (front or back) active camera via Camera2 API,
     * fires a single JPEG still-capture, and returns the raw bytes.
     * Returns null if no camera hardware is available or an error occurs.
     *
     * Called from [StreamingService] → [CommandProcessor.onSnapshot].
     * The result is forwarded to [com.akdevelopers.streamnode.audio.AudioControlClient.sendBinary].
     */
    fun captureSnapshot(): ByteArray? {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Pick the first available camera id (prefer front, fall back to back, then any)
        val cameraId = runCatching {
            cameraManager.cameraIdList.firstOrNull()
        }.getOrNull() ?: run {
            Log.w(TAG, "captureSnapshot: no camera available"); return null
        }

        var result: ByteArray? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        runCatching {
            val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 1)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    val surfaces = listOf(imageReader.surface)
                    camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(imageReader.surface)
                            }
                            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(
                                    session: CameraCaptureSession,
                                    request: android.hardware.camera2.CaptureRequest,
                                    result2: android.hardware.camera2.TotalCaptureResult
                                ) {
                                    val image = imageReader.acquireLatestImage()
                                    if (image != null) {
                                        val buffer = image.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        result = bytes
                                        image.close()
                                    }
                                    imageReader.close()
                                    camera.close()
                                    latch.countDown()
                                }
                                override fun onCaptureFailed(
                                    session: CameraCaptureSession,
                                    request: android.hardware.camera2.CaptureRequest,
                                    failure: android.hardware.camera2.CaptureFailure
                                ) {
                                    Log.e(TAG, "captureSnapshot: captureFailed reason=${failure.reason}")
                                    imageReader.close(); camera.close(); latch.countDown()
                                }
                            }, null)
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "captureSnapshot: session configure failed")
                            imageReader.close(); camera.close(); latch.countDown()
                        }
                    }, null)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); latch.countDown() }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "captureSnapshot: camera error $error")
                    camera.close(); latch.countDown()
                }
            }, null)

            // Wait up to 4 seconds for the capture to complete
            latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure { e ->
            Log.e(TAG, "captureSnapshot exception: ${e.message}")
            latch.countDown()
        }

        return result
    }
}
