package com.anistream.tv

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.anistream.viewmodel.AnimeDetailViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
private const val OVERLAY_MS = 4_000L

private fun subtitleMime(url: String): String = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    else -> MimeTypes.TEXT_VTT
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Full-screen player driven by the remote:
 *  - hidden controls: OK pauses/plays, ◀ ▶ skip 10 s (hold to go faster), ▲ ▼ show controls
 *  - controls shown: ◀ ▶ on the bar skip, ▼ to the buttons (previous, play, next, subtitles), Back hides them
 *  - media keys: play/pause, fast-forward/rewind 30 s, next/previous episode
 * Plays the next episode by itself when one ends (if Autoplay is on).
 */
@OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(vm: AnimeDetailViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    val info by vm.streamInfo.collectAsState()
    val streamLoading by vm.streamLoading.collectAsState()
    val error by vm.error.collectAsState()
    val episodes by vm.episodes.collectAsState()
    val current by vm.currentEpisode.collectAsState()

    val player = remember {
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }

    var overlay by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var hasSubs by remember { mutableStateOf(false) }
    var subsOn by remember { mutableStateOf(true) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var seekFlash by remember { mutableIntStateOf(0) }   // +/- seconds of the last skip, shown briefly
    var seekFlashAt by remember { mutableLongStateOf(0L) }
    var seekBarFocused by remember { mutableStateOf(false) }

    val rootFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    val index = episodes.indexOfFirst { it.dataId == current?.dataId }
    val prevEp = if (index > 0) episodes[index - 1] else null
    val nextEp = if (index >= 0 && index + 1 < episodes.size) episodes[index + 1] else null
    val nextEpState by rememberUpdatedState(nextEp)

    fun savePosition() {
        if (vm.streamInfo.value != null && player.duration > 0 && player.playbackState != Player.STATE_IDLE) {
            vm.updatePlaybackPosition(player.currentPosition, player.duration)
        }
    }

    fun goTo(ep: com.anistream.data.Episode?) {
        if (ep == null || streamLoading) return
        savePosition()
        player.stop()
        vm.playEpisode(ep)
    }

    // Load each stream the view model hands us (the first one, and next/previous episodes).
    LaunchedEffect(info) {
        val i = info ?: return@LaunchedEffect
        playerError = null
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf("Referer" to i.referer))
        val item = MediaItem.Builder()
            .setUri(i.masterUrl.ifBlank { i.videoUrl })
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .apply {
                i.subtitleUrl?.let { sub ->
                    setSubtitleConfigurations(
                        listOf(
                            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub))
                                .setMimeType(subtitleMime(sub))
                                .setLanguage("en")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build(),
                        ),
                    )
                }
            }
            .build()
        player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
        val resume = vm.resumePositionMs
        vm.resumePositionMs = 0L
        if (resume > 0) player.seekTo(resume)
        player.prepare()
        player.play()
        overlay = true
        lastInput = System.currentTimeMillis()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    savePosition()
                    val next = nextEpState
                    if (next != null && com.anistream.data.PlaybackPrefs.autoplayNext) vm.playEpisode(next) else overlay = true
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                hasSubs = tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }
            }
            override fun onPlayerError(e: PlaybackException) {
                playerError = "Playback failed (${e.errorCodeName})"
                overlay = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Save where you are every few seconds, and keep the clock on screen moving.
    LaunchedEffect(player) {
        var tick = 0
        while (isActive) {
            delay(250)
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            if (++tick % 20 == 0 && player.isPlaying) savePosition()
            if (overlay && playing && System.currentTimeMillis() - lastInput > OVERLAY_MS) overlay = false
        }
    }

    // Pressing Home on the remote pauses instead of playing on behind the launcher.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { savePosition(); player.pause() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            savePosition()
            player.release()
            vm.clearStreamInfo()
        }
    }

    LaunchedEffect(overlay) {
        delay(16)
        if (overlay) seekFocus.tryFocus() else rootFocus.tryFocus()
    }

    BackHandler {
        if (overlay && playerError == null && error == null) overlay = false else onExit()
    }

    fun seekBy(ms: Long) {
        val dur = player.duration
        val target = (player.currentPosition + ms).coerceIn(0L, if (dur > 0) dur - 1_000 else Long.MAX_VALUE)
        player.seekTo(target)
        position = target
        seekFlash = (ms / 1000).toInt()
        seekFlashAt = System.currentTimeMillis()
    }

    fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                val down = ev.type == KeyEventType.KeyDown
                val repeat = ev.nativeKeyEvent.repeatCount
                // Hold ◀/▶ to skip faster: 10 s, then 30 s, then 60 s steps.
                val step = when {
                    repeat > 20 -> 60_000L
                    repeat > 6 -> 30_000L
                    else -> 10_000L
                }
                when (ev.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (down && repeat == 0) {
                            when (ev.key) {
                                Key.MediaPlay -> player.play()
                                Key.MediaPause -> player.pause()
                                else -> togglePlay()
                            }
                            overlay = true; lastInput = System.currentTimeMillis()
                        }
                        return@onPreviewKeyEvent true
                    }
                    Key.MediaFastForward -> { if (down) seekBy(30_000); return@onPreviewKeyEvent true }
                    Key.MediaRewind -> { if (down) seekBy(-30_000); return@onPreviewKeyEvent true }
                    Key.MediaNext -> { if (down && repeat == 0) goTo(nextEp); return@onPreviewKeyEvent true }
                    Key.MediaPrevious -> { if (down && repeat == 0) goTo(prevEp); return@onPreviewKeyEvent true }
                }
                if (ev.key == Key.Back) return@onPreviewKeyEvent false
                lastInput = System.currentTimeMillis()

                val isOk = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                if (!overlay) {
                    when {
                        isOk -> { if (down && repeat == 0) { togglePlay(); overlay = true }; true }
                        ev.key == Key.DirectionLeft -> { if (down) seekBy(-step); true }
                        ev.key == Key.DirectionRight -> { if (down) seekBy(step); true }
                        ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> { if (down) overlay = true; true }
                        else -> false
                    }
                } else if (seekBarFocused) {
                    when {
                        isOk -> { if (down && repeat == 0) togglePlay(); true }
                        ev.key == Key.DirectionLeft -> { if (down) seekBy(-step); true }
                        ev.key == Key.DirectionRight -> { if (down) seekBy(step); true }
                        ev.key == Key.DirectionUp -> true
                        else -> false
                    }
                } else false
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val message = playerError ?: error
        when {
            message != null -> Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp)).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Press Back to return to the episode list", color = TvColors.TextDim)
            }
            info == null || streamLoading -> Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = TvColors.Accent)
                Spacer(Modifier.height(14.dp))
                Text("Loading episode ${current?.number ?: ""}…", color = TvColors.Text)
            }
            buffering -> CircularProgressIndicator(color = TvColors.Accent, modifier = Modifier.align(Alignment.Center))
        }

        // "+10 s" bubble while skipping with the controls hidden.
        if (!overlay && seekFlash != 0 && System.currentTimeMillis() - seekFlashAt < 900 && position >= 0) {
            Text(
                (if (seekFlash > 0) "+" else "−") + "${kotlin.math.abs(seekFlash)} s   ${formatTime(position)}",
                style = MaterialTheme.typography.titleLarge,
                color = TvColors.Text,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }

        AnimatedVisibility(
            visible = overlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.3f to Color.Black.copy(alpha = 0.7f),
                            1f to Color.Black.copy(alpha = 0.95f),
                        ),
                    )
                    .padding(horizontal = TvInsets.Horizontal, vertical = TvInsets.Vertical)
                    .padding(top = 90.dp),
            ) {
                Text(vm.currentAnimeTitle, style = MaterialTheme.typography.titleMedium, color = TvColors.Gold)
                Text(
                    "Episode ${current?.number ?: ""}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))

                SeekBar(
                    position = position,
                    duration = duration,
                    focusRequester = seekFocus,
                    downTarget = playFocus,
                    onFocus = { seekBarFocused = it },
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(position), color = TvColors.Text, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (duration > 0) "−" + formatTime(duration - position) else "",
                        color = TvColors.TextDim,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (prevEp != null) TvButton("Episode ${prevEp.number}", onClick = { goTo(prevEp) }, icon = Icons.Default.SkipPrevious)
                    TvButton(
                        if (playing) "Pause" else "Play",
                        onClick = { togglePlay() },
                        icon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        focusRequester = playFocus,
                    )
                    if (nextEp != null) TvButton("Episode ${nextEp.number}", onClick = { goTo(nextEp) }, icon = Icons.Default.SkipNext)
                    if (hasSubs) {
                        TvButton(
                            if (subsOn) "Subtitles on" else "Subtitles off",
                            onClick = {
                                subsOn = !subsOn
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subsOn)
                                    .build()
                            },
                            icon = if (subsOn) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("◀ ▶ skip  ·  OK pause  ·  ▼ more", color = TvColors.TextDim, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    focusRequester: FocusRequester,
    downTarget: FocusRequester,
    onFocus: (Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .focusRequester(focusRequester)
            .focusProperties { down = downTarget }   // ▼ goes to Play/Pause, not whichever button is nearest
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val barHeight = if (focused) 8.dp else 5.dp
        Box(
            Modifier.fillMaxWidth().height(barHeight)
                .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(4.dp)),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(TvColors.Accent, RoundedCornerShape(4.dp)))
        }
        if (focused) {
            // The thumb: placed with a weight-free offset so it tracks the fill.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .offset(x = maxWidth * fraction - 9.dp)
                        .size(18.dp)
                        .background(TvColors.Text, CircleShape)
                        .border(2.dp, TvColors.Accent, CircleShape),
                )
            }
        }
    }
}
