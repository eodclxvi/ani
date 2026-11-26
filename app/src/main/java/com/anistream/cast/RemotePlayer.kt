package com.anistream.cast

import android.content.Context
import com.anistream.data.StreamInfo

/** What to play on the TV. */
data class CastItem(
    val info: StreamInfo,
    val title: String,
    val subtitle: String,
    val posterUrl: String?,
    val startMs: Long,
)

/** What the TV is doing, for the remote screen. */
data class RemoteStatus(
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** The episode played to the end. */
    val finished: Boolean = false,
    val error: String? = null,
)

/** A TV the phone is playing on: Chromecast / Google TV, an LG webOS TV, or any DLNA TV. */
interface RemotePlayer {
    val name: String
    val supportsSubtitles: Boolean get() = false

    /** Starts [item] on the TV. Returns an error message, or null when the TV accepted it. */
    suspend fun load(context: Context, item: CastItem): String?
    suspend fun status(): RemoteStatus
    suspend fun setPlaying(play: Boolean)
    suspend fun seek(positionMs: Long)
    fun setSubtitles(on: Boolean) {}

    /** Stops playback on the TV and lets go of it. */
    suspend fun release()
}

/** Keeps time for TVs that can't report their position: counts while playing, holds while paused. */
internal class PlaybackClock {
    private var baseMs = 0L
    private var runningSince = -1L

    fun start(atMs: Long) {
        baseMs = atMs
        runningSince = android.os.SystemClock.elapsedRealtime()
    }

    fun pause() {
        if (runningSince >= 0) baseMs = position()
        runningSince = -1
    }

    fun resume() {
        if (runningSince < 0) runningSince = android.os.SystemClock.elapsedRealtime()
    }

    val running get() = runningSince >= 0

    fun position(): Long =
        if (runningSince < 0) baseMs else baseMs + (android.os.SystemClock.elapsedRealtime() - runningSince)
}
