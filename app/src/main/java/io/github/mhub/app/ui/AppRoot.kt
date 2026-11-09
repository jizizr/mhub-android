package io.github.mhub.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mhub.app.AppGraph
import io.github.mhub.app.R
import io.github.mhub.app.graph
import io.github.mhub.app.ui.home.CollectionScreen
import io.github.mhub.app.ui.home.HomeScreen
import io.github.mhub.app.ui.library.CacheScreen
import io.github.mhub.app.ui.library.LibraryScreen
import io.github.mhub.app.ui.library.PlaylistScreen
import io.github.mhub.app.ui.player.FullPlayer
import io.github.mhub.app.ui.player.MiniPlayer
import io.github.mhub.app.ui.player.Accessory
import io.github.mhub.app.ui.player.AccessoryMotion
import io.github.mhub.app.ui.player.rememberAccessoryMotion
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt
import io.github.mhub.app.ui.components.ToastPill
import io.github.mhub.app.ui.search.SearchScreen
import io.github.mhub.app.ui.settings.ServerSetupScreen
import io.github.mhub.app.ui.settings.SettingsScreen
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.glass.GlasmioBackdropProvider
import io.github.neoruaa.glasmio.glass.glassBackdropSource
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import io.github.neoruaa.glasmio.glass.trackBackdropPosition
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.scaffold.glasmioNavigationScrollHint
import io.github.neoruaa.glasmio.scaffold.rememberGlasmioNavigationScrollState
import io.github.neoruaa.glasmio.ui.CompactBottomControlSize
import io.github.neoruaa.glasmio.scaffold.GlasmioNavigationBarWithTrailingItem
import io.github.neoruaa.glasmio.ui.GlasmioTabItem
import io.github.neoruaa.glasmio.ui.NavigationBarSurfaceHeight

enum class Tab { Home, Library, Search }

/** Pages pushed on top of a tab's root, like iOS NavigationStack destinations. */
sealed interface Route {
    data class Playlist(val id: String) : Route
    data class Collection(val platform: String, val url: String, val title: String, val cover: String) : Route
    data object Settings : Route
    data object Cache : Route
}

/** One back stack per tab, as on iOS. */
class Navigator {
    var tab by mutableStateOf(Tab.Home)
    private val stacks = Tab.entries.associateWith { mutableStateListOf<Route>() }
    val stack: List<Route> get() = stacks.getValue(tab)
    val top: Route? get() = stack.lastOrNull()

    /** What is on screen: the tab and the top route, with the stack depth. */
    val page: Page get() = Page(tab, top, stack.size)
    /** The page a back gesture would reveal. */
    val previousPage: Page? get() = if (stack.isEmpty()) null else Page(tab, stack.getOrNull(stack.size - 2), stack.size - 1)

    fun push(route: Route) { stacks.getValue(tab).add(route) }
    fun pop(): Boolean = stacks.getValue(tab).removeLastOrNull() != null
    fun select(next: Tab) {
        // Re-selecting the current tab returns to its root, as on iOS.
        if (next == tab) stacks.getValue(tab).clear() else tab = next
    }
}

data class Page(val tab: Tab, val route: Route?, val depth: Int)

/** iOS push/pop: critically damped, about half a second. */
private fun <T> pushSpring(visibilityThreshold: T? = null) = spring(dampingRatio = 1f, stiffness = 320f, visibilityThreshold = visibilityThreshold)

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
val LocalGraph = staticCompositionLocalOf<AppGraph> { error("No graph") }

/** Space the bottom chrome (mini player and tab bar) covers; pages pad their lists with it. */
val LocalBottomChrome = staticCompositionLocalOf { 0.dp }

private val MiniPlayerHeight = io.github.mhub.app.ui.player.Accessory.Height

