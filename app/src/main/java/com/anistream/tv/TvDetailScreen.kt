package com.anistream.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.anistream.data.Episode
import com.anistream.data.WatchHistoryEntry
import com.anistream.viewmodel.AnimeDetailViewModel

private const val RANGE = 100

/** What the big button does: resume the half-watched episode, start the next one, or start at 1. */
private data class PrimaryAction(val label: String, val episode: Episode, val resumeMs: Long)

private fun primaryAction(episodes: List<Episode>, history: WatchHistoryEntry?): PrimaryAction? {
    if (episodes.isEmpty()) return null
    val idx = history?.let { h -> episodes.indexOfFirst { it.dataId == h.lastEpisodeDataId || it.number == h.lastEpisode } } ?: -1
    if (idx < 0) return PrimaryAction("Play Episode ${episodes.first().number}", episodes.first(), 0)
    val h = history!!
    val ep = episodes[idx]
    val finished = h.durationMs > 0 && h.positionMs >= h.durationMs * 0.9
    return when {
        finished && idx + 1 < episodes.size ->
            PrimaryAction("Play Episode ${episodes[idx + 1].number}", episodes[idx + 1], 0)
        h.positionMs > 5_000 && !finished -> PrimaryAction("Resume Episode ${ep.number}", ep, h.positionMs)
        else -> PrimaryAction("Play Episode ${ep.number}", ep, 0)
    }
}

