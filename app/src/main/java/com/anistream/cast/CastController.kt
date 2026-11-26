package com.anistream.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.anistream.data.StreamInfo
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/** Everything the phone app needs to know about casting: is a TV connected, and send a stream to it. */
object CastController {
    private const val TAG = "AniCast"
    private const val SUBTITLE_TRACK_ID = 1L

    private var castContext: CastContext? = null

    private val _device = MutableStateFlow<String?>(null)
    /** Name of the connected Cast device, or null when not casting. */
    val device = _device.asStateFlow()

    private val _available = MutableStateFlow(false)
    /** False on devices without Google Play services (or on the TV app), so the Cast button stays hidden. */
    val available = _available.asStateFlow()

    val context: CastContext? get() = castContext

    /** Call once from the phone activity (main thread). Safe to call again. */
    @Suppress("DEPRECATION")
    fun init(context: Context) {
        if (castContext != null) return
        castContext = try {
            CastContext.getSharedInstance(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Cast unavailable: ${e.message}")
            null
        }
        val ctx = castContext ?: return
        _available.value = true
        ctx.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { _device.value = it.castDevice?.friendlyName ?: "TV" }
    }

    fun remote(): RemoteMediaClient? = castContext?.sessionManager?.currentCastSession?.remoteMediaClient

    fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    /** Plays [info] on the TV. Returns an error message, or null when the TV accepted it. */
    fun load(
        appContext: Context,
        info: StreamInfo,
        title: String,
        subtitle: String,
        posterUrl: String?,
        startMs: Long,
    ): String? {
        val client = remote() ?: return "Not connected to a TV"
        val ip = wifiAddress(appContext) ?: return "Connect the phone to the same Wi-Fi as the TV"
        StreamProxy.start(info.referer)
        val streamUrl = StreamProxy.url(ip, info.masterUrl.ifBlank { info.videoUrl })

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_TV_SHOW).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            putString(MediaMetadata.KEY_SUBTITLE, subtitle)
            posterUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        // The default TV player only shows WebVTT subtitles.
        val subtitleTrack = info.subtitleUrl
            ?.takeIf { it.substringBefore('?').lowercase().endsWith(".vtt") }
            ?.let {
                MediaTrack.Builder(SUBTITLE_TRACK_ID, MediaTrack.TYPE_TEXT)
                    .setName("English")
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(StreamProxy.url(ip, it))
                    .setContentType("text/vtt")
                    .setLanguage("en")
                    .build()
            }
        val media = MediaInfo.Builder(streamUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("application/x-mpegurl")
            .setHlsSegmentFormat(HlsSegmentFormat.TS)
            .setHlsVideoSegmentFormat(HlsVideoSegmentFormat.MPEG2_TS)
            .setMetadata(metadata)
            .apply { subtitleTrack?.let { setMediaTracks(listOf(it)) } }
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(media)
            .setAutoplay(true)
            .setCurrentTime(startMs.coerceAtLeast(0))
            .apply { if (subtitleTrack != null) setActiveTrackIds(longArrayOf(SUBTITLE_TRACK_ID)) }
            .build()
        client.load(request)
        return null
    }

    /** Subtitles on or off on the TV. */
    fun setSubtitles(on: Boolean) {
        remote()?.setActiveMediaTracks(if (on) longArrayOf(SUBTITLE_TRACK_ID) else longArrayOf())
    }

    /** The phone's own address on Wi-Fi (not Tailscale or mobile data): the TV reaches the proxy through it. */
    @Suppress("DEPRECATION")
    private fun wifiAddress(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!lan || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val address = cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            if (address != null) return address.hostAddress
        }
        return null
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)
        override fun onSessionEnded(session: CastSession, error: Int) = ended()
        override fun onSessionStartFailed(session: CastSession, error: Int) = ended()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = ended()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}

        private fun connected(session: CastSession) {
            _device.value = session.castDevice?.friendlyName ?: "TV"
        }

        private fun ended() {
            _device.value = null
            StreamProxy.stop()
        }
    }
}