@Composable
fun AppRoot() {
    val graph = LocalContext.current.graph
    val server by graph.settings.serverAddress.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalGraph provides graph) {
        // Read the collected value here so a newly saved address recomposes this branch.
        if (io.github.mhub.core.MHubClient.serverUrl(server) == null) {
            ServerSetupScreen()
        } else {
            LaunchedEffect(server) { graph.model.refreshPlatforms() }
            MainScaffold()
        }
        Toast()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MainScaffold() {
    val navigator = remember { Navigator() }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    val rootLists = remember { mutableMapOf<Tab, LazyListState>() }
    val homeList = rememberLazyListState()
    val libraryList = rememberLazyListState()
    val searchList = rememberLazyListState()
    rootLists[Tab.Home] = homeList
    rootLists[Tab.Library] = libraryList
    rootLists[Tab.Search] = searchList
    val atRoot = navigator.top == null
    val navScroll = rememberGlasmioNavigationScrollState(listState = rootLists.getValue(navigator.tab), enabled = atRoot && !fullPlayer)
    val chromeBackdrop = rememberLayerBackdrop()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomChrome: Dp = bottomInset + NavigationBarSurfaceHeight + MiniPlayerHeight + 24.dp

    // Pages follow the navigator; the system back gesture scrubs the pop
    // transition with the finger (predictive back), like iOS swipe-back.
    val pages = remember { SeekableTransitionState(navigator.page) }
    val target = navigator.page
    LaunchedEffect(target) {
        if (pages.currentState != target || pages.targetState != target) pages.animateTo(target, pushSpring())
    }
    BackHandler(enabled = fullPlayer) { fullPlayer = false }
    PredictiveBackHandler(enabled = !fullPlayer && !atRoot) { events ->
        val previous = navigator.previousPage ?: return@PredictiveBackHandler
        try {
            events.collect { pages.seekTo(it.progress, previous) }
            navigator.pop()
        } catch (cancelled: CancellationException) {
            pages.animateTo(navigator.page, pushSpring())
            throw cancelled
        }
    }

    SharedTransitionLayout {
    CompositionLocalProvider(LocalNavigator provides navigator, LocalBottomChrome provides bottomChrome, LocalSharedScope provides this) {
        Box(Modifier.fillMaxSize()) {
            // Page layer, recorded once so the bottom chrome can refract it.
            Box(
                Modifier.fillMaxSize()
                    .glassBackdropSource(chromeBackdrop)
                    .trackBackdropPosition(chromeBackdrop)
                    .nestedScroll(navScroll.nestedScrollConnection),
            ) {
                val background = LocalGlassColors.current.groupedBackground
                GlasmioBackdropHost(
                    modifier = Modifier.fillMaxSize(),
                    sampledContent = { Box(Modifier.fillMaxSize().background(background)) },
                    overlayContent = {
                        rememberTransition(pages, label = "pages").AnimatedContent(
                            transitionSpec = {
                                val sameTab = initialState.tab == targetState.tab
                                val forward = targetState.depth > initialState.depth
                                val slide = pushSpring(IntOffset.VisibilityThreshold)
                                when {
                                    !sameTab -> fadeIn(tween(220)) togetherWith fadeOut(tween(160))
                                    // Incoming page slides over; the one below drifts a third and dims.
                                    forward -> (slideInHorizontally(slide) { it }) togetherWith
                                        (slideOutHorizontally(slide) { -it / 3 } + fadeOut(pushSpring(), targetAlpha = 0.7f))
                                    else -> (slideInHorizontally(slide) { -it / 3 } + fadeIn(pushSpring(), initialAlpha = 0.7f)) togetherWith
                                        (slideOutHorizontally(slide) { it })
                                }.apply { targetContentZIndex = if (forward) targetState.depth.toFloat() else initialState.depth.toFloat() - 1f }
                            },
                            contentKey = { it },
                        ) { page ->
                            Box(Modifier.fillMaxSize().shadow(if (page.depth > 0) 18.dp else 0.dp)) {
                                when (val route = page.route) {
                                    null -> when (page.tab) {
                                        Tab.Home -> HomeScreen(homeList)
                                        Tab.Library -> LibraryScreen(libraryList)
                                        Tab.Search -> SearchScreen(searchList)
                                    }
                                    is Route.Playlist -> PlaylistScreen(route.id)
                                    is Route.Collection -> CollectionScreen(route)
                                    Route.Settings -> SettingsScreen()
                                    Route.Cache -> CacheScreen()
                                }
                            }
                        }
                    },
                )
            }

            // Bottom chrome: the mini player sits on top of the Liquid Glass tab bar.
            GlasmioBackdropProvider(chromeBackdrop) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().glasmioNavigationScrollHint(navScroll.scrollHintProgress),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The capsule expands into the full player (shared bounds). When the
                    // tab bar minimizes it drops into the tab bar row, like the iOS
                    // inline accessory.
                    val accessory = rememberAccessoryMotion(navScroll.isCompact)
                    Box(
                        Modifier.fillMaxWidth().height(MiniPlayerHeight)
                            .accessoryPlacement(accessory),
                    ) {
                        androidx.compose.animation.AnimatedVisibility(!fullPlayer, enter = fadeIn(tween(250, delayMillis = 120)), exit = fadeOut(tween(120))) {
                            MiniPlayer(
                                onOpen = { fullPlayer = true },
                                modifier = Modifier.fillMaxWidth(),
                                height = MiniPlayerHeight,
                                visibility = this,
                                inline = accessory.horizontalProgress,
                            )
                        }
                    }
                    GlasmioNavigationBarWithTrailingItem(
                        items = listOf(
                            GlasmioTabItem(Tab.Home, stringResource(R.string.home), if (navigator.tab == Tab.Home) SfSymbol.HouseFilled else SfSymbol.House),
                            GlasmioTabItem(Tab.Library, stringResource(R.string.library), SfSymbol.MusicNoteSquareStackFilled),
                        ),
                        trailingItem = GlasmioTabItem(Tab.Search, stringResource(R.string.search), SfSymbol.Search),
                        selectedKey = navigator.tab,
                        onSelected = navigator::select,
                        onExpand = navScroll::expand,
                        compactProgress = navScroll.compactProgress,
                        compactSize = CompactBottomControlSize,
                        // Search sits inside the tab capsule, as on the iPhone.
                        detachTrailingItem = false,
                        backdrop = chromeBackdrop,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp).navigationBarsPadding(),
                    )
                }
            }

            FullPlayer(visible = fullPlayer, onDismiss = { fullPlayer = false })
        }
    }
    }
}

@Composable
private fun Toast() {
    val graph = LocalGraph.current
    val message by graph.model.toast.collectAsStateWithLifecycle()
    androidx.compose.animation.AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + androidx.compose.animation.slideInVertically { -it },
        exit = fadeOut() + androidx.compose.animation.slideOutVertically { -it },
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            ToastPill(message.orEmpty(), onClick = graph.model::dismissToast)
        }
    }
}

/** Narrows the accessory towards the inline slot and moves it down into the tab bar row. */
private fun Modifier.accessoryPlacement(motion: AccessoryMotion): Modifier = this
    .layout { measurable, constraints ->
        val h = motion.horizontalProgress.value
        val margin = (Accessory.Margin + (Accessory.InlineMargin - Accessory.Margin) * h).roundToPx()
        val width = (constraints.maxWidth - margin * 2).coerceAtLeast(0)
        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
        layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(margin, 0) }
    }
    .graphicsLayer { translationY = Accessory.InlineTravel.toPx() * motion.verticalProgress.value }
