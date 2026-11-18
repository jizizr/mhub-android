package io.github.mhub.app.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mhub.app.R
import io.github.mhub.app.ui.LocalBottomChrome
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.app.ui.LocalNavigator
import io.github.mhub.app.ui.MHubAccent
import io.github.mhub.app.ui.Route
import io.github.mhub.app.ui.components.Artwork
import io.github.mhub.app.ui.components.EmptyState
import io.github.mhub.app.ui.components.PlayShuffleButtons
import io.github.mhub.app.ui.components.TrackMenu
import io.github.mhub.app.ui.components.TrackRow
import io.github.mhub.app.ui.components.plainBackground
import io.github.mhub.app.ui.pressScale
import io.github.mhub.core.AlbumDetail
import io.github.mhub.core.ArtistDetail
import io.github.mhub.core.CatalogItem
import io.github.mhub.core.QueueSource
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioIconButton

/** Where a catalog card leads: album and artist pages, or the playlist page; null for songs. */
fun CatalogItem.route(): Route? = when (type) {
    CatalogItem.ALBUM -> Route.Album(platform, itemId, title = title, cover = cover)
    CatalogItem.ARTIST -> Route.Artist(platform, itemId, title = title, cover = cover)
    CatalogItem.PLAYLIST -> Route.Collection(platform, url.ifEmpty { itemId }, title, cover)
    else -> null
}

/** An Apple Music lockup: square artwork (round for artists) over one or two lines. Songs play in place. */
@Composable
fun CatalogCard(item: CatalogItem, withKind: Boolean = false, size: Int = 150) {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val colors = LocalGlassColors.current
    val artist = item.type == CatalogItem.ARTIST
    val open = { item.route()?.let(navigator::push) ?: graph.player.playNow(item.track) }
    Column(
        Modifier.width(size.dp).pressScale(open),
        horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Artwork(item.cover, Modifier.size(size.dp), corner = if (artist) (size / 2).dp else 10.dp)
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), horizontalAlignment = if (artist) Alignment.CenterHorizontally else Alignment.Start) {
                Text(item.title, style = IosTypography.subheadline, color = colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = item.cardSubtitle(withKind).ifEmpty { if (artist) CatalogItem.kindLabel(CatalogItem.ARTIST) else "" }
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = IosTypography.footnote, color = colors.secondaryContent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = if (artist) TextAlign.Center else TextAlign.Start)
                }
            }
            if (item.type == CatalogItem.SONG) TrackMenu(listOf(item.track))
        }
    }
}

/** A titled horizontal shelf; the title opens the full section when [onMore] is set. */
@Composable
fun CatalogShelf(title: String, items: List<CatalogItem>, withKind: Boolean = false, onMore: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (title.isNotEmpty()) SectionTitle(title, onMore)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.id }) { CatalogCard(it, withKind) }
        }
    }
}

@Composable
fun SectionTitle(title: String, onMore: (() -> Unit)? = null) {
    val colors = LocalGlassColors.current
    Row(
        Modifier.padding(horizontal = 20.dp).then(if (onMore != null) Modifier.clickable(onClick = onMore) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = IosTypography.title3, fontWeight = FontWeight.Bold, color = colors.content)
        if (onMore != null) SfIcon("chevron.right", null, tint = colors.secondaryContent, size = 16.dp)
    }
}

/** A whole section as rows of cards (search tabs other than songs). */
fun LazyListScope.catalogGrid(items: List<CatalogItem>, columns: Int = 2) {
    items(items.chunked(columns), key = { row -> row.first().id }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            row.forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.TopStart) { CatalogCard(it, size = 160) } }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Loading and error states shared by the album and artist pages. */
private fun LazyListScope.pageState(error: String?, retry: () -> Unit) {
    if (error != null) item("error") {
        EmptyState(stringResource(R.string.page_load_failed), error, "exclamationmark.triangle", stringResource(R.string.retry) to retry)
    } else item("loading") { EmptyState(stringResource(R.string.loading), symbol = "sparkles") }
}

