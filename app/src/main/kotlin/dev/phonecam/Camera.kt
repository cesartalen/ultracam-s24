package dev.phonecam

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.graphics.Rect
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

class Camera(private val cm: CameraManager, state: JSONObject, private val onError: (String) -> Unit) {
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val outputs = mutableListOf<Output>()
    private var device: CameraDevice? = null
    private var opening = false
    private var session: CameraCaptureSession? = null
    private var generation = 0
    private var locked = false
    private var pinned: String? = null
    private var region: MeteringRectangle? = null
    private var retrigger = false
    private var chars = cm.getCameraCharacteristics(state.getString("camera"))

    @Volatile private var state = state

    @Volatile private var last: TotalCaptureResult? = null

    @Volatile private var dead = false

    init {
        thread.setUncaughtExceptionHandler { _, e ->
            Log.e(TAG, "camera thread", e)
            fail(e.toString())
        }
    }

    inner class Output : VideoSource() {
        var surface: Surface? = null

        override fun create(width: Int, height: Int, fps: Int, rotation: Int) = true

        override fun start(surfaceTexture: SurfaceTexture) {
            surfaceTexture.setDefaultBufferSize(width, height)
            surface = Surface(surfaceTexture)
            handler.post {
                outputs += this
                if (device == null) open() else configure()
            }
        }

        override fun stop() {
            val s = surface ?: return
            surface = null
            handler.post {
                outputs -= this
                if (outputs.isEmpty()) close() else configure()
                s.release()
            }
        }

        override fun release() {}

        override fun isRunning() = surface != null

        override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.LANDSCAPE)
    }

    fun apply(state: JSONObject) {
        if (state.getBoolean("af") != this.state.getBoolean("af")) region = null
        this.state = state
        handler.post { if (lensOf(state) != pinned) configure() else request() }
    }

    fun tap(nx: Float, ny: Float) {
        val array = chars[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return
        val half = minOf(array.width(), array.height()) / 16
        val cx = array.left + (nx.coerceIn(0f, 1f) * array.width()).toInt()
        val cy = array.top + (ny.coerceIn(0f, 1f) * array.height()).toInt()
        val box = Rect(cx - half, cy - half, cx + half, cy + half)
        box.intersect(array)
        region = MeteringRectangle(box, MeteringRectangle.METERING_WEIGHT_MAX)
        retrigger = true
        handler.post(::request)
    }

    fun reopen(state: JSONObject) {
        this.state = state
        handler.post {
            close()
            chars = cm.getCameraCharacteristics(state.getString("camera"))
            if (outputs.isNotEmpty()) open()
        }
    }

    fun destroy() {
        dead = true
        handler.post {
            close()
            thread.quitSafely()
        }
    }

    private fun fail(reason: String) {
        if (!dead) onError(reason)
    }

    fun live(): JSONObject {
        val r = last ?: return JSONObject()
        return JSONObject()
            .put("iso", r[CaptureResult.SENSOR_SENSITIVITY])
            .put("shutter", r[CaptureResult.SENSOR_EXPOSURE_TIME])
            .put("focus", r[CaptureResult.LENS_FOCUS_DISTANCE])
            .put("afState", r[CaptureResult.CONTROL_AF_STATE])
            .put("zoom", if (Build.VERSION.SDK_INT >= 30) r[CaptureResult.CONTROL_ZOOM_RATIO] else null)
            .put("lens", r[CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID])
            .put("eis", r[CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE])
    }

    fun caps(): JSONObject {
        val afModes = chars[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        return JSONObject()
            .put("zoom", json(zoomRange()))
            .put("iso", json(chars[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]))
            .put("shutter", json(chars[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]))
            .put("ev", json(chars[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]))
            .put("evStep", chars[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toDouble())
            .put("focus", if (CameraMetadata.CONTROL_AF_MODE_OFF in afModes) focusMax() else null)
            .put("torch", chars[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true)
            .put("eis", eisMode() != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            .put("awbLock", chars[CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE] == true)
            .put("tap", (chars[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0)
            .put("lenses", lenses())
    }

    private fun lenses(): JSONObject {
        val out = JSONObject()
        for (id in chars.physicalCameraIds) {
            val focal = cm.getCameraCharacteristics(id)[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull()
            out.put(id, focal)
        }
        return out
    }

    private fun lensOf(st: JSONObject): String? = st.optString("lens").takeIf { it in chars.physicalCameraIds }

    private fun json(r: Range<*>?) = r?.let { JSONArray(listOf(it.lower, it.upper)) }

    private fun zoomRange() = if (Build.VERSION.SDK_INT >= 30) chars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] else null

    // Samsung omits the key; 10 diopters (10 cm) is a safe upper bound for manual focus
    private fun focusMax() = chars[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]?.takeIf { it > 0 } ?: 10f

    private fun eisMode(): Int {
        val modes = chars[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES] ?: intArrayOf()
        return when {
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in modes ->
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in modes -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
    }

    private fun fpsRange(fps: Int) =
        chars[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.filter { it.upper == fps }?.maxByOrNull { it.lower }

    private fun open() {
        if (opening) return
        opening = true
        cm.openCamera(
            state.getString("camera"),
            object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    opening = false
                    device = cam
                    configure()
                }

                override fun onDisconnected(cam: CameraDevice) {
                    cam.close()
                    if (device == cam) fail("camera disconnected")
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    opening = false
                    cam.close()
                    fail("camera error $error")
                }
            },
            handler,
        )
    }

    private fun close() {
        session?.close()
        session = null
        device?.close()
        device = null
        last = null
    }

    private fun configure() {
        val cam = device ?: return
        if (dead) return
        val surfaces = outputs.mapNotNull { it.surface }
        val gen = ++generation
        session = null
        locked = false
        region = null
        pinned = lensOf(state)
        if (surfaces.isEmpty()) return
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            surfaces.map { s -> OutputConfiguration(s).apply { pinned?.let(::setPhysicalCameraId) } },
            Executor { handler.post(it) },
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation) return
                    session = s
                    request()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen == generation) fail("session configuration failed")
                }
            },
        )
        cam.createCaptureSession(config)
    }

    private fun request() {
        val s = session ?: return
        if (dead) return
        val st = state
        val req = s.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        outputs.mapNotNull { it.surface }.forEach(req::addTarget)
        fpsRange(st.getInt("fps"))?.let { req[CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] = it }
        zoomRange()?.let { req[CaptureRequest.CONTROL_ZOOM_RATIO] = it.clamp(st.getDouble("zoom").toFloat()) }
        when {
            !st.getBoolean("af") -> {
                req[CaptureRequest.CONTROL_AF_MODE] = CameraMetadata.CONTROL_AF_MODE_OFF
                req[CaptureRequest.LENS_FOCUS_DISTANCE] = st.getDouble("focus").toFloat().coerceIn(0f, focusMax())
            }
            // a tapped region needs AUTO so the scan honours it; continuous only biases
            region != null -> req[CaptureRequest.CONTROL_AF_MODE] = CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> req[CaptureRequest.CONTROL_AF_MODE] = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        }
        val iso = chars[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
        val shutter = chars[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
        if (st.getBoolean("manual") && iso != null && shutter != null) {
            req[CaptureRequest.CONTROL_AE_MODE] = CameraMetadata.CONTROL_AE_MODE_OFF
            req[CaptureRequest.SENSOR_SENSITIVITY] = iso.clamp(st.getInt("iso"))
            req[CaptureRequest.SENSOR_EXPOSURE_TIME] = shutter.clamp(st.getLong("shutter"))
        } else {
            chars[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]?.let {
                req[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION] = it.clamp(st.getInt("ev"))
            }
        }
        region?.let {
            val r = arrayOf(it)
            if ((chars[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0) req[CaptureRequest.CONTROL_AF_REGIONS] = r
            if ((chars[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0) > 0) req[CaptureRequest.CONTROL_AE_REGIONS] = r
        }
        req[CaptureRequest.CONTROL_AWB_LOCK] = st.getBoolean("awbLock")
        req[CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE] =
            if (st.getBoolean("eis")) eisMode() else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        if (chars[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true) {
            req[CaptureRequest.FLASH_MODE] = if (st.getBoolean("torch")) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF
        }
        s.setRepeatingRequest(req.build(), callback, handler)
        // in a continuous AF mode a single trigger freezes the lens until a cancel
        val lock = st.getBoolean("af") && st.getBoolean("afLock")
        if (lock != locked) {
            req[CaptureRequest.CONTROL_AF_TRIGGER] =
                if (lock) CameraMetadata.CONTROL_AF_TRIGGER_START else CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            s.capture(req.build(), null, handler)
            locked = lock
        } else if (retrigger && st.getBoolean("af") && !lock) {
            req[CaptureRequest.CONTROL_AF_TRIGGER] = CameraMetadata.CONTROL_AF_TRIGGER_START
            s.capture(req.build(), null, handler)
        }
        retrigger = false
    }

    private val callback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            last = result
        }
    }
}
