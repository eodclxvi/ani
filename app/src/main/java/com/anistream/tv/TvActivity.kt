package com.anistream.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.anistream.data.AnimeResult
import com.anistream.ui.navigation.Routes
import com.anistream.viewmodel.AnimeDetailViewModel
import com.anistream.viewmodel.HistoryViewModel
import com.anistream.viewmodel.SearchViewModel
import java.net.URLDecoder

/** True on Google TV / Android TV, where the phone layout is unusable with a remote. */
fun Context.isTelevision(): Boolean {
    val uiMode = (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
    return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/** The TV entry point (LEANBACK_LAUNCHER): a remote-first layout over the same data as the phone app. */
class TvActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.anistream.data.PlaybackPrefs.load(this)
        setContent { TvTheme { TvApp() } }
    }
}

private object TvRoutes {
    const val HOME = "home"
    const val SEARCH = Routes.SEARCH
    const val HISTORY = Routes.HISTORY
}

private enum class RailTab(val route: String, val label: String, val icon: ImageVector) {
    Home(TvRoutes.HOME, "Home", Icons.Default.Home),
    Search(TvRoutes.SEARCH, "Search", Icons.Default.Search),
    History(TvRoutes.HISTORY, "History", Icons.Default.History),
}

private val RailCollapsed = 72.dp

@Composable
private fun TvApp() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tab = RailTab.entries.firstOrNull { it.route == route }
    // One detail ViewModel for the whole activity: the player reads the stream it loaded.
    val detailVm: AnimeDetailViewModel = viewModel()
    // Search keeps its query and results for the whole session, whichever tab you go to in between.
    val searchVm: SearchViewModel = viewModel()

    val openAnime: (AnimeResult) -> Unit = { nav.navigate(Routes.detail(it.id, it.title, it.posterUrl)) }

    // Back on Search or History goes Home first; Back on Home leaves the app.
    BackHandler(enabled = tab != null && tab != RailTab.Home) { nav.switchTab(TvRoutes.HOME) }

    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        NavHost(
            navController = nav,
            startDestination = TvRoutes.HOME,
            modifier = Modifier.fillMaxSize().padding(start = if (tab != null) RailCollapsed else 0.dp),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable(TvRoutes.HOME) {
                TvHomeScreen(searchVm, onOpen = openAnime, onSearch = { nav.switchTab(TvRoutes.SEARCH) })
            }
            composable(TvRoutes.SEARCH) {
                TvSearchScreen(searchVm, onOpen = openAnime)
            }
            composable(TvRoutes.HISTORY) {
                val vm: HistoryViewModel = viewModel()
                TvHistoryScreen(vm, onOpen = openAnime)
            }
            composable(
                route = Routes.DETAIL,
                arguments = listOf(
                    navArgument("animeId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val args = entry.arguments!!
                val animeId = URLDecoder.decode(args.getString("animeId")!!, "UTF-8")
                val title = URLDecoder.decode(args.getString("title")!!, "UTF-8")
                val poster = args.getString("poster")?.takeIf { it.isNotBlank() }?.let { URLDecoder.decode(it, "UTF-8") }
                TvDetailScreen(
                    animeId = animeId,
                    animeTitle = title,
                    posterUrl = poster,
                    vm = detailVm,
                    onPlay = { nav.navigate(Routes.PLAYER) },
                )
            }
            composable(Routes.PLAYER) {
                TvPlayerScreen(vm = detailVm, onExit = { nav.popBackStack() })
            }
        }

        if (tab != null) {
            TvRail(selected = tab, onSelect = { nav.switchTab(it.route) })
        }
    }
}

private fun NavHostController.switchTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(TvRoutes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Icons only until the remote moves onto it, then it widens over the content and shows labels. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvRail(selected: RailTab, onSelect: (RailTab) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 210.dp else RailCollapsed, label = "rail-width")
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (expanded) Brush.horizontalGradient(0f to TvColors.Background, 0.75f to TvColors.Background.copy(alpha = 0.96f), 1f to Color.Transparent)
                else Brush.horizontalGradient(listOf(TvColors.Background, TvColors.Background))
            )
            .onFocusChanged { expanded = it.hasFocus }
            .focusRestorer()
            .focusGroup()
            .padding(vertical = TvInsets.Vertical, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            if (expanded) "Ani" else "A",
            color = TvColors.Accent,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(start = 12.dp, bottom = 18.dp),
        )
        RailTab.entries.forEach { item ->
            val isSelected = item == selected
            FocusSurface(
                onClick = { onSelect(item) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(24.dp),
                focusedScale = 1f,
                color = Color.Transparent,
                focusedColor = TvColors.Focus,
            ) { focused ->
                val tint = when {
                    focused -> TvColors.Background
                    isSelected -> TvColors.Accent
                    else -> TvColors.TextDim
                }
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(24.dp))
                    if (expanded) {
                        Spacer(Modifier.width(14.dp))
                        Text(item.label, color = tint, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(1.dp))
    }
}
