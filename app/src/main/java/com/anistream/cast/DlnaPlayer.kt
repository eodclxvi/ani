package com.anistream.cast

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Any DLNA (UPnP) TV, e.g. Samsung, or an LG when its own protocol doesn't work. The TV gets one continuous
 * MPEG-TS video from the phone (most DLNA players can't play HLS). Seeking restarts that stream at the new
 * point, which works on every TV instead of trusting each TV's own seek.
 */
class DlnaPlayer(
    override val name: String,
    private val dlna: DlnaInfo,
) : RemotePlayer {
    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private var item: CastItem? = null
    private var context: Context? = null
    /** Where the current stream starts in the episode; the TV counts from 0 at that point. */
    private var offsetMs = 0L
    private var durationMs = 0L
    private var sawPlaying = false
    private var lastPositionMs = 0L
    /**
     * The stream keeps the episode's own timestamps, so some TVs report 5:00 for a stream started at 5:00
     * and others 0:00. Decided from the first reading after each start.
     */
    private var tvCountsEpisodeTime: Boolean? = null

    override suspend fun load(context: Context, item: CastItem): String? = withContext(Dispatchers.IO) {
        this@DlnaPlayer.context = context.applicationContext
        this@DlnaPlayer.item = item
        val ip = LanNetwork.wifiAddress(context) ?: return@withContext "Connect to the same Wi-Fi as the TV"
        val master = item.info.masterUrl.ifBlank { item.info.videoUrl }
        StreamProxy.start(item.info.referer)
        durationMs = StreamProxy.durationMs(master)
        StreamProxy.prepareTs(master)
        playFrom(ip, master, item.startMs)
    }

    private fun playFrom(ip: String, master: String, startMs: Long): String? {
        val startSec = (startMs / 1000).coerceAtLeast(0)
        val url = StreamProxy.tsUrl(ip, master, startSec)
        offsetMs = startSec * 1000
        lastPositionMs = offsetMs
        sawPlaying = false
        tvCountsEpisodeTime = null
        val it = item
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${xml(it?.title ?: "Ani")}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:video/mpeg:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000">${xml(url)}</res></item></DIDL-Lite>"""
        return try {
            runCatching { soap("Stop", "") }
            soap("SetAVTransportURI", "<CurrentURI>${xml(url)}</CurrentURI><CurrentURIMetaData>${xml(didl)}</CurrentURIMetaData>")
            soap("Play", "<Speed>1</Speed>")
            null
        } catch (e: IOException) {
            "The TV didn't accept the video (${e.message})"
        }
    }

    override suspend fun status(): RemoteStatus = withContext(Dispatchers.IO) {
        try {
            val transport = tag(soap("GetTransportInfo", ""), "CurrentTransportState").orEmpty()
            val rel = parseTime(tag(soap("GetPositionInfo", ""), "RelTime"))
            // LG reports its own states too, e.g. LG_TRANSITIONING.
            val playing = transport.endsWith("PLAYING") && !transport.contains("PAUSED")
            if (playing) sawPlaying = true
            if (rel != null && (playing || transport.contains("PAUSED"))) {
                val absolute = tvCountsEpisodeTime
                    ?: (offsetMs >= 30_000 && rel >= offsetMs - 20_000).also { tvCountsEpisodeTime = it }
                lastPositionMs = if (absolute) rel else offsetMs + rel
            }
            val stopped = transport.endsWith("STOPPED") || transport.endsWith("NO_MEDIA_PRESENT")
            RemoteStatus(
                playing = playing,
                buffering = transport.endsWith("TRANSITIONING") || !sawPlaying,
                positionMs = lastPositionMs,
                durationMs = durationMs,
                // The stream ends when the episode does; a stop well before the end was someone pressing Stop.
                finished = sawPlaying && stopped && durationMs > 0 && lastPositionMs > durationMs - 60_000,
            )
        } catch (e: IOException) {
            RemoteStatus(positionMs = lastPositionMs, durationMs = durationMs, error = "Lost the TV (${e.message})")
        }
    }

    override suspend fun setPlaying(play: Boolean) {
        withContext(Dispatchers.IO) { runCatching { if (play) soap("Play", "<Speed>1</Speed>") else soap("Pause", "") } }
    }

    override suspend fun seek(positionMs: Long) {
        withContext(Dispatchers.IO) {
            val ctx = context ?: return@withContext
            val it = item ?: return@withContext
            val ip = LanNetwork.wifiAddress(ctx) ?: return@withContext
            playFrom(ip, it.info.masterUrl.ifBlank { it.info.videoUrl }, positionMs.coerceIn(0, (durationMs - 5_000).coerceAtLeast(0)))
        }
    }

    override suspend fun release() {
        withContext(Dispatchers.IO) { runCatching { soap("Stop", "") } }
    }

    private fun soap(action: String, args: String): String {
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${dlna.serviceType}"><InstanceID>0</InstanceID>$args</u:$action></s:Body></s:Envelope>"""
        val request = Request.Builder()
            .url(dlna.controlUrl)
            .header("SOAPACTION", "\"${dlna.serviceType}#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("$action: HTTP ${resp.code} ${tag(text, "errorDescription").orEmpty()}".trim())
            return text
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name>([^<]*)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)

    /** "0:01:23" or "00:01:23.500" → ms. */
    private fun parseTime(t: String?): Long? {
        val parts = t?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return ((h * 3600 + m * 60) * 1000 + s * 1000).toLong()
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
