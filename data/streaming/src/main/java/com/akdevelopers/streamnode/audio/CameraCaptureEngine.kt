package com.akdevelopers.streamnode.audio

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.akdevelopers.streamnode.service.CameraFacing

/**
 * CameraCaptureEngine — encodes a front or back camera feed to H.264 Baseline NAL units.
 *
 * Usage:
 *   val engine = CameraCaptureEngine(context, CameraFacing.FRONT) { nalUnit ->
 *       cameraWsClient.sendFrame(nalUnit)
 *   }
 *   engine.start()
 *   // ...
 *   engine.stop()
 *
 * Design:
 *  - Camera2 API: opens the requested lens, creates a CaptureSession targeting the
 *    MediaCodec input surface directly — no intermediate ImageReader or VirtualDisplay.
 *  - H.264 Baseline 3.1 — same codec profile as ScreenCaptureEngine for browser compat.
 *  - SPS/PPS prepend pattern: saves BUFFER_FLAG_CODEC_CONFIG bytes and prepends to
 *    every IDR keyframe so the browser VideoDecoder can initialise at any point.
 *  - Sensor orientation is baked into the stream via MediaFormat.KEY_ROTATION.
 */
class CameraCaptureEngine(
    private val context:   Context,
    private val facing:    CameraFacing,
    private val onNalUnit: (ByteArray) -> Unit,
    private val onError:   (String) -> Unit = {}
) {
    private val TAG = "AC_CameraCapture[${facing.name}]"

    companion object {
        private const val MIME        = "video/avc"
        private const val BITRATE     = 1_500_000   // 1.5 Mbps — cameras produce richer frames
        private const val FRAME_RATE  = 30
        private const val I_FRAME_SEC = 2
        private const val PROFILE     = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        private const val LEVEL       = MediaCodecInfo.CodecProfileLevel.AVCLevel31
        private val PREFERRED_SIZE    = Size(1280, 720)
    }

    private var codec:          MediaCodec?                              = null
    private var cameraDevice:   CameraDevice?                           = null
    private var captureSession: android.hardware.camera2.CameraCaptureSession? = null
    private var inputSurface:   Surface?                                = null
    private var cameraThread:   HandlerThread?                          = null
    private var cameraHandler:  Handler?                                = null

    @Volatile private var running          = false
    @Volatile private var codecConfigBytes: ByteArray? = null

    fun start() {
        if (running) { Log.w(TAG, "start: already running"); return }
        Log.i(TAG, "start: facing=$facing")

        cameraThread = HandlerThread("AC_Camera_${facing.name}").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId      = selectCameraId(cameraManager) ?: run {
                onError("No camera found for facing=$facing"); return
            }
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val size = chooseBestSize(cameraManager, cameraId)

            Log.i(TAG, "opening cameraId=$cameraId size=${size.width}x${size.height} rotation=$sensorOrientation")

            val format = MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,          BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE,        FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,  I_FRAME_SEC)
                setInteger(MediaFormat.KEY_PROFILE,           PROFILE)
                setInteger(MediaFormat.KEY_LEVEL,             LEVEL)
                setInteger(MediaFormat.KEY_ROTATION,          sensorOrientation)
            }

            codec = MediaCodec.createEncoderByType(MIME).also { c ->
                c.setCallback(CodecCallback(), cameraHandler)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close(); handleError("Camera disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error $error")
                    camera.close(); handleError("Camera error $error")
                }
            }, cameraHandler)

        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
            onError("CameraCaptureEngine start failed: ${e.message}")
            release()
        }
    }

    private fun startCaptureSession(camera: CameraDevice) {
        val surface = inputSurface ?: return
        try {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(surface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                        captureSession = session
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }.build()
                        session.setRepeatingRequest(request, null, cameraHandler)
                        running = true
                        Log.i(TAG, "capture session running ✓")
                    }
                    override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                        handleError("CaptureSession configuration failed")
                    }
                },
                cameraHandler
            )
        } catch (e: Exception) {
            handleError("createCaptureSession failed: ${e.message}")
        }
    }

    fun stop() {
        if (!running) return
        Log.i(TAG, "stop")
        running = false
        release()
    }

    private fun handleError(msg: String) {
        if (!running) return
        running = false
        onError(msg)
        release()
    }

    private fun release() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close() }         catch (_: Exception) {}
        captureSession = null

        try { cameraDevice?.close() }           catch (_: Exception) {}
        cameraDevice = null

        try { codec?.stop() }                   catch (_: Exception) {}
        try { codec?.release() }                catch (_: Exception) {}
        codec = null

        try { inputSurface?.release() }         catch (_: Exception) {}
        inputSurface = null

        codecConfigBytes = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        Log.d(TAG, "release complete")
    }

    // ── Codec callback ────────────────────────────────────────────────────────

    private inner class CodecCallback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { /* surface mode */ }

        override fun onOutputBufferAvailable(
            codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo
        ) {
            if (!running) { codec.releaseOutputBuffer(index, false); return }
            val buf = codec.getOutputBuffer(index) ?: run {
                codec.releaseOutputBuffer(index, false); return
            }
            buf.position(info.offset); buf.limit(info.offset + info.size)
            val bytes = ByteArray(info.size).also { buf.get(it) }
            codec.releaseOutputBuffer(index, false)

            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                codecConfigBytes = bytes; return
            }

            val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val payload = if (isKey) (codecConfigBytes?.let { it + bytes } ?: bytes) else bytes
            try { onNalUnit(payload) } catch (e: Exception) {
                Log.e(TAG, "onNalUnit error: ${e.message}")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "MediaCodec error: ${e.diagnosticInfo}")
            handleError("MediaCodec error: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Output format changed: $format")
        }
    }

    // ── Camera selection helpers ──────────────────────────────────────────────

    private fun selectCameraId(manager: CameraManager): String? {
        val targetFacing = when (facing) {
            CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            CameraFacing.BACK  -> CameraCharacteristics.LENS_FACING_BACK
        }
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == targetFacing
        }
    }

    private fun chooseBestSize(manager: CameraManager, cameraId: String): Size {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return PREFERRED_SIZE
        val outputSizes = map.getOutputSizes(MediaCodec::class.java) ?: return PREFERRED_SIZE
        // Prefer 1280x720; fall back to next-closest supported resolution
        return outputSizes.firstOrNull { it == PREFERRED_SIZE }
            ?: outputSizes.filter { it.width <= 1280 && it.height <= 720 }
                .maxByOrNull { it.width * it.height }
            ?: outputSizes.minByOrNull { it.width * it.height }
            ?: PREFERRED_SIZE
    }
}
