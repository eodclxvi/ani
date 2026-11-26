package com.anistream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.anistream.cast.CastController
import com.anistream.data.StreamInfo
import com.anistream.viewmodel.AnimeDetailViewModel
import com.google.android.gms.cast.MediaStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The phone as a remote while the episode plays on the TV. */
@Composable
fun CastRemoteScreen(
    viewModel: AnimeDetailViewModel,
    info: StreamInfo?,
    device: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val episodes by viewModel.episodes.collectAsState()
    val episode by viewModel.currentEpisode.collectAsState()
    val index = episodes.indexOfFirst { it.dataId == episode?.dataId }
    val previous = episodes.getOrNull(index - 1).takeIf { index > 0 }
    val next = episodes.getOrNull(index + 1).takeIf { index >= 0 }

    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var subtitlesOn by remember { mutableStateOf(true) }

    // Send each stream to the TV (the first one, and the next/previous episodes).
    LaunchedEffect(info) {
        val i = info ?: return@LaunchedEffect
        val start = viewModel.resumePositionMs
        viewModel.resumePositionMs = 0L
        buffering = true
        error = CastController.load(
            appContext = context.applicationContext,
            info = i,
            title = viewModel.currentAnimeTitle,
            subtitle = episode?.let { "Episode ${it.number}" }.orEmpty(),
            posterUrl = viewModel.currentPosterUrl,
            startMs = start,
        )
        subtitlesOn = true
    }

    // Follow the TV: position, play/pause, and on to the next episode when one ends.
    LaunchedEffect(info) {
        var lastSaved = 0L
        var finishedHandled = false
        var sawPlayback = false
        while (isActive) {
            delay(500)
            val remote = CastController.remote() ?: continue
            val state = remote.playerState
            playing = state == MediaStatus.PLAYER_STATE_PLAYING
            buffering = state == MediaStatus.PLAYER_STATE_BUFFERING || state == MediaStatus.PLAYER_STATE_LOADING
            if (playing || state == MediaStatus.PLAYER_STATE_PAUSED) sawPlayback = true
            if (!dragging) positionMs = remote.approximateStreamPosition
            durationMs = remote.streamDuration.coerceAtLeast(0)
            val now = System.currentTimeMillis()
            if (durationMs > 0 && now - lastSaved > 5_000) {
                lastSaved = now
                viewModel.updatePlaybackPosition(positionMs, durationMs)
            }
            if (sawPlayback && !finishedHandled && state == MediaStatus.PLAYER_STATE_IDLE) {
                when (remote.idleReason) {
                    MediaStatus.IDLE_REASON_FINISHED -> {
                        finishedHandled = true
                        if (durationMs > 0) viewModel.updatePlaybackPosition(durationMs, durationMs)
                        next?.let { viewModel.playEpisode(it) }
                    }
                    MediaStatus.IDLE_REASON_ERROR -> {
                        finishedHandled = true
                        error = "The TV couldn't play this episode"
                    }
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding(),
    ) {
        // Poster takes about a third of the height, so the controls fit without scrolling in landscape too.
        val posterHeight = (maxHeight * 0.36f).coerceIn(120.dp, 380.dp)
        IconButton(onClick = onDismiss, modifier = Modifier.padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = viewModel.currentPosterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .height(posterHeight)
                        .aspectRatio(2f / 3f)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    viewModel.currentAnimeTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                episode?.let {
                    Text("Episode ${it.number}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CastConnected, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (device != null) "Playing on $device" else "Cast ended",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }

                Spacer(Modifier.height(24.dp))
                Slider(
                    value = if (dragging) dragValue else if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onValueChange = { dragging = true; dragValue = it },
                    onValueChangeFinished = {
                        val target = (dragValue * durationMs).toLong()
                        CastController.remote()?.seek(target)
                        positionMs = target
                        dragging = false
                    },
                    enabled = durationMs > 0,
                )
                Row(Modifier.fillMaxWidth()) {
                    val shown = if (dragging) (dragValue * durationMs).toLong() else positionMs
                    Text(formatTime(shown), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium)
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { previous?.let { viewModel.playEpisode(it) } }, enabled = previous != null) {
                        Icon(Icons.Default.SkipPrevious, "Previous episode")
                    }
                    IconButton(onClick = { CastController.remote()?.seek((positionMs - 10_000).coerceAtLeast(0)) }) {
                        Icon(Icons.Default.Replay10, "Back 10 seconds")
                    }
                    FilledIconButton(
                        onClick = { CastController.remote()?.togglePlayback() },
                        modifier = Modifier.size(64.dp),
                    ) {
                        if (buffering && !playing) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(36.dp))
                        }
                    }
                    IconButton(onClick = { CastController.remote()?.seek(positionMs + 10_000) }) {
                        Icon(Icons.Default.Forward10, "Forward 10 seconds")
                    }
                    IconButton(onClick = { next?.let { viewModel.playEpisode(it) } }, enabled = next != null) {
                        Icon(Icons.Default.SkipNext, "Next episode")
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (info?.subtitleUrl != null) {
                        OutlinedButton(onClick = { subtitlesOn = !subtitlesOn; CastController.setSubtitles(subtitlesOn) }) {
                            Icon(if (subtitlesOn) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled, null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (subtitlesOn) "Subtitles on" else "Subtitles off")
                        }
                    }
                    OutlinedButton(onClick = { CastController.disconnect() }) {
                        Text("Stop casting")
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
