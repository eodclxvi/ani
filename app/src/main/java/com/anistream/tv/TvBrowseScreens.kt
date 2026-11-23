package com.anistream.tv

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.anistream.data.AnimeResult
import com.anistream.data.WatchHistoryEntry
import com.anistream.viewmodel.HistoryViewModel
import com.anistream.viewmodel.SearchViewModel

private fun WatchHistoryEntry.progress(): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

private fun WatchHistoryEntry.toResult() = AnimeResult(animeId, animeTitle, posterUrl)

// ───────────────────────────── Home ─────────────────────────────

private data class Hero(val title: String, val posterUrl: String?, val line: String)

@Composable
fun TvHomeScreen(vm: SearchViewModel, onOpen: (AnimeResult) -> Unit, onSearch: () -> Unit) {
    val history by vm.watchHistory.collectAsState()
    val favorites by vm.favorites.collectAsState()
    var hero by remember { mutableStateOf<Hero?>(null) }
    // Which card had focus, so coming Back from a show lands on the same card.
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    if (history.isEmpty() && favorites.isEmpty()) {
        val searchFocus = remember { FocusRequester() }
        Column(
            Modifier.fillMaxSize().padding(horizontal = TvInsets.Horizontal),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Welcome aboard", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(
                "Find a show to start. What you watch and favourite shows up here.",
                style = MaterialTheme.typography.titleMedium,
                color = TvColors.TextDim,
            )
            Spacer(Modifier.height(28.dp))
            TvButton("Search anime", onClick = onSearch, icon = Icons.Default.Search, focusRequester = searchFocus)
        }
        InitialFocus(searchFocus)
        return
    }

    Box(Modifier.fillMaxSize()) {
        // Hero: the focused show's art fills the top right, faded into the background.
        val h = hero
        if (h?.posterUrl != null) {
            Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.62f).height(300.dp)) {
                AsyncImage(
                    model = h.posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(18.dp).alpha(0.55f),
                )
                Box(Modifier.fillMaxSize().scrim())
                Box(Modifier.fillMaxSize().scrim(horizontal = false))
            }
        }

        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(start = 24.dp, end = TvInsets.Horizontal, top = TvInsets.Vertical + 8.dp),
            ) {
                Text(h?.line ?: "", style = MaterialTheme.typography.labelLarge, color = TvColors.Gold)
                Spacer(Modifier.height(6.dp))
                Text(
                    h?.title ?: "",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = TvInsets.Vertical + 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (history.isNotEmpty()) {
                    item(key = "continue") {
                        ShelfRow("Continue watching") {
                            itemsIndexed(history, key = { _, e -> e.animeId }) { index, entry ->
                                val key = "c:${entry.animeId}"
                                val req = remember { FocusRequester() }
                                val pct = entry.progress()
                                PosterCard(
                                    title = entry.animeTitle,
                                    posterUrl = entry.posterUrl,
                                    subtitle = "Episode ${entry.lastEpisode}",
                                    progress = pct,
                                    focusRequester = req,
                                    onFocused = {
                                        lastFocused = key
                                        hero = Hero(
                                            entry.animeTitle, entry.posterUrl,
                                            if (pct > 0f) "CONTINUE · EPISODE ${entry.lastEpisode} · ${(pct * 100).toInt()}%"
                                            else "CONTINUE · EPISODE ${entry.lastEpisode}",
                                        )
                                    },
                                    onClick = { onOpen(entry.toResult()) },
                                )
                                InitialFocus(req, enabled = key == lastFocused || (lastFocused == null && index == 0))
                            }
                        }
                    }
                }
                if (favorites.isNotEmpty()) {
                    item(key = "favorites") {
                        ShelfRow("Favourites") {
                            itemsIndexed(favorites, key = { _, f -> f.animeId }) { index, fav ->
                                val key = "f:${fav.animeId}"
                                val req = remember { FocusRequester() }
                                PosterCard(
                                    title = fav.animeTitle,
                                    posterUrl = fav.posterUrl,
                                    focusRequester = req,
                                    onFocused = {
                                        lastFocused = key
                                        hero = Hero(fav.animeTitle, fav.posterUrl, "FAVOURITE")
                                    },
                                    onClick = { onOpen(AnimeResult(fav.animeId, fav.animeTitle, fav.posterUrl)) },
                                )
                                InitialFocus(
                                    req,
                                    enabled = key == lastFocused || (lastFocused == null && history.isEmpty() && index == 0),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(start = 24.dp, end = TvInsets.Horizontal, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            content = content,
        )
    }
}

// ───────────────────────────── Search ─────────────────────────────

@Composable
fun TvSearchScreen(vm: SearchViewModel, onOpen: (AnimeResult) -> Unit) {
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

    // The text field only takes focus once OK is pressed on the box, so moving past it with the
    // remote never pops the keyboard up.
    var editing by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val boxFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }

    val runSearch = {
        editing = false
        keyboard?.hide()
        if (query.isNotBlank()) {
            searched = true
            lastFocused = null
            vm.search()
        }
    }

    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a show name")
    }
    val canVoice = remember { voiceIntent.resolveActivity(context.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val said = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!said.isNullOrBlank()) {
            vm.updateQuery(said)
            searched = true
            lastFocused = null
            vm.search()
        }
    }

    BackHandler(enabled = editing) { editing = false }

    Column(Modifier.fillMaxSize().padding(top = TvInsets.Vertical)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = TvInsets.Horizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FocusSurface(
                onClick = { editing = true },
                focusRequester = boxFocus,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(28.dp),
                focusedScale = 1.02f,
                color = TvColors.SurfaceHigh,
            ) { focused ->
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = if (focused) TvColors.Text else TvColors.TextDim)
                    Spacer(Modifier.width(14.dp))
                    if (editing) {
                        var hadFocus by remember { mutableStateOf(false) }
                        BasicTextField(
                            value = query,
                            onValueChange = vm::updateQuery,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = TvColors.Text),
                            cursorBrush = SolidColor(TvColors.Accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }, onDone = { runSearch() }),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(fieldFocus)
                                .onFocusChanged {
                                    if (it.isFocused) hadFocus = true
                                    else if (hadFocus) editing = false
                                }
                                .onPreviewKeyEvent {
                                    // Down from the open field means "done typing": go to the results.
                                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                                        runSearch(); true
                                    } else false
                                },
                        )
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(16)
                            fieldFocus.tryFocus()
                            keyboard?.show()
                        }
                    } else {
                        Text(
                            query.ifBlank { "Search anime" },
                            style = MaterialTheme.typography.titleMedium,
                            color = if (query.isBlank()) TvColors.TextDim else TvColors.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (canVoice) {
                TvButton("Voice", onClick = { runCatching { voice.launch(voiceIntent) } }, icon = Icons.Default.Mic)
            }
        }

        Spacer(Modifier.height(20.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TvColors.Accent)
            }
            error != null -> CenterMessage(error!!)
            results.isEmpty() -> CenterMessage("Press OK on the search box to type, or use Voice")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(start = 24.dp, end = TvInsets.Horizontal, top = 10.dp, bottom = TvInsets.Vertical + 20.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(results, key = { _, a -> a.id }) { index, anime ->
                    val req = remember { FocusRequester() }
                    PosterCard(
                        title = anime.title,
                        posterUrl = anime.posterUrl,
                        width = null,
                        focusRequester = req,
                        onFocused = { lastFocused = anime.id },
                        onClick = { onOpen(anime) },
                    )
                    // After a fresh search jump to the first result; after Back, to the one you opened.
                    InitialFocus(
                        req,
                        key = results,
                        enabled = (lastFocused == null && index == 0) || lastFocused == anime.id,
                    )
                }
            }
        }
    }

    // Arriving on Search fresh: start on the box (Back from a show restores that result instead).
    InitialFocus(boxFocus, enabled = lastFocused == null && results.isEmpty())
    // Closing the field without searching: give focus back to the box rather than to nothing.
    var wasEditing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (wasEditing && !editing && !searched) { kotlinx.coroutines.delay(16); boxFocus.tryFocus() }
        if (editing) searched = false
        wasEditing = editing
    }
}

