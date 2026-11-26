package com.anistream.cast

import android.content.Context
import android.util.Log
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which TV the phone is playing on (if any), whatever kind it is. */
object CastController {
    private const val TAG = "AniCast"

    private var castContext: CastContext? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _active = MutableStateFlow<RemotePlayer?>(null)
    /** The TV being played on, or null when not casting. */
    val active = _active.asStateFlow()

    private val _device = MutableStateFlow<String?>(null)
    /** Name of the connected TV, or null when not casting. */
    val device = _device.asStateFlow()

    private val _available = MutableStateFlow(false)
    /** True in the phone app (casting to LG/DLNA TVs works even without Google Play services). */
    val available = _available.asStateFlow()

    val context: CastContext? get() = castContext

    /** Call once from the phone activity (main thread). Safe to call again. */
    @Suppress("DEPRECATION")
    fun init(context: Context) {
        _available.value = true
        if (castContext != null) return
        castContext = try {
            CastContext.getSharedInstance(context.applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Google Cast unavailable: ${e.message}")
            null
        }
        val ctx = castContext ?: return
        ctx.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { setActive(GoogleCastPlayer(it, ctx)) }
    }

    private fun setActive(player: RemotePlayer?) {
        _active.value = player
        _device.value = player?.name
    }

    /** Connects to an LG or DLNA TV. [onPrompt] runs while an LG TV is asking to allow the phone. */
    suspend fun connectLan(context: Context, tv: LanTv, onPrompt: () -> Unit): String? {
        disconnect()
        if (tv.isLg) {
            val lg = LgTvPlayer.connect(context, tv, onPrompt)
            lg.getOrNull()?.let { setActive(it); return null }
            // LG's own way didn't work (switched off in the TV's settings, or pairing refused): try DLNA.
            val dlna = tv.dlna ?: return "Couldn't connect to ${tv.name}: ${lg.exceptionOrNull()?.message}"
            Log.i(TAG, "LG pairing failed (${lg.exceptionOrNull()?.message}), using DLNA")
            setActive(DlnaPlayer(tv.name, dlna))
            return null
        }
        val dlna = tv.dlna ?: return "${tv.name} can't play video from the phone"
        setActive(DlnaPlayer(tv.name, dlna))
        return null
    }

    fun disconnect() {
        val player = _active.value ?: return
        setActive(null)
        scope.launch {
            runCatching { player.release() }
            StreamProxy.stop()
        }
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
            val ctx = castContext ?: return
            val previous = _active.value
            if (previous != null && previous !is GoogleCastPlayer) scope.launch { runCatching { previous.release() } }
            setActive(GoogleCastPlayer(session, ctx))
        }

        private fun ended() {
            if (_active.value is GoogleCastPlayer) {
                setActive(null)
                StreamProxy.stop()
            }
        }
    }
}
