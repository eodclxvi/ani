package com.anistream.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.anistream.ui.screens.AnimeDetailScreen
import com.anistream.ui.screens.HistoryScreen
import com.anistream.ui.screens.PlayerScreen
import com.anistream.ui.screens.SearchScreen
import com.anistream.viewmodel.AnimeDetailViewModel
import com.anistream.viewmodel.HistoryViewModel
import com.anistream.viewmodel.SearchViewModel
import androidx.compose.ui.Modifier
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val SEARCH = "search"
    const val HISTORY = "history"
    const val DETAIL = "detail/{animeId}/{title}?poster={poster}"
    const val PLAYER = "player"

    fun detail(animeId: String, title: String, posterUrl: String?): String {
        val id = URLEncoder.encode(animeId, "UTF-8")
        val enc = URLEncoder.encode(title, "UTF-8")
        val poster = posterUrl?.let { URLEncoder.encode(it, "UTF-8") } ?: ""
        return "detail/$id/$enc?poster=$poster"
    }
}

@Composable
fun AniNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    detailViewModel: AnimeDetailViewModel = viewModel(),
) {
    NavHost(navController = navController, startDestination = Routes.SEARCH, modifier = modifier) {

        composable(Routes.SEARCH) {
            val vm: SearchViewModel = viewModel()
            SearchScreen(
                viewModel = vm,
                onAnimeClick = { anime ->
                    navController.navigate(Routes.detail(anime.id, anime.title, anime.posterUrl))
                },
            )
        }

        composable(Routes.HISTORY) {
            val vm: HistoryViewModel = viewModel()
            HistoryScreen(
                viewModel = vm,
                onAnimeClick = { entry ->
                    navController.navigate(Routes.detail(entry.animeId, entry.animeTitle, entry.posterUrl))
                },
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(
                navArgument("animeId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                navArgument("poster") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStack ->
            val animeId = URLDecoder.decode(backStack.arguments!!.getString("animeId")!!, "UTF-8")
            val title = URLDecoder.decode(backStack.arguments!!.getString("title")!!, "UTF-8")
            val poster = backStack.arguments!!.getString("poster")
                ?.let { if (it.isBlank()) null else URLDecoder.decode(it, "UTF-8") }

            AnimeDetailScreen(
                animeId = animeId,
                animeTitle = title,
                posterUrl = poster,
                viewModel = detailViewModel,
                onBack = { navController.popBackStack() },
                onPlayEpisode = { navController.navigate(Routes.PLAYER) },
            )
        }

        composable(Routes.PLAYER) {
            PlayerScreen(
                viewModel = detailViewModel,
                onDismiss = { navController.popBackStack() },
            )
        }
    }
}
