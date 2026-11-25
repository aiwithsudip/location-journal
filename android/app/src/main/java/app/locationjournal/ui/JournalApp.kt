package app.locationjournal.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.locationjournal.R

private data class BottomItem(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun JournalApp() {
    val viewModel: JournalViewModel = hiltViewModel()
    val settings by viewModel.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsState(initial = null)
    val nav = rememberNavController()

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it) } }

    JournalTheme(settings.theme) {
        val backStack by nav.currentBackStackEntryAsState()
        val current = backStack?.destination?.route
        LaunchedEffect(settings.onboardingComplete, current) {
            if (settings.onboardingComplete && current == "onboarding") {
                nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
            }
        }
        val items = listOf(
            BottomItem("home", "Home") { Icon(app.locationjournal.ui.JournalIcons.Home, null) },
            BottomItem("map", "Map") { Icon(app.locationjournal.ui.JournalIcons.Map, null) },
            BottomItem("timeline", "Timeline") { Icon(app.locationjournal.ui.JournalIcons.List, null) },
            BottomItem("places", "Places") { Icon(app.locationjournal.ui.JournalIcons.Place, null) },
            BottomItem("reels", "Reels") { Icon(app.locationjournal.ui.JournalIcons.Play, null) },
        )
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (items.any { it.route == current }) {
                    NavigationBar {
                        items.forEach { item ->
                            NavigationBarItem(
                                selected = current == item.route,
                                onClick = {
                                    nav.navigate(item.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = item.icon,
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = nav,
                startDestination = if (settings.onboardingComplete) "home" else "onboarding",
                modifier = Modifier.padding(padding),
            ) {
                composable("onboarding") { OnboardingScreen(viewModel, nav) }
                composable("home") { HomeScreen(viewModel, nav) }
                composable("map") { MapScreen(viewModel) }
                composable("timeline") { TimelineScreen(viewModel, nav) }
                composable("places") { PlacesScreen(viewModel, nav) }
                composable("photos") { PhotosScreen(viewModel) }
                composable("journal") { JournalEntriesScreen(viewModel) }
                composable("insights") { InsightsScreen(viewModel) }
                composable("settings") { SettingsScreen(viewModel, nav) }
                composable("reels") { ReelsScreen(viewModel) }
                composable(
                    "place/{placeId}",
                    arguments = listOf(navArgument("placeId") { type = NavType.StringType }),
                ) { entry -> PlaceDetailScreen(viewModel, entry.arguments!!.getString("placeId")!!, nav) }
            }
        }
    }
}
