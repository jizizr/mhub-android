package io.github.mhub.app.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.mhub.app.R
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.core.Links
import io.github.mhub.core.Track
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioAlertTextField
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioMenu
import io.github.neoruaa.glasmio.ui.GlasmioMenuItem
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar

/** Cover art with a neutral placeholder; Coil shares its memory and disk caches. */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    val colors = LocalGlassColors.current
    Box(
        modifier.clip(ContinuousRoundedRectangle(corner)).background(colors.container),
        contentAlignment = Alignment.Center,
    ) {
        SfIcon(SfSymbol.MusicNote, null, tint = colors.tertiaryContent, size = 22.dp)
        if (!url.isNullOrEmpty()) {
            // One memory-cache entry per cover, whatever its size, so an artwork that
            // moves between sizes (shared transitions) never flashes the placeholder.
            val context = LocalContext.current
            val request = remember(url) {
                coil3.request.ImageRequest.Builder(context).data(url).memoryCacheKey(url).placeholderMemoryCacheKey(url).build()
            }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** One song in a list. Tapping plays it now; the menu holds the other actions. */
@Composable
fun TrackRow(track: Track, modifier: Modifier = Modifier, playlistId: String? = null, onRemove: (() -> Unit)? = null, separator: Boolean = false) {
    val graph = LocalGraph.current
    val state by graph.player.state.collectAsStateWithLifecycle()
    val playing = state.current?.id == track.id
    val colors = LocalGlassColors.current
    val separatorColor = colors.separator
    Row(
        modifier.fillMaxWidth()
            .clickable { graph.player.playNow(track) }
            // iOS plain-list separator: a hairline inset to the title.
            .drawBehind {
                if (separator) {
                    val y = size.height - 0.5f
                    drawLine(separatorColor, Offset(60.dp.toPx(), y), Offset(size.width, y), strokeWidth = 1f)
                }
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box {
            Artwork(track.cover, Modifier.size(48.dp), corner = 5.dp)
            if (playing) {
                Box(Modifier.size(48.dp).clip(ContinuousRoundedRectangle(5.dp)).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    SfIcon(SfSymbol.Waveform, null, tint = Color.White, size = 22.dp)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, style = IosTypography.body, color = if (playing) colors.accent else colors.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = IosTypography.subheadline, color = colors.secondaryContent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TrackMenu(listOf(track), playlistId = playlistId, onRemove = onRemove)
    }
}

/** The song actions, in the order shared with iOS and the web app. */
@Composable
fun TrackMenu(tracks: List<Track>, playlistId: String? = null, onRemove: (() -> Unit)? = null, tint: Color? = null) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var addToPlaylist by remember { mutableStateOf(false) }
    val share = tracks.singleOrNull()?.let { track -> graph.settings.serverUrl?.let { Links.shareUrl(track, it) }?.let { track to it } }
    val removeLabel = stringResource(R.string.remove_from_playlist)
    val items = buildList {
        add(Triple(stringResource(R.string.play_next), "text.line.first.and.arrowtriangle.forward") { graph.player.playNext(tracks) })
        add(Triple(stringResource(R.string.add_to_queue), "text.line.last.and.arrowtriangle.forward") { graph.player.append(tracks) })
        add(Triple(stringResource(R.string.add_to_playlist), "text.badge.plus") { addToPlaylist = true })
        if (share != null) {
            val shareTitle = stringResource(R.string.share)
            add(Triple(shareTitle, "square.and.arrow.up") {
                val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "${share.first.title} - ${share.first.artist} ${share.second}")
                context.startActivity(Intent.createChooser(intent, shareTitle))
            })
        }
    }
    GlasmioMenu(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        itemCount = items.size + if (playlistId != null && onRemove != null) 1 else 0,
        anchor = { open ->
            Box(Modifier.size(width = 36.dp, height = 44.dp).clickable(onClick = open), contentAlignment = Alignment.Center) {
                SfIcon(SfSymbol.Ellipsis, stringResource(R.string.more), tint = tint ?: LocalGlassColors.current.secondaryContent, size = 20.dp)
            }
        },
    ) { backdrop, close ->
        items.forEach { (title, symbol, action) ->
            GlasmioMenuItem(title, { close(); action() }, systemName = symbol, backdrop = backdrop)
        }
        if (playlistId != null && onRemove != null) {
            GlasmioMenuItem(removeLabel, { close(); onRemove() }, systemName = "trash", destructive = true, backdrop = backdrop)
        }
    }
    if (addToPlaylist) AddToPlaylistSheet(tracks) { addToPlaylist = false }
}

@Composable
fun AddToPlaylistSheet(tracks: List<Track>, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val playlists by graph.library.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val untitled = stringResource(R.string.untitled_playlist)
    GlasmioBottomSheet(
        onDismissRequest = onDismiss,
        skipPartiallyExpanded = false,
        title = stringResource(R.string.add_to_playlist),
        topBar = { GlasmioSheetTopToolbar(title = stringResource(R.string.add_to_playlist)) },
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 72.dp, bottom = 24.dp)) {
            GlasmioGroupedList {
                GlasmioListRow(stringResource(R.string.new_playlist), systemName = "plus", onClick = { creating = true }, showTopSeparator = false)
                playlists.forEach { playlist ->
                    GlasmioListRow(
                        playlist.name,
                        subtitle = context.getString(R.string.songs_count, playlist.tracks.size),
                        leading = { Artwork(playlist.cover, Modifier.size(40.dp)) },
                        onClick = {
                            val added = graph.library.add(tracks, playlist.id)
                            graph.model.show(context.getString(if (added > 0) R.string.added_to_playlist else R.string.already_in_playlist, playlist.name))
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
    if (creating) {
        TextAlert(
            title = stringResource(R.string.new_playlist),
            placeholder = stringResource(R.string.playlist_name),
            confirm = stringResource(R.string.create),
            value = name,
            onValueChange = { name = it },
            onDismiss = { creating = false },
            onConfirm = {
                val playlist = graph.library.create(name, untitled, tracks)
                graph.model.show(context.getString(R.string.added_to_playlist, playlist.name))
                creating = false
                onDismiss()
            },
        )
    }
}

/** An alert with one text field, used for naming playlists. */
@Composable
fun TextAlert(title: String, placeholder: String, confirm: String, value: String, onValueChange: (String) -> Unit, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    GlasmioAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        buttons = listOf(
            GlasmioAlertButtonSpec(stringResource(R.string.cancel), onDismiss, role = GlasmioAlertButtonRole.Cancel),
            GlasmioAlertButtonSpec(confirm, onConfirm),
        ),
    ) {
        GlasmioAlertTextField(value = field, onValueChange = { field = it; onValueChange(it.text) }, placeholder = placeholder)
    }
}

/** "播放" and "随机播放" under a list header, as in Apple Music. */
@Composable
fun PlayShuffleButtons(tracks: List<Track>, modifier: Modifier = Modifier) {
    val graph = LocalGraph.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        GlasmioButton(onClick = { graph.player.play(tracks, shuffle = false) }, modifier = Modifier.weight(1f), size = GlasmioButtonSize.Large, enabled = tracks.isNotEmpty()) {
            SfIcon(SfSymbol.PlayFilled, null, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.play), fontWeight = FontWeight.SemiBold)
        }
        GlasmioButton(onClick = { graph.player.play(tracks, shuffle = true) }, modifier = Modifier.weight(1f), size = GlasmioButtonSize.Large, enabled = tracks.isNotEmpty()) {
            SfIcon("shuffle", null, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.shuffle), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Large centred header for playlist and collection pages. */
@Composable
fun CollectionHeader(title: String, cover: String?, subtitle: String?, tracks: List<Track>?) {
    val colors = LocalGlassColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(cover, Modifier.size(240.dp), corner = 12.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, style = IosTypography.title2, color = colors.content)
        if (subtitle != null) Text(subtitle, style = IosTypography.subheadline, color = colors.secondaryContent)
        if (tracks != null) {
            Spacer(Modifier.height(14.dp))
            PlayShuffleButtons(tracks)
        }
    }
}

/** Empty / error states, like iOS ContentUnavailableView. */
@Composable
fun EmptyState(title: String, message: String? = null, symbol: String = "music.note", action: Pair<String, () -> Unit>? = null) {
    val colors = LocalGlassColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SfIcon(symbol, null, tint = colors.tertiaryContent, size = 44.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = IosTypography.title3, color = colors.content, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(message, style = IosTypography.subheadline, color = colors.secondaryContent, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            GlasmioButton(onClick = action.second) { Text(action.first) }
        }
    }
}

fun Long.byteText(): String = when {
    this >= 1L shl 30 -> "%.1f GB".format(this / (1L shl 30).toDouble())
    this >= 1L shl 20 -> "%.0f MB".format(this / (1L shl 20).toDouble())
    else -> "%.0f KB".format(this / 1024.0)
}

/** Transient feedback at the top of the screen. */
@Composable
fun ToastPill(text: String, onClick: () -> Unit) {
    val colors = LocalGlassColors.current
    Box(
        Modifier.padding(horizontal = 24.dp)
            .shadow(12.dp, ContinuousRoundedRectangle(22.dp), clip = false)
            .clip(ContinuousRoundedRectangle(22.dp))
            .background(colors.elevatedBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(text, style = IosTypography.subheadline, fontWeight = FontWeight.Medium, color = colors.content)
    }
}

/** iOS `systemBackground`: plain pages (home, search, song lists) are white / black, grouped pages stay grey. */
@Composable
fun plainBackground(): Color = if (LocalGlassColors.current.isDark) Color.Black else Color.White
