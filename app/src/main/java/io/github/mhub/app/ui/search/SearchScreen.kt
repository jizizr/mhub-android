package io.github.mhub.app.ui.search

import androidx.compose.foundation.layout.Arrangement
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
import io.github.mhub.app.ui.components.EmptyState
import io.github.mhub.app.ui.components.plainBackground
import io.github.mhub.app.ui.components.TrackRow
import io.github.mhub.core.PlatformInfo
import io.github.mhub.core.Track
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioScrollableTabRow
import io.github.neoruaa.glasmio.ui.GlasmioSearchBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Search: platform chips + search field + results, the same layout as iOS. */
@Composable
fun SearchScreen(listState: LazyListState) {
    val graph = LocalGraph.current
    val platforms by graph.model.platforms.collectAsStateWithLifecycle()
    val platform by graph.settings.searchPlatform.state.collectAsStateWithLifecycle()
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var active by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf(listOf<Track>()) }
    var searched by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Typing searches after a short pause; a newer key press restarts the effect.
    LaunchedEffect(query.text, platform) {
        val text = query.text.trim()
        val client = graph.model.client
        if (text.isEmpty() || client == null) {
            results = emptyList(); searched = ""; error = null
            return@LaunchedEffect
        }
        delay(350)
        searching = true
        try {
            results = client.search(platform, text)
            searched = text
            error = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            results = emptyList()
            error = failure.message
        } finally {
            searching = false
        }
    }

    GlasmioPinnedListPage(
        title = stringResource(R.string.search),
        listState = listState,
        bottomPadding = LocalBottomChrome.current,
        backgroundColor = plainBackground(),
        actions = { io.github.mhub.app.ui.components.SettingsButton() },
    ) {
        item("search") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        }
        when {
            results.isNotEmpty() -> items(results, key = { it.id }) { TrackRow(it, Modifier.padding(horizontal = 4.dp), separator = it != results.last()) }
            searching -> item("searching") { EmptyState(stringResource(R.string.loading), symbol = "magnifyingglass") }
            error != null -> item("error") { EmptyState(stringResource(R.string.home_load_failed), error, "exclamationmark.triangle") }
            searched.isNotEmpty() -> item("empty") { EmptyState(stringResource(R.string.search_empty), symbol = "magnifyingglass") }
            else -> item("hint") { EmptyState(stringResource(R.string.search_hint), symbol = "magnifyingglass") }
        }
    }
}

private fun shortLabel(platform: PlatformInfo) = when (platform.id) {
    "applemusic" -> "Apple"
    "netease" -> "网易云"
    "tencent" -> "QQ"
    "bilibili" -> "B站"
    "youtubemusic" -> "YouTube"
    else -> platform.label
}
