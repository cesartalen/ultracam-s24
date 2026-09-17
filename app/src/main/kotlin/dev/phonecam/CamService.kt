package dev.phonecam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.encoder.CodecErrorCallback
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.encoder.utils.CodecUtil
import com.pedro.rtspserver.RtspServerStream
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface

const val TAG = "phonecam"

val defaults = JSONObject(
    """{"camera":"0","lens":"auto","width":1920,"height":1080,"fps":30,"bitrate":6000,"zoom":1.0,"af":true,"afLock":false,
       "focus":0.0,"manual":false,"iso":100,"shutter":10000000,"ev":0,"awbLock":false,"eis":false,"torch":false}""",
)

fun ip(): String =
    NetworkInterface.getNetworkInterfaces().toList()
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it.isSiteLocalAddress && it.address.size == 4 }
        ?.hostAddress ?: "0.0.0.0"

class CamService : Service() {
    val handler = Handler(Looper.getMainLooper())
    lateinit var state: JSONObject
    var camera: Camera? = null
    var main: RtspServerStream? = null
    var sub: RtspServerStream? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var web: Web
    private lateinit var discovery: Discovery
    private lateinit var wake: PowerManager.WakeLock
    private lateinit var wifi: WifiManager.WifiLock
    private lateinit var multicast: WifiManager.MulticastLock
    private val mdns = mutableListOf<NsdManager.RegistrationListener>()

    @Volatile private var restarting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        instance = this
        prefs = getSharedPreferences("phonecam", MODE_PRIVATE)
        state = JSONObject(defaults.toString())
        prefs.getString("state", null)?.let { saved ->
            val s = JSONObject(saved)
            s.keys().forEach { state.put(it, s.get(it)) }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("stream", "Streaming", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, "stream")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("phonecam")
            .setContentText("rtsp://${ip()}:8554/live")
            .setContentIntent(open)
            .build()
        startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonecam:stream").apply { acquire() }
        val wm = getSystemService(WifiManager::class.java)
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "phonecam").apply { acquire() }
        multicast = wm.createMulticastLock("phonecam").apply { acquire() }
        web = Web(this).apply { start() }
        discovery = Discovery(serial()).apply { start() }
        mdns("_rtsp._tcp", 8554)
        mdns("_http._tcp", 8080)
        start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        stopPipeline()
        web.stop()
        discovery.stop()
        mdns.forEach(getSystemService(NsdManager::class.java)::unregisterService)
        multicast.release()
        wifi.release()
        wake.release()
    }

    fun serial(): String = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID)

    fun status(): JSONObject =
        JSONObject()
            .put("state", state)
            .put("caps", camera?.caps() ?: JSONObject())
            .put("live", camera?.live() ?: JSONObject())
            .put("presets", JSONArray(presets().keys().asSequence().toList()))
            .put("cameras", JSONArray(cameras()))
            .put("rtsp", "rtsp://${ip()}:8554/live")
            .put("clients", main?.getStreamClient()?.getNumClients() ?: 0)

    // one entry per lens: Samsung lists the front sensor twice with different crops
    private fun cameras(): List<String> {
        val cm = getSystemService(CameraManager::class.java)
        return cm.cameraIdList.distinctBy { id ->
            val c = cm.getCameraCharacteristics(id)
            c[CameraCharacteristics.LENS_FACING] to c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.toList()
        }
    }

    fun update(changes: JSONObject) {
        changes.optJSONArray("tap")?.let {
            camera?.tap(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())
            return
        }
        val prev = JSONObject(state.toString())
        changes.keys().forEach { state.put(it, changes.get(it)) }
        val changed = { k: String -> prev.opt(k)?.toString() != state.opt(k)?.toString() }
        if (changed("camera")) state.put("lens", "auto")
        prefs.edit().putString("state", state.toString()).apply()
        when {
            listOf("width", "height", "fps").any(changed) -> restart("format changed")
            changed("camera") -> camera?.reopen(JSONObject(state.toString()))
            else -> camera?.apply(JSONObject(state.toString()))
        }
        if (changed("bitrate")) main?.setVideoBitrateOnFly(state.getInt("bitrate") * 1000)
        web.push()
    }

    fun preset(action: String, name: String) {
        val all = presets()
        when (action) {
            "save" -> all.put(name, JSONObject(state.toString()))
            "delete" -> all.remove(name)
            "load" -> all.optJSONObject(name)?.let(::update)
        }
        prefs.edit().putString("presets", all.toString()).apply()
        web.push()
    }

    private fun presets() = JSONObject(prefs.getString("presets", null) ?: "{}")

    private fun start() {
        try {
            val cam = Camera(getSystemService(CameraManager::class.java), state, ::restart)
            camera = cam
            main = stream(8554, cam.Output(), state.getInt("width"), state.getInt("height"), state.getInt("bitrate"))
            sub = stream(8555, cam.Output(), 640, 360, 800)
            Log.i(TAG, "streaming rtsp://${ip()}:8554/live")
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            restart(e.toString())
        }
    }

    private fun stream(port: Int, source: VideoSource, width: Int, height: Int, kbps: Int): RtspServerStream {
        val s = RtspServerStream(this, port, checker, source, NoAudioSource())
        s.getStreamClient().setOnlyVideo(true)
        s.setEncoderErrorCallback(codecErrors)
        val ok = s.prepareVideo(width, height, kbps * 1000, state.getInt("fps"), iFrameInterval = 1)
        check(ok) { "prepareVideo ${width}x$height failed" }
        // the library refuses to start without a prepared audio encoder, even with NoAudioSource
        s.prepareAudio(32000, false, 64000)
        s.startStream()
        return s
    }

    fun restart(reason: String) {
        if (restarting) return
        restarting = true
        Log.w(TAG, "restart: $reason")
        // 2 s gives the HAL time to release the camera before it is opened again
        handler.postDelayed({
            stopPipeline()
            restarting = false
            start()
        }, 2000)
    }

    private fun stopPipeline() {
        try {
            camera?.destroy()
            main?.release()
            sub?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
        }
        main = null
        sub = null
        camera = null
    }

    private fun mdns(type: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "phonecam"
            serviceType = type
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}

            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                Log.w(TAG, "mdns $type failed: $code")
            }

            override fun onServiceUnregistered(i: NsdServiceInfo) {}

            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        mdns += listener
    }

    private val checker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {}

        override fun onConnectionSuccess() {}

        override fun onConnectionFailed(reason: String) = restart("rtsp: $reason")

        override fun onDisconnect() {}

        override fun onAuthError() {}

        override fun onAuthSuccess() {}
    }

    private val codecErrors = object : CodecErrorCallback {
        override fun onCodecError(type: CodecUtil.CodecTypeError, e: MediaCodec.CodecException) {
            restart("codec $type: ${e.diagnosticInfo}")
        }
    }

    companion object {
        var instance: CamService? = null
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!ctx.getSharedPreferences("phonecam", Context.MODE_PRIVATE).getBoolean("boot", false)) return
        if (Build.VERSION.SDK_INT < 35) {
            ctx.startForegroundService(Intent(ctx, CamService::class.java))
            return
        }
        // Android 15 forbids camera foreground services from BOOT_COMPLETED, so offer a tap instead
        val start = Intent(ctx, MainActivity::class.java).putExtra("start", true)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("boot", "Start after boot", NotificationManager.IMPORTANCE_HIGH))
        val note = Notification.Builder(ctx, "boot")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("phonecam")
            .setContentText("Tap to start streaming")
            .setContentIntent(PendingIntent.getActivity(ctx, 0, start, PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        nm.notify(2, note)
    }
}