// ───────────────────────────── History ─────────────────────────────

@Composable
fun TvHistoryScreen(vm: HistoryViewModel, onOpen: (AnimeResult) -> Unit) {
    val history by vm.history.collectAsState()
    var confirmRemove by remember { mutableStateOf<WatchHistoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(top = TvInsets.Vertical)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = TvInsets.Horizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("History", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Hold OK on a show to remove it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvColors.TextDim,
                )
            }
            if (history.isNotEmpty()) {
                TvButton("Clear all", onClick = { confirmClear = true }, icon = Icons.Default.DeleteSweep)
            }
        }
        Spacer(Modifier.height(16.dp))

        if (history.isEmpty()) {
            CenterMessage("Nothing watched yet")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                contentPadding = PaddingValues(start = 24.dp, end = TvInsets.Horizontal, top = 10.dp, bottom = TvInsets.Vertical + 20.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(history, key = { _, e -> e.animeId }) { index, entry ->
                    val req = remember { FocusRequester() }
                    PosterCard(
                        title = entry.animeTitle,
                        posterUrl = entry.posterUrl,
                        width = null,
                        subtitle = "Episode ${entry.lastEpisode}",
                        progress = entry.progress(),
                        focusRequester = req,
                        onFocused = { lastFocused = entry.animeId },
                        onClick = { onOpen(entry.toResult()) },
                        onLongClick = { confirmRemove = entry },
                    )
                    InitialFocus(req, enabled = lastFocused == entry.animeId || (lastFocused == null && index == 0))
                }
            }
        }
    }

    confirmRemove?.let { entry ->
        ConfirmDialog(
            title = "Remove from history?",
            text = entry.animeTitle,
            confirm = "Remove",
            onConfirm = { vm.delete(entry.animeId); lastFocused = null; confirmRemove = null },
            onDismiss = { confirmRemove = null },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear all history?",
            text = "Continue watching on Home will be empty too.",
            confirm = "Clear all",
            onConfirm = { vm.clearAll(); lastFocused = null; confirmClear = false },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TvColors.Surface,
        title = { Text(title) },
        text = { Text(text, color = TvColors.TextDim) },
        confirmButton = { TvButton(confirm, onClick = onConfirm) },
        // Cancel starts focused, so a stray OK never deletes anything.
        dismissButton = { TvButton("Cancel", onClick = onDismiss, focusRequester = cancelFocus) },
    )
    InitialFocus(cancelFocus)
}