@Composable
fun TvDetailScreen(
    animeId: String,
    animeTitle: String,
    posterUrl: String?,
    vm: AnimeDetailViewModel,
    onPlay: () -> Unit,
) {
    val episodes by vm.episodes.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val streamLoading by vm.streamLoading.collectAsState()
    val streamInfo by vm.streamInfo.collectAsState()
    val history by vm.watchHistory.collectAsState()
    val isDub by vm.isDub.collectAsState()
    val isFavorite by vm.isFavorite.collectAsState()
    val loadingEpisode by vm.currentEpisode.collectAsState()

    // Load once per show; coming Back from the player keeps what is there.
    LaunchedEffect(animeId) {
        if (vm.currentAnimeId != animeId || episodes.isEmpty()) vm.loadEpisodes(animeId, animeTitle, posterUrl)
    }
    // Go to the player only for a play started here, never because an old stream is still set.
    var awaitingStream by remember { mutableStateOf(false) }
    LaunchedEffect(streamInfo, awaitingStream) {
        if (awaitingStream && streamInfo != null) {
            awaitingStream = false
            onPlay()
        }
    }
    LaunchedEffect(error) { if (error != null) awaitingStream = false }

    val play: (Episode, Long) -> Unit = { ep, resume ->
        if (!streamLoading) {
            awaitingStream = true
            vm.playEpisode(ep, resumeMs = resume)
        }
    }

    val primary = primaryAction(episodes, history)
    val ranges = remember(episodes) { episodes.chunked(RANGE) }
    val historyRange = remember(episodes, history) {
        val i = episodes.indexOfFirst { it.dataId == history?.lastEpisodeDataId }
        if (i >= 0) i / RANGE else 0
    }
    var rangeIndex by rememberSaveable(animeId) { mutableIntStateOf(-1) }
    val shownRange = (if (rangeIndex >= 0) rangeIndex else historyRange).coerceIn(0, (ranges.size - 1).coerceAtLeast(0))
    val shown = ranges.getOrElse(shownRange) { emptyList() }
    val primaryFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        // Blurred poster as the backdrop.
        if (posterUrl != null) {
            AsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(40.dp).alpha(0.35f),
            )
            Box(Modifier.fillMaxSize().scrim())
        }

        Row(Modifier.fillMaxSize().padding(horizontal = TvInsets.Horizontal, vertical = TvInsets.Vertical)) {
            Column(Modifier.width(220.dp).fillMaxHeight()) {
                Spacer(Modifier.height(12.dp))
                AsyncImage(
                    model = posterUrl,
                    contentDescription = animeTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(220.dp)
                        .height(314.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(TvColors.Surface),
                )
            }
            Spacer(Modifier.width(40.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                contentPadding = PaddingValues(top = 12.dp, bottom = 40.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    Column {
                        Text(
                            animeTitle,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        val meta = buildList {
                            if (episodes.isNotEmpty()) add("${episodes.size} episode${if (episodes.size == 1) "" else "s"}")
                            add(if (isDub) "English dub" else "Japanese, subtitled")
                            history?.let { add("Last watched: episode ${it.lastEpisode}") }
                        }.joinToString("  ·  ")
                        Text(meta, style = MaterialTheme.typography.titleMedium, color = TvColors.TextDim)
                        Spacer(Modifier.height(22.dp))

                        when {
                            loading -> CircularProgressIndicator(color = TvColors.Accent)
                            error != null && episodes.isEmpty() -> {
                                Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(14.dp))
                                TvButton(
                                    "Try again",
                                    onClick = { vm.loadEpisodes(animeId, animeTitle, posterUrl) },
                                    icon = Icons.Default.Refresh,
                                    focusRequester = retryFocus,
                                )
                                InitialFocus(retryFocus)
                            }
                            else -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (primary != null) {
                                    TvButton(
                                        primary.label,
                                        onClick = { play(primary.episode, primary.resumeMs) },
                                        icon = Icons.Default.PlayArrow,
                                        selected = true,
                                        focusRequester = primaryFocus,
                                    )
                                    InitialFocus(primaryFocus, key = episodes.size)
                                }
                                TvButton("Sub", onClick = { if (isDub) vm.toggleDub() }, selected = !isDub)
                                TvButton("Dub", onClick = { if (!isDub) vm.toggleDub() }, selected = isDub)
                                TvButton(
                                    if (isFavorite) "Favourited" else "Favourite",
                                    onClick = vm::toggleFavorite,
                                    icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                )
                            }
                        }

                        if (error != null && episodes.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                        }

                        if (ranges.size > 1) {
                            Spacer(Modifier.height(24.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                itemsIndexed(ranges) { i, chunk ->
                                    TvButton(
                                        "${chunk.first().number}–${chunk.last().number}",
                                        onClick = { rangeIndex = i },
                                        selected = i == shownRange,
                                    )
                                }
                            }
                        }
                        if (episodes.isNotEmpty()) {
                            Spacer(Modifier.height(24.dp))
                            Text("Episodes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                items(shown, key = { it.dataId }) { ep ->
                    val lastNum = history?.lastEpisode?.toDoubleOrNull()
                    val num = ep.number.toDoubleOrNull()
                    val isCurrent = ep.dataId == history?.lastEpisodeDataId
                    val watched = !isCurrent && lastNum != null && num != null && num < lastNum
                    EpisodeTile(
                        ep = ep,
                        watched = watched,
                        current = isCurrent,
                        onClick = {
                            val h = history
                            val resume = if (isCurrent && h != null && h.positionMs > 5_000 &&
                                (h.durationMs <= 0 || h.positionMs < h.durationMs * 0.9)) h.positionMs else 0L
                            play(ep, resume)
                        },
                    )
                }
            }
        }

        if (streamLoading) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = TvColors.Accent)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Loading episode ${loadingEpisode?.number ?: ""}…",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeTile(ep: Episode, watched: Boolean, current: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        color = when {
            current -> TvColors.Accent.copy(alpha = 0.85f)
            else -> TvColors.SurfaceHigh.copy(alpha = if (watched) 0.55f else 0.95f)
        },
        focusedColor = TvColors.Focus,
        focusedScale = 1.1f,
    ) { focused ->
        val color = when {
            focused -> TvColors.Background
            watched -> TvColors.TextDim
            else -> TvColors.Text
        }
        Text(
            ep.number,
            color = color,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
        if (watched) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Watched",
                tint = color,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp),
            )
        }
    }
}
