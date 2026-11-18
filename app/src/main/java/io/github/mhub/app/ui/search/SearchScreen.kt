package io.github.mhub.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mhub.app.R
import io.github.mhub.app.ui.LocalBottomChrome
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.app.ui.catalog.CatalogShelf
import io.github.mhub.app.ui.catalog.SectionTitle
import io.github.mhub.app.ui.catalog.catalogGrid
import io.github.mhub.app.ui.components.EmptyState
import io.github.mhub.app.ui.components.TrackRow
import io.github.mhub.app.ui.components.plainBackground
import io.github.mhub.core.ApiException
import io.github.mhub.core.CatalogItem
import io.github.mhub.core.CatalogSearch
import io.github.mhub.core.CatalogSection
import io.github.mhub.core.PlatformInfo
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioScrollableTabRow
import io.github.neoruaa.glasmio.ui.GlasmioSearchBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Search: platform chips + search field, then the results in Apple Music's
 * tabs (最佳结果 · 艺人 · 专辑 · 歌曲 · 播放列表), the same layout as iOS.
 * A platform with songs only keeps the plain song list.
 */
@Composable
fun SearchScreen(listState: LazyListState) {
    val graph = LocalGraph.current
    val platforms by graph.model.platforms.collectAsStateWithLifecycle()
    val platform by graph.settings.searchPlatform.state.collectAsStateWithLifecycle()
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var active by rememberSaveable { mutableStateOf(false) }
    var found by remember { mutableStateOf<CatalogSearch?>(null) }
    var section by rememberSaveable { mutableStateOf(CatalogSection.Top.wire) }
    var searched by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Typing searches after a short pause; a newer key press restarts the effect.
    LaunchedEffect(query.text, platform) {
        val text = query.text.trim()
        val client = graph.model.client
        if (text.isEmpty() || client == null) {
            found = null; searched = ""; error = null
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        try {
            found = try {
                client.catalogSearch(platform, text)
            } catch (old: ApiException.Http) {
                // A server from before catalog search: songs only.
                if (old.status != 404) throw old
                CatalogSearch(platform, songs = client.search(platform, text).map {
                    CatalogItem(CatalogItem.SONG, it.platform, it.sourceId, it.title, it.artist, it.cover)
                })
            }
            section = CatalogSection.Top.wire
            searched = text
            error = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            found = null
            error = failure.message
        } finally {
            searching = false
        }
    }

    val result = found
    val tabs = result?.visibleSections.orEmpty()
    val current = tabs.firstOrNull { it.wire == section } ?: tabs.firstOrNull() ?: CatalogSection.Songs

    GlasmioPinnedListPage(
        title = stringResource(R.string.search),
        listState = listState,
        bottomPadding = LocalBottomChrome.current,
        backgroundColor = plainBackground(),
        horizontalContentPadding = 0.dp,
        largeTitleHorizontalPadding = 20.dp,
        actions = { io.github.mhub.app.ui.components.SettingsButton() },
    ) {
        item("search") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlasmioSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    active = active,
                    onActiveChange = { active = it },
                    onClose = { active = false; query = TextFieldValue() },
                    placeholder = stringResource(R.string.search_prompt),
                    closeContentDescription = stringResource(R.string.close_search),
                    modifier = Modifier.fillMaxWidth(),
                )
                GlasmioScrollableTabRow(
                    items = platforms.map { it.id to shortLabel(it) },
                    selected = platform,
                    onSelected = graph.settings.searchPlatform::set,
                )
                if (tabs.isNotEmpty()) {
                    GlasmioScrollableTabRow(
                        items = tabs.map { it.wire to it.label },
                        selected = current.wire,
                        onSelected = { section = it },
                    )
                }
            }
        }
        when {
            result != null && (result.songs.isNotEmpty() || tabs.isNotEmpty()) -> when {
                current == CatalogSection.Top && tabs.isNotEmpty() -> {
                    item("top") { Box(Modifier.padding(top = 4.dp)) { CatalogShelf("", result.top, withKind = true) } }
                    if (result.songs.isNotEmpty()) {
                        item("songs-title") {
                            Box(Modifier.padding(top = 20.dp, bottom = 4.dp)) { SectionTitle(CatalogSection.Songs.label) { section = CatalogSection.Songs.wire } }
                        }
                        songRows(result.songs.take(6))
                    }
                    listOf(CatalogSection.Albums, CatalogSection.Artists, CatalogSection.Playlists)
                        .filter { result.items(it).isNotEmpty() }
                        .forEach { shelf ->
                            item("shelf-${shelf.wire}") {
                                Box(Modifier.padding(top = 20.dp)) {
                                    CatalogShelf(shelf.label, result.items(shelf).take(12)) { section = shelf.wire }
                                }
                            }
                        }
                }
                current == CatalogSection.Songs || current == CatalogSection.Top -> songRows(result.songs)
                else -> catalogGrid(result.items(current))
            }
            searching -> item("searching") { EmptyState(stringResource(R.string.loading), symbol = "magnifyingglass") }
            error != null -> item("error") { EmptyState(stringResource(R.string.home_load_failed), error, "exclamationmark.triangle") }
            searched.isNotEmpty() -> item("empty") { EmptyState(stringResource(R.string.search_empty), symbol = "magnifyingglass") }
            else -> item("hint") { EmptyState(stringResource(R.string.search_hint), symbol = "magnifyingglass") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.songRows(songs: List<CatalogItem>) {
    items(songs, key = { it.id }) { TrackRow(it.track, Modifier.padding(horizontal = 20.dp), separator = it != songs.last()) }
}

private fun shortLabel(platform: PlatformInfo) = when (platform.id) {
    "applemusic" -> "Apple"
    "netease" -> "网易云"
    "tencent" -> "QQ"
    "bilibili" -> "B站"
    "youtubemusic" -> "YouTube"
    else -> platform.label
}
