package dev.phonecam

import android.graphics.Bitmap
import com.pedro.rtspserver.RtspServerStream
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume

class Web(private val svc: CamService) {
    private val sessions = CopyOnWriteArraySet<DefaultWebSocketServerSession>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val photo = Mutex()
    private val server = embeddedServer(CIO, port = 8080) {
        install(WebSockets)
        routing {
            get("/") { call.respondBytes(svc.assets.open("panel.html").readBytes(), ContentType.Text.Html) }
            get("/snapshot.jpg") { call.respondBytes(jpeg(svc.main, 85), ContentType.Image.JPEG) }
            get("/preview.jpg") { call.respondBytes(jpeg(svc.sub, 60), ContentType.Image.JPEG) }
            webSocket("/ws") {
                sessions += this
                svc.handler.post { push() }
                try {
                    for (frame in incoming) {
                        val msg = JSONObject((frame as? Frame.Text ?: continue).readText())
                        svc.handler.post {
                            if (msg.has("preset")) svc.preset(msg.getString("preset"), msg.getString("name")) else svc.update(msg)
                        }
                    }
                } finally {
                    sessions -= this
                }
            }
            onvif(svc)
        }
    }

    fun start() {
        server.start()
        scope.launch {
            while (true) {
                delay(1000)
                svc.handler.post { push() }
            }
        }
    }

    fun stop() {
        scope.cancel()
        server.stop(500, 1000)
    }

    fun push() {
        val text = svc.status().toString()
        scope.launch {
            for (s in sessions) {
                try {
                    s.send(text)
                } catch (e: Exception) {
                    sessions -= s
                }
            }
        }
    }

    private suspend fun jpeg(stream: RtspServerStream?, quality: Int): ByteArray =
        photo.withLock {
            val gl = checkNotNull(stream) { "not streaming" }.getGlInterface()
            val bmp = withTimeout(3000) { suspendCancellableCoroutine { c -> gl.takePhoto { c.resume(it) } } }
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        }
}
