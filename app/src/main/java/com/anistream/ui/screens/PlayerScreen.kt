package com.anistream.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import com.anistream.cast.CastButton
import com.anistream.cast.CastController
import com.anistream.data.StreamInfo
import com.anistream.viewmodel.AnimeDetailViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun PlayerScreen(
    viewModel: AnimeDetailViewModel,
    onDismiss: () -> Unit,
) {
    val streamInfo by viewModel.streamInfo.collectAsState()
    val device by CastController.device.collectAsState()

    // Once a TV takes over, this screen stays the remote; when casting stops (here, in the notification,
    // or on the TV) it closes instead of suddenly playing on the phone.
    var castMode by remember { mutableStateOf(device != null) }
    LaunchedEffect(device) {
        if (device != null) castMode = true else if (castMode) onDismiss()
    }

    BackHandler { onDismiss() }
    DisposableEffect(Unit) { onDispose { viewModel.clearStreamInfo() } }

    val info = streamInfo
    when {
        castMode -> CastRemoteScreen(viewModel, info, device, onDismiss)
        info == null -> Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        else -> LocalPlayer(viewModel, info, onDismiss)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun LocalPlayer(viewModel: AnimeDetailViewModel, info: StreamInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity

    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = activity?.window?.insetsController
        controller?.hide(
            android.view.WindowInsets.Type.statusBars() or
            android.view.WindowInsets.Type.navigationBars()
        )
        controller?.systemBarsBehavior =
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val c = activity?.window?.insetsController
            c?.show(
                android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
            )
        }
    }

    val player = remember(info) {
        ExoPlayer.Builder(context).build().apply {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(
                    mapOf(
                        "Referer" to info.referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    )
                )
            val hlsSource = HlsMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(info.videoUrl))
            setMediaSource(hlsSource)
            prepare()
            val resume = viewModel.resumePositionMs
            viewModel.resumePositionMs = 0L
            if (resume > 0) seekTo(resume)
            playWhenReady = true
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            delay(5000)
            if (player.duration > 0) {
                viewModel.updatePlaybackPosition(player.currentPosition, player.duration)
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            if (player.duration > 0) {
                viewModel.updatePlaybackPosition(player.currentPosition, player.duration)
            }
            // If this player is closing because a TV connected, the TV picks up from here.
            viewModel.resumePositionMs = player.currentPosition
            player.release()
        }
    }

    // The Cast icon comes and goes with the player's own controls.
    var controlsVisible by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setFullscreenButtonState(true)
                    setFullscreenButtonClickListener { isFullscreen ->
                        if (!isFullscreen) onDismiss()
                    }
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { controlsVisible = it == android.view.View.VISIBLE }
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (controlsVisible) {
            Box(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                CastButton(tint = Color.White)
            }
        }
    }
}
