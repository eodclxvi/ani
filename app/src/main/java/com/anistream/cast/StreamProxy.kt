package com.anistream.cast

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A tiny web server on the phone that the Chromecast streams through.
 *
 * The video site only answers playlist requests that carry its Referer header, and a Chromecast can't add
 * one. So the TV asks the phone (http://<phone-wifi-ip>:<port>/p?u=<real url>), the phone fetches the real
 * URL with the right headers and passes it on. Playlists are rewritten so every link inside them also goes
 * through the phone. Only hosts that came from the stream we're casting are allowed, so it can't be used
 * as an open proxy by anything else on the Wi-Fi.
 */
object StreamProxy {
    private const val TAG = "AniCastProxy"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val pool = Executors.newCachedThreadPool()
    private val allowedHosts: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var referer = ""
    private var server: ServerSocket? = null

    /** Starts the server (once) and returns its port. */
    @Synchronized
    fun start(referer: String): Int {
        this.referer = referer
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0)
        server = socket
        thread(name = "ani-cast-proxy", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: IOException) { break }
                pool.execute { serve(client) }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: IOException) {}
        server = null
        allowedHosts.clear()
    }

    /** The address the Chromecast should use for [upstream]. */
    fun url(phoneIp: String, upstream: String): String {
        allow(upstream)
        val port = server?.localPort ?: error("Cast proxy not started")
        return "http://$phoneIp:$port/p?u=" + URLEncoder.encode(upstream, "UTF-8")
    }

    private fun allow(url: String) {
        runCatching { URI(url).host }.getOrNull()?.let { allowedHosts += it.lowercase() }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream())
                val requestLine = readLine(input) ?: return
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val target = parts.getOrNull(1) ?: return

                if (method == "OPTIONS") {
                    writeHead(out, 204, "No Content", emptyMap(), 0)
                    out.flush()
                    return
                }
                val upstream = if (target.startsWith("/p?u=")) URLDecoder.decode(target.removePrefix("/p?u="), "UTF-8") else ""
                val host = runCatching { URI(upstream).host?.lowercase() }.getOrNull()
                val okScheme = upstream.startsWith("https://") || upstream.startsWith("http://")
                if (!okScheme || host == null || host !in allowedHosts) {
                    writeHead(out, 403, "Forbidden", emptyMap(), 0)
                    out.flush()
                    return
                }

                val request = Request.Builder()
                    .url(upstream)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", referer)
                    .apply { headers["range"]?.let { header("Range", it) } }
                    .build()
                client.newCall(request).execute().use { resp ->
                    val body = resp.body ?: return
                    val type = resp.header("Content-Type").orEmpty()
                    val isPlaylist = upstream.substringBefore('?').endsWith(".m3u8") || type.contains("mpegurl", ignoreCase = true)
                    val reason = resp.message.ifBlank { if (resp.isSuccessful) "OK" else "Error" }
                    val extra = mutableMapOf("Content-Type" to type.ifBlank { "application/octet-stream" })
                    if (isPlaylist && resp.isSuccessful) {
                        val self = "http://${headers["host"]}/p?u="
                        val bytes = rewrite(body.string(), upstream, self).toByteArray()
                        extra["Content-Type"] = "application/vnd.apple.mpegurl"
                        writeHead(out, resp.code, reason, extra, bytes.size.toLong())
                        if (method != "HEAD") out.write(bytes)
                    } else {
                        resp.header("Content-Range")?.let { extra["Content-Range"] = it }
                        resp.header("Accept-Ranges")?.let { extra["Accept-Ranges"] = it }
                        writeHead(out, resp.code, reason, extra, body.contentLength())
                        if (method != "HEAD") body.byteStream().copyTo(out)
                    }
                    out.flush()
                }
            }
        } catch (e: Exception) {
            // A TV closing a connection early is normal (seeking, quality switch); never crash the app over it.
            Log.d(TAG, "request ended: ${e.message}")
        }
    }

    /** Points every link in an HLS playlist (lines and URI="…" attributes) back through this server. */
    private fun rewrite(playlist: String, base: String, self: String): String {
        fun proxied(ref: String): String {
            val abs = absolute(base, ref.trim())
            allow(abs)
            return self + URLEncoder.encode(abs, "UTF-8")
        }
        return playlist.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> URI_ATTR.replace(line) { m -> "URI=\"" + proxied(m.groupValues[1]) + "\"" }
                else -> proxied(line)
            }
        }
    }

    private fun absolute(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { URI(base).resolve(ref).toString() }.getOrElse {
            if (ref.startsWith("/")) {
                val u = URI(base); "${u.scheme}://${u.authority}$ref"
            } else {
                base.substringBefore('?').substringBeforeLast('/') + "/" + ref
            }
        }
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>, length: Long) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16_384) return null
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }
}
