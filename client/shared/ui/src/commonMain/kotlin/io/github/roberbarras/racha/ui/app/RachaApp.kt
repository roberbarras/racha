package io.github.roberbarras.racha.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.roberbarras.racha.domain.library.ContentId
import io.github.roberbarras.racha.domain.preferences.Settings
import io.github.roberbarras.racha.domain.preferences.ThemeMode
import io.github.roberbarras.racha.ui.common.parseColor
import io.github.roberbarras.racha.ui.design.RachaTheme
import io.github.roberbarras.racha.ui.detail.DetailScreen
import io.github.roberbarras.racha.ui.folders.FoldersScreen
import io.github.roberbarras.racha.ui.home.HomeScreen
import io.github.roberbarras.racha.ui.lists.HistoryScreen
import io.github.roberbarras.racha.ui.lists.MyListScreen
import io.github.roberbarras.racha.ui.player.PlayerPlaceholder
import io.github.roberbarras.racha.ui.search.SearchScreen
import io.github.roberbarras.racha.ui.category.CategoryScreen

/** Root of the shared UI: theme from settings, adaptive shell, navigation and keyboard shortcuts. */
@Composable
fun RachaApp(deps: RachaDependencies, navController: NavHostController = rememberNavController()) {
    val app = viewModel { AppViewModel(deps) }
    val appearance by app.appearance.collectAsStateWithLifecycle()
    val dark = when (appearance.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val accent = appearance.accent.takeIf { it != Settings.AccentColor.default }?.let(::parseColor)
    RachaTheme(dark = dark, accent = accent, fontScale = appearance.fontScale.toFloat()) {
        val home by app.home.collectAsStateWithLifecycle()
        val scan by app.scan.collectAsStateWithLifecycle()
        val entry by navController.currentBackStackEntryAsState()
        val destination = entry?.destination
        val current = when {
            destination == null -> null
            destination.hasRoute(HomeRoute::class) -> Destination.Home
            destination.hasRoute(MyListRoute::class) -> Destination.MyList
            destination.hasRoute(HistoryRoute::class) -> Destination.History
            destination.hasRoute(SearchRoute::class) -> Destination.Search
            destination.hasRoute(FoldersRoute::class) -> Destination.Folders
            else -> null
        }
        fun go(d: Destination) {
            val route: Any = when (d) {
                Destination.Home -> HomeRoute
                Destination.MyList -> MyListRoute
                Destination.History -> HistoryRoute
                Destination.Search -> SearchRoute
                Destination.Folders -> FoldersRoute
            }
            navController.navigate(route) {
                popUpTo(HomeRoute) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        val openItem: (ContentId) -> Unit = { navController.navigate(ItemRoute(it.value)) }
        val play: (ContentId, Boolean) -> Unit = { id, fromStart -> navController.navigate(PlayerRoute(id.value, fromStart)) }
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.K -> { go(Destination.Search); true }
                    Key.R -> { app.scanNow(); true }
                    else -> false
                }
            },
        ) {
            RachaShell(
                current = current,
                onNavigate = ::go,
                categories = home?.categories.orEmpty(),
                onCategory = { navController.navigate(CategoryRoute(it.category.key.value)) },
                streakDays = home?.streakDays ?: 0,
                scan = scan,
                onScan = { app.scanNow() },
            ) {
                NavHost(navController, startDestination = HomeRoute) {
                    composable<HomeRoute> { HomeScreen(app, deps, openItem, play, onAddFolder = { go(Destination.Folders) }) }
                    composable<CategoryRoute> { CategoryScreen(deps, it.toRoute<CategoryRoute>().key, onBack = navController::navigateUp, onOpenItem = openItem) }
                    composable<ItemRoute> { DetailScreen(deps, ContentId(it.toRoute<ItemRoute>().id), onBack = navController::navigateUp, onPlay = play) }
                    composable<MyListRoute> { MyListScreen(deps, openItem) }
                    composable<HistoryRoute> { HistoryScreen(deps, openItem) }
                    composable<SearchRoute> { SearchScreen(deps, openItem) }
                    composable<FoldersRoute> { FoldersScreen(deps, app) }
                    composable<PlayerRoute> {
                        val route = it.toRoute<PlayerRoute>()
                        PlayerPlaceholder(deps, ContentId(route.elementId), route.fromStart, onBack = navController::navigateUp)
                    }
                }
            }
        }
    }
}
