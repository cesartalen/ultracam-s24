package dev.phonecam

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
import android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
import android.hardware.camera2.CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP
import android.hardware.camera2.CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE
import android.hardware.camera2.CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES
import android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
import android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE
import android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
import android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
import android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL
import android.hardware.camera2.CameraCharacteristics.LENS_FACING
import android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
import android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION
import android.hardware.camera2.CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION
import android.hardware.camera2.CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE
import android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
import android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
import android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
import android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
import android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
import android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Size

fun probe(cm: CameraManager): String = buildString {
    val ids = cm.cameraIdList
    row("device", "${Build.MANUFACTURER} ${Build.MODEL}, android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
    row("cameraIdList", ids.toList())
    if (Build.VERSION.SDK_INT >= 30) row("concurrentCameraIds", cm.concurrentCameraIds)
    val physical = ids.flatMap { cm.getCameraCharacteristics(it).physicalCameraIds }.distinct() - ids.toSet()
    for (id in ids + physical) {
        appendLine()
        appendLine(if (id in physical) "physical camera $id" else "camera $id")
        try {
            dump(cm.getCameraCharacteristics(id))
        } catch (e: Exception) {
            row("error", e)
        }
    }
}

private fun StringBuilder.dump(c: CameraCharacteristics) {
    val map = c[SCALER_STREAM_CONFIGURATION_MAP]
    val sizes = map?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
    val fhd = Size(1920, 1080)
    row("facing", name("LENS_FACING_", c[LENS_FACING]))
    row("hardwareLevel", name("INFO_SUPPORTED_HARDWARE_LEVEL_", c[INFO_SUPPORTED_HARDWARE_LEVEL]))
    row("capabilities", names("REQUEST_AVAILABLE_CAPABILITIES_", c[REQUEST_AVAILABLE_CAPABILITIES]))
    row("physicalCameraIds", c.physicalCameraIds)
    row("focalLengths", c[LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.toList())
    row("fpsRanges", c[CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]?.toList())
    row("videoStabilization", names("CONTROL_VIDEO_STABILIZATION_MODE_", c[CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]))
    row("opticalStabilization", names("LENS_OPTICAL_STABILIZATION_MODE_", c[LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION]))
    if (Build.VERSION.SDK_INT >= 30) row("zoomRatioRange", c[CONTROL_ZOOM_RATIO_RANGE])
    row("maxDigitalZoom", c[SCALER_AVAILABLE_MAX_DIGITAL_ZOOM])
    row("isoRange", c[SENSOR_INFO_SENSITIVITY_RANGE])
    row("exposureTimeNs", c[SENSOR_INFO_EXPOSURE_TIME_RANGE])
    row("aeCompensation", "${c[CONTROL_AE_COMPENSATION_RANGE]} step ${c[CONTROL_AE_COMPENSATION_STEP]}")
    row("aeLock", c[CONTROL_AE_LOCK_AVAILABLE])
    row("awbLock", c[CONTROL_AWB_LOCK_AVAILABLE])
    row("afModes", names("CONTROL_AF_MODE_", c[CONTROL_AF_AVAILABLE_MODES]))
    row("minFocusDistance", c[LENS_INFO_MINIMUM_FOCUS_DISTANCE])
    row("hyperfocalDistance", c[LENS_INFO_HYPERFOCAL_DISTANCE])
    row("focusCalibration", name("LENS_INFO_FOCUS_DISTANCE_CALIBRATION_", c[LENS_INFO_FOCUS_DISTANCE_CALIBRATION]))
    row("flash", c[FLASH_INFO_AVAILABLE])
    if (fhd in sizes) {
        val ns = map?.getOutputMinFrameDuration(SurfaceTexture::class.java, fhd)
        row("1080pMaxFps", ns?.takeIf { it > 0 }?.let { (1e9 / it).toInt() })
    }
    row("highSpeedSizes", map?.highSpeedVideoSizes?.toList())
    row("surfaceSizes", sizes.joinToString(" "))
}

private fun StringBuilder.row(key: String, value: Any?) = appendLine("  $key: $value")

private val constants = CameraMetadata::class.java.fields.filter { it.type == Int::class.javaPrimitiveType }

private fun name(prefix: String, value: Int?) =
    value?.let { v ->
        val field = constants.firstOrNull { it.name.startsWith(prefix) && it.getInt(null) == v }
        field?.name?.removePrefix(prefix)?.lowercase() ?: v.toString()
    }

private fun names(prefix: String, values: IntArray?) = values?.map { name(prefix, it) }
