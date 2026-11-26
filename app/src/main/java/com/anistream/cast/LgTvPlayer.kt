package com.anistream.cast

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * An LG webOS TV, driven the way LG's own phone app does it (the "SSAP" WebSocket on port 3001, or 3000 on
 * older TVs). The first time, the TV asks on screen whether to allow Ani; the key it hands back is saved,
 * so it doesn't ask again. The TV's own media player opens the episode (HLS) through the phone's proxy.
 *
 * LG's player doesn't report its position, so the phone keeps time itself; seeking reopens the video at
 * the new point. If the TV won't play it this way, [useDlna] switches to the TV's DLNA player instead.
 */
class LgTvPlayer private constructor(
    private val tv: LanTv,
    private val socket: WebSocket,
    private val pending: ConcurrentHashMap<String, CompletableDeferred<JSONObject>>,
) : RemotePlayer {
    override val name: String get() = tv.name

    /** Set once we've switched to DLNA: everything goes there from then on. */
    private var dlna: DlnaPlayer? = null
    val canUseDlna get() = tv.dlna != null && dlna == null
    val usingDlna get() = dlna != null

    private val ids = AtomicInteger()
    private val clock = PlaybackClock()
    private var item: CastItem? = null
    private var context: Context? = null
    private var durationMs = 0L
    private var sessionId: String? = null

    override suspend fun load(context: Context, item: CastItem): String? {
        dlna?.let { return it.load(context, item) }
        this.context = context.applicationContext
        this.item = item
        return withContext(Dispatchers.IO) {
            val ip = LanNetwork.wifiAddress(context) ?: return@withContext "Connect to the same Wi-Fi as the TV"
            val master = item.info.masterUrl.ifBlank { item.info.videoUrl }
            StreamProxy.start(item.info.referer)
            durationMs = StreamProxy.durationMs(master)
            open(ip, master, item.startMs)
        }
    }

    private suspend fun open(ip: String, master: String, startMs: Long): String? {
        val startSec = (startMs / 1000).coerceAtLeast(0)
        val it = item
        val payload = JSONObject()
            .put("target", StreamProxy.url(ip, master, startSec))
            .put("title", it?.title ?: "Ani")
            .put("description", it?.subtitle.orEmpty())
            .put("mimeType", "application/x-mpegURL")
            .put("loop", false)
            .apply { it?.posterUrl?.let { p -> put("iconSrc", p) } }
        val reply = request("ssap://media.viewer/open", payload)
        val error = errorOf(reply)
        if (error != null) {
            // The TV's own player said no: fall back to DLNA when the TV has it.
            val ctx = context
            if (tv.dlna != null && ctx != null && it != null) {
                Log.i(TAG, "media.viewer/open failed ($error), switching to DLNA")
                return useDlna(ctx, startMs)
            }
            return "The TV wouldn't open the video ($error)"
        }
        sessionId = reply?.optJSONObject("payload")?.optString("sessionId")?.takeIf { s -> s.isNotEmpty() }
        clock.start(startSec * 1000)
        return null
    }

    /** Play through the TV's DLNA player from now on (for when LG's player shows nothing). */
    suspend fun useDlna(context: Context, atMs: Long): String? {
        val info = tv.dlna ?: return "This TV has no DLNA player"
        val current = item ?: return null
        closeViewer()
        val player = DlnaPlayer(tv.name, info)
        dlna = player
        return player.load(context, current.copy(startMs = atMs))
    }

    override suspend fun status(): RemoteStatus {
        dlna?.let { return it.status() }
        val pos = clock.position().coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
        return RemoteStatus(
            playing = clock.running,
            positionMs = pos,
            durationMs = durationMs,
            finished = durationMs > 0 && clock.running && clock.position() >= durationMs + 2_000,
        )
    }

    override suspend fun setPlaying(play: Boolean) {
        dlna?.let { return it.setPlaying(play) }
        withContext(Dispatchers.IO) {
            request(if (play) "ssap://media.controls/play" else "ssap://media.controls/pause", JSONObject())
        }
        if (play) clock.resume() else clock.pause()
    }

    override suspend fun seek(positionMs: Long) {
        dlna?.let { return it.seek(positionMs) }
        val ctx = context ?: return
        val it = item ?: return
        withContext(Dispatchers.IO) {
            val ip = LanNetwork.wifiAddress(ctx) ?: return@withContext
            val target = positionMs.coerceIn(0, (durationMs - 5_000).coerceAtLeast(0))
            open(ip, it.info.masterUrl.ifBlank { it.info.videoUrl }, target)
        }
    }

    override suspend fun release() {
        dlna?.release()
        withContext(Dispatchers.IO) { closeViewer() }
        socket.close(1000, null)
    }

    private suspend fun closeViewer() {
        val id = sessionId ?: return
        sessionId = null
        request("ssap://media.viewer/close", JSONObject().put("sessionId", id))
    }

    private suspend fun request(uri: String, payload: JSONObject): JSONObject? {
        val id = "ani_${ids.incrementAndGet()}"
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        val msg = JSONObject().put("type", "request").put("id", id).put("uri", uri).put("payload", payload)
        if (!socket.send(msg.toString())) {
            pending.remove(id)
            return JSONObject().put("type", "error").put("error", "Lost the TV")
        }
        return withTimeoutOrNull(10_000) { reply.await() }.also { pending.remove(id) }
            ?: JSONObject().put("type", "error").put("error", "The TV didn't answer")
    }

    private fun errorOf(reply: JSONObject?): String? = when {
        reply == null -> "no answer"
        reply.optString("type") == "error" -> reply.optString("error").ifBlank { "error" }
        reply.optJSONObject("payload")?.optBoolean("returnValue", true) == false ->
            reply.optJSONObject("payload")?.optString("errorText")?.ifBlank { null } ?: "refused"
        else -> null
    }

    companion object {
        private const val TAG = "AniLg"
        private const val PREFS = "ani_cast"

        /**
         * Connects and pairs. [onPrompt] runs when the TV is showing its "allow this device?" question.
         * Returns the player, or an error message.
         */
        suspend fun connect(context: Context, tv: LanTv, onPrompt: () -> Unit): Result<LgTvPlayer> = withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val keyName = "lg_key_" + tv.name
            var lastError = "couldn't reach the TV"
            // Newer LG firmware only answers the secure port; older TVs only the plain one.
            for (url in listOf("wss://${tv.ip}:3001", "ws://${tv.ip}:3000")) {
                val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
                val opened = CompletableDeferred<Boolean>()
                val registered = CompletableDeferred<String>()
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(true) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                        val id = msg.optString("id")
                        if (id == "register_0") {
                            when (msg.optString("type")) {
                                "response" -> if (msg.optJSONObject("payload")?.optString("pairingType") == "PROMPT") onPrompt()
                                "registered" -> registered.complete(msg.optJSONObject("payload")?.optString("client-key").orEmpty())
                                "error" -> registered.completeExceptionally(IllegalStateException(msg.optString("error").ifBlank { "refused" }))
                            }
                        } else {
                            pending[id]?.complete(msg)
                        }
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        opened.complete(false)
                        registered.completeExceptionally(t)
                        pending.values.forEach { it.complete(JSONObject().put("type", "error").put("error", "Lost the TV")) }
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        registered.completeExceptionally(IllegalStateException("closed"))
                    }
                }
                val socket = client(tv.ip).newWebSocket(Request.Builder().url(url).build(), listener)
                if (withTimeoutOrNull(5_000) { opened.await() } != true) {
                    socket.cancel()
                    continue
                }
                val register = JSONObject()
                    .put("type", "register")
                    .put("id", "register_0")
                    .put("payload", registrationPayload(prefs.getString(keyName, null)))
                socket.send(register.toString())
                // Up to a minute to find the remote and press Allow.
                val key = try {
                    withTimeoutOrNull(60_000) { registered.await() }
                } catch (e: Exception) {
                    lastError = e.message ?: "refused"
                    null
                }
                if (key == null) {
                    // The TV answered but didn't pair (Deny pressed, or no one pressed anything): don't ask twice.
                    socket.cancel()
                    if (lastError == "couldn't reach the TV") lastError = "no answer to the pairing request"
                    return@withContext Result.failure(IllegalStateException(lastError))
                }
                if (key.isNotEmpty()) prefs.edit().putString(keyName, key).apply()
                return@withContext Result.success(LgTvPlayer(tv, socket, pending))
            }
            Result.failure(IllegalStateException(lastError))
        }

        /** LG TVs use a self-signed certificate; trust it for this TV's address only. */
        @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
        private fun client(ip: String): OkHttpClient {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { host, _ -> host == ip }
                .build()
        }

        /**
         * The pairing request LG's own remote app sends (same manifest and signature as Home Assistant's
         * aiowebostv, Apache-2.0). With a saved client key the TV connects without asking again.
         */
        private fun registrationPayload(clientKey: String?): JSONObject {
            val signature = "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbm" +
                "ctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR" +
                "+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRy" +
                "aMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4" +
                "RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n" +
                "50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM" +
                "2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQoj" +
                "oa7NQnAtw=="
            val permissions = listOf(
                "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED", "CONTROL_AUDIO",
                "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING",
                "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_TV", "CONTROL_POWER", "CONTROL_TV_SCREEN",
                "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
                "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE",
                "READ_COUNTRY_INFO", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
                "READ_SETTINGS",
            )
            val signedPermissions = listOf(
                "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS",
                "READ_LGE_SDX", "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT",
                "CONTROL_POWER", "READ_CURRENT_CHANNEL", "READ_RUNNING_APPS", "READ_UPDATE_INFO",
                "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME",
            )
            val manifest = JSONObject()
                .put("appVersion", "1.1")
                .put("manifestVersion", 1)
                .put("permissions", JSONArray(permissions))
                .put("signatures", JSONArray().put(JSONObject().put("signature", signature).put("signatureVersion", 1)))
                .put(
                    "signed", JSONObject()
                        .put("appId", "com.lge.test")
                        .put("created", "20140509")
                        .put("localizedAppNames", JSONObject().put("", "LG Remote App").put("ko-KR", "리모컨 앱").put("zxx-XX", "ЛГ Rэмotэ AПП"))
                        .put("localizedVendorNames", JSONObject().put("", "LG Electronics"))
                        .put("permissions", JSONArray(signedPermissions))
                        .put("serial", "2f930e2d2cfe083771f68e4fe7bb07")
                        .put("vendorId", "com.lge"),
                )
            return JSONObject()
                .put("forcePairing", false)
                .put("pairingType", "PROMPT")
                .put("manifest", manifest)
                .apply { if (!clientKey.isNullOrEmpty()) put("client-key", clientKey) }
        }
    }
}
