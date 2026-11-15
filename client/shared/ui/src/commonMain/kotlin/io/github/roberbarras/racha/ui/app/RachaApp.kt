package io.github.roberbarras.racha.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.roberbarras.racha.ui.account.AccountScreen
import io.github.roberbarras.racha.ui.stats.StatsScreen
import io.github.roberbarras.racha.ui.home.HomeScreen
import io.github.roberbarras.racha.ui.lists.HistoryScreen
import io.github.roberbarras.racha.ui.lists.MyListScreen
import io.github.roberbarras.racha.ui.player.PlayerPlaceholder
import io.github.roberbarras.racha.ui.player.PlayerScreen
import io.github.roberbarras.racha.ui.resource.ResourceScreen
import io.github.roberbarras.racha.domain.library.ElementKind
import io.github.roberbarras.racha.domain.playback.StartMode
import io.github.roberbarras.racha.ui.search.SearchScreen
import io.github.roberbarras.racha.ui.category.CategoryScreen

/** Root of the shared UI: theme from settings, adaptive shell, navigation and keyboard shortcuts. */
@Composable
fun RachaApp(deps: RachaDependencies, navController: NavHostController = rememberNavController(), launchAnimation: Boolean = false) {
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
        var showLaunch by rememberSaveable { mutableStateOf(launchAnimation) }
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
            destination.hasRoute(AccountRoute::class) -> Destination.Account
            destination.hasRoute(StatsRoute::class) -> Destination.Stats
            else -> null
        }
        val navScope = rememberCoroutineScope()
        fun go(d: Destination) {
            val route: Any = when (d) {
                Destination.Home -> HomeRoute
                Destination.MyList -> MyListRoute
                Destination.History -> HistoryRoute
                Destination.Search -> SearchRoute
                Destination.Folders -> FoldersRoute
                Destination.Account -> AccountRoute
                Destination.Stats -> StatsRoute
            }
            // Main explicitly, like [play]: NavController must only be touched from the main thread.
            navScope.launch(Dispatchers.Main.immediate) {
                navController.navigate(route) {
                    popUpTo(HomeRoute) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
        // Account and sync only where the platform offers it.
        val destinations = remember(deps.sync) { Destination.entries.filter { it != Destination.Account || deps.sync != null } }
        val openItem: (ContentId) -> Unit = { navController.navigate(ItemRoute(it.value)) }
        // Elements open where they belong: resources in the viewer, videos in the player (one navigation, so the
        // screen transition never gets interrupted by a redirect).
        val play: (ContentId, StartMode) -> Unit = { id, mode ->
            // Main explicitly: NavController is not thread-safe, and the lookup must not decide where we resume.
            navScope.launch(Dispatchers.Main.immediate) {
                val resource = deps.getElement(id)?.element?.kind == ElementKind.RESOURCE
                // Single top: a double tap (or a tap while the transition runs) must not stack the same screen twice.
                navController.navigate(if (resource) ResourceRoute(id.value) else PlayerRoute(id.value, mode)) { launchSingleTop = true }
            }
        }
        val immersive = destination != null && (destination.hasRoute(PlayerRoute::class) || destination.hasRoute(ResourceRoute::class))
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
                immersive = immersive,
                destinations = destinations,
            ) {
                NavHost(navController, startDestination = HomeRoute) {
                    composable<HomeRoute> { HomeScreen(app, deps, openItem, play, onAddFolder = { go(Destination.Folders) }, onOpenAccount = { go(Destination.Account) }) }
                    composable<CategoryRoute> { CategoryScreen(deps, it.toRoute<CategoryRoute>().key, onBack = navController::navigateUp, onOpenItem = openItem) }
                    composable<ItemRoute> { DetailScreen(deps, ContentId(it.toRoute<ItemRoute>().id), onBack = navController::navigateUp, onPlay = play) }
                    composable<MyListRoute> { MyListScreen(deps, openItem) }
                    composable<HistoryRoute> { HistoryScreen(deps, openItem) }
                    composable<SearchRoute> { SearchScreen(deps, openItem) }
                    composable<FoldersRoute> { FoldersScreen(deps, app) }
                    composable<AccountRoute> { deps.sync?.let { AccountScreen(deps, it) } }
                    composable<StatsRoute> {
                        StatsScreen(deps, onFolders = { go(Destination.Folders) }, onAccount = deps.sync?.let { { go(Destination.Account) } })
                    }
                    composable<PlayerRoute> {
                        val route = it.toRoute<PlayerRoute>()
                        val element = ContentId(route.elementId)
                        val player = deps.platform.player
                        if (player == null) {
                            PlayerPlaceholder(deps, element, route.mode, onBack = navController::navigateUp)
                        } else {
                            PlayerScreen(
                                deps, player, element, route.mode,
                                onBack = navController::navigateUp,
                                onOpenResource = { resource ->
                                    navController.navigate(ResourceRoute(resource.id.value)) { popUpTo<PlayerRoute> { inclusive = true } }
                                },
                            )
                        }
                    }
                    composable<ResourceRoute> {
                        ResourceScreen(
                            deps, ContentId(it.toRoute<ResourceRoute>().elementId),
                            onBack = navController::navigateUp,
                            onOpenElement = { target ->
                                val route: Any = if (target.kind == ElementKind.VIDEO) PlayerRoute(target.id.value, StartMode.RESUME) else ResourceRoute(target.id.value)
                                navController.navigate(route) { popUpTo<ResourceRoute> { inclusive = true } }
                            },
                        )
                    }
                }
            }
            if (showLaunch) LaunchAnimation(contentReady = home != null, onFinished = { showLaunch = false })
        }
    }
}
