package com.anistream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.anistream.ui.navigation.AniNavGraph
import com.anistream.ui.navigation.Routes
import com.anistream.ui.theme.AniStreamTheme
import com.anistream.ui.theme.ThemeMode
import com.anistream.ui.theme.ThemePreference
import com.anistream.tv.isTelevision

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On a TV (e.g. opened from Settings > Apps), use the remote-friendly app instead.
        if (isTelevision()) {
            startActivity(android.content.Intent(this, com.anistream.tv.TvActivity::class.java))
            finish()
            return
        }
        ThemePreference.load(this)
        enableEdgeToEdge()

        setContent {
            AniStreamTheme {
                val navController = rememberNavController()
                val backStack by navController.currentBackStackEntryAsState()
                val currentRoute = backStack?.destination?.route
                val context = LocalContext.current

                val showBars = currentRoute in listOf(Routes.SEARCH, Routes.HISTORY)

                Scaffold(
                    topBar = {
                        if (showBars) {
                            TopAppBar(
                                title = { Text("Ani") },
                                actions = {
                                    IconButton(onClick = { ThemePreference.cycle(context) }) {
                                        Icon(
                                            imageVector = when (ThemePreference.mode) {
                                                ThemeMode.Dark -> Icons.Default.DarkMode
                                                ThemeMode.Light -> Icons.Default.LightMode
                                                ThemeMode.System -> Icons.Default.Settings
                                            },
                                            contentDescription = "Theme: ${ThemePreference.mode.name}",
                                        )
                                    }
                                },
                            )
                        }
                    },
                    bottomBar = {
                        if (showBars) {
                            NavigationBar {
                                BottomTab.entries.forEach { tab ->
                                    NavigationBarItem(
                                        selected = currentRoute == tab.route,
                                        onClick = {
                                            if (currentRoute != tab.route) {
                                                navController.navigate(tab.route) {
                                                    popUpTo(Routes.SEARCH) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { innerPadding ->
                    AniNavGraph(
                        navController = navController,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

private enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    Search(Routes.SEARCH, "Search", Icons.Default.Search),
    History(Routes.HISTORY, "History", Icons.Default.History),
}
