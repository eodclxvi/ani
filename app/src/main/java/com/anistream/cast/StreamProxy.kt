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
 *
 * Two kinds of address:
 *  - /p?u=<url>[&s=<seconds>]  the HLS playlist/segment itself (Chromecast, LG). With s, playlists start there.
 *  - /ts?u=<master>&s=<seconds> one continuous MPEG-TS video stitched from the segments, for DLNA TVs that
 *                               can't play HLS. Starts at s seconds.
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
        layouts.clear()
    }

    /** The address a TV should use for [upstream]. With [startSec] > 0, playlists begin at that point. */
    fun url(phoneIp: String, upstream: String, startSec: Long = 0): String {
        allow(upstream)
        val port = server?.localPort ?: error("Cast proxy not started")
        return "http://$phoneIp:$port/p?u=" + URLEncoder.encode(upstream, "UTF-8") + startParam(startSec)
    }

    /** One continuous MPEG-TS stream of [master], starting at [startSec]: for DLNA TVs. */
    fun tsUrl(phoneIp: String, master: String, startSec: Long = 0): String {
        allow(master)
        val port = server?.localPort ?: error("Cast proxy not started")
        return "http://$phoneIp:$port/ts?u=" + URLEncoder.encode(master, "UTF-8") + startParam(startSec)
    }

    /** Length of the episode in milliseconds (sum of the segment lengths), or 0 if it can't be read. Blocking. */
    fun durationMs(master: String): Long = runCatching {
        (segments(master).sumOf { it.second } * 1000).toLong()
    }.getOrDefault(0L)

    /** Looks up the episode's segment sizes ahead of time, so a DLNA TV's first request answers at once. Blocking. */
    fun prepareTs(master: String) {
        runCatching { layout(master) }
    }

    private fun startParam(startSec: Long) = if (startSec > 0) "&s=$startSec" else ""

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
                Log.d(TAG, "${requestLine.take(120)} range=${headers["range"]} ua=${headers["user-agent"]?.take(60)} feat=${headers["getcontentfeatures.dlna.org"]}")
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val target = parts.getOrNull(1) ?: return

                if (method == "OPTIONS") {
                    writeHead(out, 204, "No Content", emptyMap(), 0)
                    out.flush()
                    return
                }
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val upstream = if (path == "/p" || path == "/ts") query["u"].orEmpty() else ""
                val startSec = query["s"]?.toLongOrNull() ?: 0L
                val host = runCatching { URI(upstream).host?.lowercase() }.getOrNull()
                val okScheme = upstream.startsWith("https://") || upstream.startsWith("http://")
                if (!okScheme || host == null || host !in allowedHosts) {
                    writeHead(out, 403, "Forbidden", emptyMap(), 0)
                    out.flush()
                    return
                }

                if (path == "/ts") {
                    serveTs(out, method, upstream, startSec, headers["range"])
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
                        val text = trim(body.string(), startSec.toDouble())
                        val bytes = rewrite(text, upstream, self, startSec).toByteArray()
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
    private fun rewrite(playlist: String, base: String, self: String, startSec: Long = 0): String {
        fun proxied(ref: String): String {
            val abs = absolute(base, ref.trim())
            allow(abs)
            // Sub-playlists (quality levels, audio, subtitles) start at the same point; segments ignore it.
            return self + URLEncoder.encode(abs, "UTF-8") + startParam(startSec)
        }
        return playlist.lines().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> URI_ATTR.replace(line) { m -> "URI=\"" + proxied(m.groupValues[1]) + "\"" }
                else -> proxied(line)
            }
        }
    }

    /**
     * Drops the segments before [startSec] from a media playlist (one with #EXTINF lines), so a TV that can't
     * be told where to start begins there anyway. Master playlists and startSec = 0 pass through unchanged.
     */
    private fun trim(playlist: String, startSec: Double): String {
        if (startSec <= 0 || !playlist.contains("#EXTINF")) return playlist
        val out = mutableListOf<String>()
        val pending = mutableListOf<String>()
        var inSegments = false
        var t = 0.0
        var dur = 0.0
        var skipped = 0L
        var started = false
        for (line in playlist.lines()) {
            when {
                line.startsWith("#EXTINF") -> {
                    dur = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                    inSegments = true
                    pending += line
                }
                line.isBlank() || line.startsWith("#") -> if (inSegments) pending += line else out += line
                else -> {
                    // Keep the segment that contains startSec, so playback begins a few seconds early, not late.
                    if (started || t + dur > startSec) {
                        started = true
                        out += pending
                        out += line
                    } else {
                        skipped++
                    }
                    pending.clear()
                    t += dur
                    dur = 0.0
                }
            }
        }
        out += pending
        return out.joinToString("\n").replace(Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)")) {
            "#EXT-X-MEDIA-SEQUENCE:" + (it.groupValues[1].toLong() + skipped)
        }
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Referer", referer).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for playlist")
            return resp.body?.string().orEmpty()
        }
    }

    /** The segments (absolute url, seconds) of the best quality in [master]. Blocking. */
    private fun segments(master: String): List<Pair<String, Double>> {
        var playlistUrl = master
        var text = fetchText(master)
        if (text.contains("#EXT-X-STREAM-INF")) {
            // Highest bandwidth variant.
            val lines = text.lines()
            var best: Pair<Long, String>? = null
            lines.forEachIndexed { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val uri = lines.getOrNull(i + 1)?.trim().orEmpty()
                    if (uri.isNotEmpty() && !uri.startsWith("#") && (best == null || bw > best!!.first)) best = bw to uri
                }
            }
            playlistUrl = absolute(master, best?.second ?: throw IOException("No video in playlist"))
            allow(playlistUrl)
            text = fetchText(playlistUrl)
        }
        val result = mutableListOf<Pair<String, Double>>()
        var dur = 0.0
        for (line in text.lines()) {
            when {
                line.startsWith("#EXTINF") -> dur = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.isBlank() || line.startsWith("#") -> {}
                else -> {
                    val abs = absolute(playlistUrl, line.trim())
                    allow(abs)
                    result += abs to dur
                    dur = 0.0
                }
            }
        }
        return result
    }

    private class Segment(val url: String, val seconds: Double, val bytes: Long)

    /** Segment sizes per episode, so the stitched video has an exact length (looked up once). */
    private val layouts = ConcurrentHashMap<String, List<Segment>>()

    /** Every segment of [master] with its size, from parallel HEAD requests. Null if a size is unknown. */
    private fun layout(master: String): List<Segment>? {
        layouts[master]?.let { return it }
        val segs = segments(master)
        val sizes = segs.map { (url, _) ->
            pool.submit<Long> {
                val head = Request.Builder().url(url).head().header("User-Agent", USER_AGENT).header("Referer", referer).build()
                client.newCall(head).execute().use { resp -> resp.header("Content-Length")?.toLongOrNull() ?: -1L }
            }
        }.map { runCatching { it.get(20, TimeUnit.SECONDS) }.getOrDefault(-1L) }
        if (sizes.any { it <= 0 }) return null
        val result = segs.mapIndexed { i, (url, sec) -> Segment(url, sec, sizes[i]) }
        layouts[master] = result
        return result
    }

    /**
     * Streams the segments from [startSec] on, back to back, as one MPEG-TS video (DLNA TVs).
     * Gives the TV an exact length and honours byte ranges ("from byte N"), which LG's player insists on.
     */
    private fun serveTs(out: OutputStream, method: String, master: String, startSec: Long, range: String?) {
        val all = layout(master)?.let { list ->
            var t = 0.0
            list.dropWhile { seg -> (t + seg.seconds <= startSec).also { if (it) t += seg.seconds } }
        }
        val features = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val head = mutableMapOf(
            "Content-Type" to "video/mpeg",
            "transferMode.dlna.org" to "Streaming",
            "contentFeatures.dlna.org" to features,
        )
        if (all == null) {
            // Sizes unknown: plain stream without a length.
            writeHead(out, 200, "OK", head, -1)
            if (method != "HEAD") {
                for (seg in segments(master).let { list -> var t = 0.0; list.dropWhile { (_, d) -> (t + d <= startSec).also { if (it) t += d } } }) {
                    copySegment(out, seg.first, 0)
                }
            }
            out.flush()
            return
        }
        val total = all.sumOf { it.bytes }
        val match = range?.let { Regex("bytes=(\\d+)-(\\d*)").find(it) }
        val from = match?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(0, (total - 1).coerceAtLeast(0)) ?: 0L
        val to = match?.groupValues?.get(2)?.toLongOrNull()?.coerceIn(from, total - 1) ?: (total - 1)
        head["Accept-Ranges"] = "bytes"
        if (match != null) {
            head["Content-Range"] = "bytes $from-$to/$total"
            writeHead(out, 206, "Partial Content", head, to - from + 1)
        } else {
            writeHead(out, 200, "OK", head, total)
        }
        if (method == "HEAD") {
            out.flush()
            return
        }
        // Walk to the segment holding byte `from`, then send bytes until `to`.
        var offset = 0L
        var remaining = to - from + 1
        for (seg in all) {
            if (remaining <= 0) break
            val segEnd = offset + seg.bytes
            if (segEnd > from) {
                val skip = (from - offset).coerceAtLeast(0)
                remaining -= copySegment(out, seg.url, skip, limit = remaining)
            }
            offset = segEnd
        }
        out.flush()
    }

    /** Sends one segment (from byte [skip], at most [limit] bytes); returns how many bytes were sent. */
    private fun copySegment(out: OutputStream, url: String, skip: Long, limit: Long = Long.MAX_VALUE): Long {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Referer", referer).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for segment")
            val input = resp.body?.byteStream() ?: return 0
            var toSkip = skip
            while (toSkip > 0) {
                val n = input.skip(toSkip)
                if (n <= 0) { if (input.read() < 0) return 0 else toSkip-- } else toSkip -= n
            }
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            while (sent < limit) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), limit - sent).toInt())
                if (r < 0) break
                out.write(buf, 0, r)
                sent += r
            }
            return sent
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate {
            val key = it.substringBefore('=')
            key to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
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