@Composable
fun AlbumScreen(route: Route.Album) {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val colors = LocalGlassColors.current
    var album by remember(route) { mutableStateOf<AlbumDetail?>(null) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    var attempt by remember(route) { mutableIntStateOf(0) }
    val untitled = stringResource(R.string.untitled_playlist)
    val noAlbum = stringResource(R.string.no_album_found)
    graph.model.platforms.collectAsStateWithLifecycle().value
    LaunchedEffect(route, attempt) {
        val client = graph.model.client ?: return@LaunchedEffect
        error = null
        runCatching {
            val id = route.id.ifEmpty { client.trackInfo(route.platform, route.trackId).albumId }
            if (id.isEmpty()) throw IllegalStateException(noAlbum)
            client.album(route.platform, id)
        }.onSuccess { album = it }.onFailure { error = it.message }
    }
    val loaded = album
    val source = loaded?.let { QueueSource(QueueSource.ALBUM, it.title, id = it.id, platform = it.platform, url = it.url, cover = it.cover) }
    GlasmioPinnedListPage(
        title = loaded?.title ?: route.title,
        showsLargeTitle = false,
        bottomPadding = LocalBottomChrome.current,
        backgroundColor = plainBackground(),
        horizontalContentPadding = 0.dp,
        onNavigateBack = { navigator.pop() },
        navigateBackContentDescription = stringResource(R.string.back),
        actions = {
            if (loaded != null && loaded.tracks.isNotEmpty()) {
                GlasmioIconButton(sampleBackdrop = false, onClick = {
                    val name = if (loaded.artist.isEmpty()) loaded.title else "${loaded.title} - ${loaded.artist}"
                    val playlist = graph.library.create(name, untitled, loaded.tracks.map { it.track }, loaded.url, loaded.platform)
                    graph.model.show(context.getString(R.string.saved_to_library, playlist.name, playlist.tracks.size))
                }) { SfIcon("plus", stringResource(R.string.add_all_to_library)) }
            }
        },
    ) {
        item("header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(loaded?.cover ?: route.cover, Modifier.size(240.dp), corner = 12.dp)
                Spacer(Modifier.height(14.dp))
                Text(loaded?.title ?: route.title, style = IosTypography.title2, fontWeight = FontWeight.Bold, color = colors.content, textAlign = TextAlign.Center)
                if (loaded != null) {
                    val artistPage = loaded.artistId.isNotEmpty() && graph.model.hasArtists(loaded.platform)
                    Text(
                        loaded.artist, style = IosTypography.title3, color = MHubAccent, textAlign = TextAlign.Center,
                        modifier = if (artistPage) Modifier.clickable {
                            navigator.push(Route.Artist(loaded.platform, loaded.artistId, title = loaded.artist))
                        } else Modifier,
                    )
                    val meta = listOf(loaded.genre, loaded.releaseDate.take(4)).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = IosTypography.caption1, fontWeight = FontWeight.SemiBold, color = colors.secondaryContent)
                    Spacer(Modifier.height(14.dp))
                    PlayShuffleButtons(loaded.tracks.map { it.track }, source = source)
                }
            }
        }
        if (loaded != null && source != null) {
            val tracks = loaded.tracks.map { it.track }
            loaded.discs.forEach { disc ->
                if (disc.disc > 0) item("disc-${disc.disc}") {
                    Text(context.getString(R.string.disc, disc.disc), style = IosTypography.footnote, fontWeight = FontWeight.SemiBold,
                        color = colors.secondaryContent, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                }
                items(disc.tracks, key = { it.value.id }) { (index, item) ->
                    TrackRow(
                        item.track, Modifier.padding(horizontal = 20.dp), separator = true,
                        number = item.trackNumber ?: (index + 1), showsArtist = item.subtitle != loaded.artist,
                        // A tapped song plays the album from there; shuffle keeps it first.
                        onClick = { graph.player.play(tracks, start = index, keepStart = true, source = source) },
                    )
                }
            }
            item("footer") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    loaded.footer.forEach { Text(it, style = IosTypography.footnote, color = colors.secondaryContent) }
                }
            }
        } else pageState(error) { attempt++ }
    }
}

@Composable
fun ArtistScreen(route: Route.Artist) {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val colors = LocalGlassColors.current
    var artist by remember(route) { mutableStateOf<ArtistDetail?>(null) }
    var error by remember(route) { mutableStateOf<String?>(null) }
    var attempt by remember(route) { mutableIntStateOf(0) }
    val noArtist = stringResource(R.string.no_artist_found)
    LaunchedEffect(route, attempt) {
        val client = graph.model.client ?: return@LaunchedEffect
        error = null
        runCatching {
            val id = route.id.ifEmpty { client.trackInfo(route.platform, route.trackId).artistId }
            if (id.isEmpty()) throw IllegalStateException(noArtist)
            client.artist(route.platform, id)
        }.onSuccess { artist = it }.onFailure { error = it.message }
    }
    val loaded = artist
    val source = loaded?.let { QueueSource(QueueSource.ARTIST, it.name, id = it.id, platform = it.platform, cover = it.cover) }
    GlasmioPinnedListPage(
        title = loaded?.name ?: route.title,
        showsLargeTitle = false,
        bottomPadding = LocalBottomChrome.current,
        backgroundColor = plainBackground(),
        horizontalContentPadding = 0.dp,
        onNavigateBack = { navigator.pop() },
        navigateBackContentDescription = stringResource(R.string.back),
    ) {
        item("header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(loaded?.cover ?: route.cover, Modifier.size(200.dp), corner = 100.dp)
                Spacer(Modifier.height(14.dp))
                Text(loaded?.name ?: route.title, style = IosTypography.largeTitle, fontWeight = FontWeight.Bold, color = colors.content, textAlign = TextAlign.Center)
                if (loaded != null && loaded.topSongs.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    PlayShuffleButtons(loaded.topSongs.map { it.track }, source = source)
                }
            }
        }
        if (loaded != null && source != null) {
            val tracks = loaded.topSongs.map { it.track }
            if (tracks.isNotEmpty()) {
                item("top-title") { Box(Modifier.padding(top = 16.dp, bottom = 4.dp)) { SectionTitle(stringResource(R.string.top_songs)) } }
                items(loaded.topSongs.take(10).withIndex().toList(), key = { it.value.id }) { (index, item) ->
                    TrackRow(item.track, Modifier.padding(horizontal = 20.dp), separator = true,
                        onClick = { graph.player.play(tracks, start = index, keepStart = true, source = source) })
                }
            }
            if (loaded.albums.isNotEmpty()) item("albums") {
                Box(Modifier.padding(top = 20.dp)) { CatalogShelf(stringResource(R.string.albums), loaded.albums) }
            }
            if (loaded.singles.isNotEmpty()) item("singles") {
                Box(Modifier.padding(top = 20.dp)) { CatalogShelf(stringResource(R.string.singles_and_eps), loaded.singles) }
            }
        } else pageState(error) { attempt++ }
    }
}
