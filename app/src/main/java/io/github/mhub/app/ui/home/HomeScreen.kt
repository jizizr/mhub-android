package io.github.mhub.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mhub.app.R
import io.github.mhub.app.ui.LocalBottomChrome
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.app.ui.LocalNavigator
import io.github.mhub.app.ui.Route
import io.github.mhub.app.ui.pressScale
import io.github.mhub.app.ui.components.Artwork
import io.github.mhub.app.ui.components.CollectionHeader
import io.github.mhub.app.ui.components.EmptyState
import io.github.mhub.app.ui.components.plainBackground
import io.github.mhub.app.ui.components.TrackMenu
import io.github.mhub.app.ui.components.TrackRow
import io.github.mhub.core.Collection
import io.github.mhub.core.RecommendationHome
import io.github.mhub.core.Track
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioIconButton
import io.github.neoruaa.glasmio.ui.GlasmioScrollableTabRow
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(listState: LazyListState) {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val recent by graph.model.recent.collectAsStateWithLifecycle()
    var home by remember { mutableStateOf<RecommendationHome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var selectedTag by remember { mutableStateOf("") }
    var seen by remember { mutableStateOf(listOf<String>()) }

    fun load(refresh: Boolean) {
        val client = graph.model.client ?: return
        if (loading) return
        loading = true
        scope.launch {
            runCatching { client.home(refresh = refresh, exclude = if (refresh) seen else emptyList()) }
                .onSuccess {
                    home = it
                    error = null
                    seen = (seen + it.tracks.map { item -> item.id }).takeLast(300)
                }
                .onFailure { error = it.message }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (home == null) load(false) }

    GlasmioPinnedListPage(
        title = stringResource(R.string.home),
        listState = listState,
        bottomPadding = LocalBottomChrome.current,
        backgroundColor = plainBackground(),
        // Sections scroll edge to edge; the large title keeps the page inset.
        horizontalContentPadding = 0.dp,
        largeTitleHorizontalPadding = 20.dp,
        verticalArrangement = Arrangement.spacedBy(28.dp),
        actions = {
            GlasmioIconButton(onClick = { load(true) }, enabled = !loading) { SfIcon(SfSymbol.ArrowClockwise, stringResource(R.string.refresh)) }
            GlasmioIconButton(onClick = { navigator.push(Route.Settings) }) { SfIcon(SfSymbol.PersonFilled, stringResource(R.string.settings)) }
        },
    ) {
        val data = home
        when {
            data != null -> {
                if (data.tags.isNotEmpty()) item("tags") {
                    // Same as iOS: tags filter the playlists; tapping the chosen tag again clears it.
                    GlasmioScrollableTabRow(
                        items = data.tags.map { it.id to it.label },
                        selected = selectedTag,
                        onSelected = { selectedTag = if (it == selectedTag) "" else it },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                val tag = data.tags.firstOrNull { it.id == selectedTag }
                val playlists = if (tag == null) data.playlists else data.playlists.filter { it.id in tag.playlistIds }
                if (playlists.isNotEmpty()) item("playlists") {
                    Section(stringResource(R.string.recommended_playlists)) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(playlists, key = { it.id }) { playlist ->
                                Card(playlist.cover, playlist.name, playlist.curator, 170) {
                                    navigator.push(Route.Collection("applemusic", playlist.url, playlist.name, playlist.cover))
                                }
                            }
                        }
                    }
                }
                if (data.tracks.isNotEmpty()) item("songs") {
                    val tracks = data.tracks.map { it.track }
                    Section(stringResource(R.string.recommended_songs), trailing = {
                        GlasmioIconButton(onClick = { graph.player.play(tracks) }) { SfIcon(SfSymbol.PlayFilled, stringResource(R.string.play)) }
                    }) {
                        LazyHorizontalGrid(
                            rows = GridCells.Fixed(4),
                            modifier = Modifier.height(60.dp * 4),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(tracks, key = { it.id }) { TrackRow(it, Modifier.width(300.dp)) }
                        }
                    }
                }
            }
            error != null -> item("error") {
                EmptyState(stringResource(R.string.home_load_failed), error, "wifi.exclamationmark", stringResource(R.string.retry) to { load(false) })
            }
            else -> item("loading") { EmptyState(stringResource(R.string.loading), symbol = "sparkles") }
        }
        if (recent.isNotEmpty()) item("recent") {
            Section(stringResource(R.string.recently_played)) {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(recent, key = { it.id }) { track ->
                        Card(track.cover, track.title, track.artist, 130, menuTrack = track) { graph.player.playNow(track) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = IosTypography.title2, fontWeight = FontWeight.Bold, color = LocalGlassColors.current.content, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

@Composable
private fun Card(cover: String, title: String, subtitle: String, size: Int, menuTrack: Track? = null, onClick: () -> Unit) {
    val colors = LocalGlassColors.current
    Column(Modifier.width(size.dp).pressScale(onClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Artwork(cover, Modifier.size(size.dp), corner = 10.dp)
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = IosTypography.subheadline, color = colors.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = IosTypography.footnote, color = colors.secondaryContent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (menuTrack != null) Box(Modifier.padding(top = 0.dp)) { TrackMenu(listOf(menuTrack)) }
        }
    }
}

/** A remote playlist or album: play it, or save it to the library. */
@Composable
fun CollectionScreen(route: Route.Collection) {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    var collection by remember { mutableStateOf<Collection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val untitled = stringResource(R.string.untitled_playlist)
    LaunchedEffect(route) {
        val client = graph.model.client ?: return@LaunchedEffect
        runCatching { client.collection(route.platform, route.url) }.onSuccess { collection = it }.onFailure { error = it.message }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    GlasmioPinnedListPage(
        title = collection?.title ?: route.title,
        showsLargeTitle = false,
        bottomPadding = LocalBottomChrome.current,
        backgroundColor = plainBackground(),
        onNavigateBack = { navigator.pop() },
        navigateBackContentDescription = stringResource(R.string.back),
        actions = {
            collection?.let { loaded ->
                GlasmioIconButton(onClick = {
                    val playlist = graph.library.create(loaded.title, untitled, loaded.items.map { it.track }, loaded.sourceUrl, loaded.platform)
                    graph.model.show(context.getString(R.string.imported, playlist.name, playlist.tracks.size))
                }) { SfIcon("plus", stringResource(R.string.add_all_to_library)) }
            }
        },
    ) {
        val loaded = collection
        item("header") {
            CollectionHeader(
                loaded?.title ?: route.title, route.cover,
                loaded?.let { context.getString(R.string.songs_count, it.items.size) },
                loaded?.items?.map { it.track },
            )
        }
        when {
            loaded != null -> items(loaded.items.map { it.track }, key = { it.id }) { TrackRow(it, separator = true) }
            error != null -> item("error") { EmptyState(stringResource(R.string.home_load_failed), error, "exclamationmark.triangle") }
            else -> item("loading") { EmptyState(stringResource(R.string.loading), symbol = "sparkles") }
        }
    }
}
