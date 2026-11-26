package com.anistream.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.HlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.common.images.WebImage

/** Chromecast / Google TV, through Google's Default Media Receiver. */
class GoogleCastPlayer(
    private val session: CastSession,
    private val castContext: CastContext,
) : RemotePlayer {
    override val name: String = session.castDevice?.friendlyName ?: "TV"
    override val supportsSubtitles = true

    private val client get() = session.remoteMediaClient

    override suspend fun load(context: Context, item: CastItem): String? {
        val client = client ?: return "Not connected to the TV"
        val ip = LanNetwork.wifiAddress(context) ?: return "Connect to the same Wi-Fi as the TV"
        val info = item.info
        StreamProxy.start(info.referer)
        val streamUrl = StreamProxy.url(ip, info.masterUrl.ifBlank { info.videoUrl })

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_TV_SHOW).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            putString(MediaMetadata.KEY_SUBTITLE, item.subtitle)
            item.posterUrl?.let { addImage(WebImage(Uri.parse(it))) }
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
            .setCurrentTime(item.startMs.coerceAtLeast(0))
            .apply { if (subtitleTrack != null) setActiveTrackIds(longArrayOf(SUBTITLE_TRACK_ID)) }
            .build()
        client.load(request)
        return null
    }

    override suspend fun status(): RemoteStatus {
        val c = client ?: return RemoteStatus(error = "Not connected to the TV")
        val state = c.playerState
        val idle = state == MediaStatus.PLAYER_STATE_IDLE
        return RemoteStatus(
            playing = state == MediaStatus.PLAYER_STATE_PLAYING,
            buffering = state == MediaStatus.PLAYER_STATE_BUFFERING || state == MediaStatus.PLAYER_STATE_LOADING,
            positionMs = c.approximateStreamPosition,
            durationMs = c.streamDuration.coerceAtLeast(0),
            finished = idle && c.idleReason == MediaStatus.IDLE_REASON_FINISHED,
            error = if (idle && c.idleReason == MediaStatus.IDLE_REASON_ERROR) "The TV couldn't play this episode" else null,
        )
    }

    override suspend fun setPlaying(play: Boolean) {
        client?.let { if (play) it.play() else it.pause() }
    }

    override suspend fun seek(positionMs: Long) {
        client?.seek(MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0)).build())
    }

    override fun setSubtitles(on: Boolean) {
        client?.setActiveMediaTracks(if (on) longArrayOf(SUBTITLE_TRACK_ID) else longArrayOf())
    }

    override suspend fun release() {
        castContext.sessionManager.endCurrentSession(true)
    }

    private companion object {
        const val SUBTITLE_TRACK_ID = 1L
    }
}
