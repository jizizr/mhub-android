package io.github.mhub.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.mhub.app.BuildConfigCompat
import io.github.mhub.app.R
import io.github.mhub.app.ui.LocalBottomChrome
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.app.ui.LocalNavigator
import io.github.mhub.app.ui.components.byteText
import io.github.mhub.core.MHubClient
import io.github.mhub.core.Quality
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.glass.GlasmioBackdropHost
import io.github.neoruaa.glasmio.glass.GlassSurface
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbol
import io.github.neoruaa.glasmio.preference.GlasmioListPreference
import io.github.neoruaa.glasmio.preference.GlasmioPreferenceGroupTitle
import io.github.neoruaa.glasmio.preference.GlasmioSwitchPreference
import io.github.neoruaa.glasmio.scaffold.GlasmioPinnedListPage
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioGroupedTableFooter
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import kotlinx.coroutines.launch

/** Checks `/health` before saving a server address. Returns an error message or null. */
private suspend fun checkServer(text: String, invalid: String, failed: (String) -> String): Pair<String?, String?> {
    val url = MHubClient.serverUrl(text) ?: return null to invalid
    return runCatching { MHubClient(url).health() }.fold(
        onSuccess = { MHubClient.origin(url) to null },
        onFailure = { null to failed(it.message.orEmpty()) },
    )
}

@Composable
fun SettingsScreen() {
    val graph = LocalGraph.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = graph.settings
    val server by settings.serverAddress.state.collectAsStateWithLifecycle()
    val quality by settings.quality.state.collectAsStateWithLifecycle()
    val platform by settings.searchPlatform.state.collectAsStateWithLifecycle()
    val limit by settings.cacheLimit.state.collectAsStateWithLifecycle()
    val platforms by graph.model.platforms.collectAsStateWithLifecycle()
    val player by graph.player.state.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf(server) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val invalid = stringResource(R.string.invalid_server)

    GlasmioPinnedListPage(
        title = stringResource(R.string.settings),
        bottomPadding = LocalBottomChrome.current,
        onNavigateBack = { navigator.pop() },
        navigateBackContentDescription = stringResource(R.string.back),
    ) {
        item("account") { AccountSection() }
        item("server") {
            GlasmioPreferenceGroupTitle(stringResource(R.string.server))
            GlasmioGroupedList {
                Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    GlasmioTextField(
                        value = address,
                        onValueChange = { address = it },
                        placeholder = stringResource(R.string.server_placeholder),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    )
                }
                if (address != server) {
                    GlasmioListRow(stringResource(if (checking) R.string.connecting else R.string.connect), titleColor = LocalGlassColors.current.accent, enabled = !checking, onClick = {
                        checking = true
                        scope.launch {
                            val (saved, error) = checkServer(address, invalid) { context.getString(R.string.connect_failed, it) }
                            if (saved != null) { settings.serverAddress.set(saved); address = saved }
                            serverError = error
                            checking = false
                        }
                    }, showDisclosure = false)
                }
            }
            GlasmioGroupedTableFooter(serverError ?: stringResource(R.string.server_footer))
        }
        item("playback") {
            GlasmioPreferenceGroupTitle(stringResource(R.string.playback))
            GlasmioGroupedList {
                GlasmioListPreference(
                    title = { Text(stringResource(R.string.quality)) },
                    selectedValue = quality,
                    values = Quality.entries,
                    valueText = { it.label },
                    onValueSelected = settings.quality::set,
                    showTopSeparator = false,
                )
                GlasmioSwitchPreference(
                    title = { Text(stringResource(R.string.autoplay)) },
                    checked = player.queue.autoplay,
                    onCheckedChange = graph.player::setAutoplay,
                )
            }
            GlasmioGroupedTableFooter(stringResource(R.string.quality_footer))
        }
        item("search") {
            GlasmioPreferenceGroupTitle(stringResource(R.string.search))
            GlasmioGroupedList {
                GlasmioListPreference(
                    title = { Text(stringResource(R.string.search_platform)) },
                    selectedValue = platform,
                    values = platforms.map { it.id },
                    valueText = { id -> platforms.firstOrNull { it.id == id }?.label ?: id },
                    onValueSelected = settings.searchPlatform::set,
                    showTopSeparator = false,
                )
            }
        }
        item("lyrics") { LyricsSettingsGroup() }
        item("appearance") { AppearanceSettingsGroup() }
        item("storage") {
            GlasmioPreferenceGroupTitle(stringResource(R.string.storage))
            GlasmioGroupedList {
                GlasmioListRow(stringResource(R.string.cache_usage), detail = graph.cache.totalSize().byteText(), showTopSeparator = false)
                GlasmioListPreference(
                    title = { Text(stringResource(R.string.cache_limit)) },
                    selectedValue = limit,
                    values = listOf(1L shl 30, 2L shl 30, 5L shl 30, 10L shl 30),
                    valueText = { it.byteText() },
                    onValueSelected = settings.cacheLimit::set,
                )
            }
            GlasmioGroupedTableFooter(stringResource(R.string.cache_limit_footer))
        }
        item("about") {
            GlasmioPreferenceGroupTitle(stringResource(R.string.about))
            GlasmioGroupedList {
                GlasmioListRow(stringResource(R.string.version), detail = BuildConfigCompat.versionName(context), showTopSeparator = false)
                val autoUpdate by settings.autoUpdate.state.collectAsStateWithLifecycle()
                val checkingUpdate by graph.updates.checking.collectAsStateWithLifecycle()
                GlasmioSwitchPreference(title = { Text(stringResource(R.string.auto_update)) }, checked = autoUpdate, onCheckedChange = settings.autoUpdate::set)
                GlasmioListRow(
                    stringResource(if (checkingUpdate) R.string.checking_update else R.string.check_update),
                    titleColor = LocalGlassColors.current.accent, enabled = !checkingUpdate, showDisclosure = false,
                    onClick = { scope.launch { graph.updates.checkNow()?.let(graph.model::show) } },
                )
                GlasmioListRow(stringResource(R.string.source_code), onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/jizizr/mhub")))
                })
            }
            GlasmioGroupedTableFooter(stringResource(R.string.license_text))
        }
    }
}

/** First launch: connect to a server. */
@Composable
fun ServerSetupScreen() {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.invalid_server)
    val colors = LocalGlassColors.current
    fun connect() {
        if (checking) return
        checking = true
        scope.launch {
            val (saved, failure) = checkServer(address, invalid) { context.getString(R.string.connect_failed, it) }
            if (saved != null) graph.settings.serverAddress.set(saved)
            error = failure
            checking = false
        }
    }
    GlasmioBackdropHost(
        modifier = Modifier.fillMaxSize(),
        sampledContent = {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFFF8FA3), Color(0xFFC08BEA), Color(0xFF8FB8FF)))))
        },
        overlayContent = {
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 460.dp).padding(28.dp).imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(Modifier.size(92.dp).background(Color.White.copy(alpha = 0.25f), ContinuousRoundedRectangle(22.dp)), contentAlignment = Alignment.Center) {
                    SfIcon(SfSymbol.MusicNote, null, tint = Color.White, size = 44.dp)
                }
                Text(stringResource(R.string.welcome), style = IosTypography.largeTitle, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(stringResource(R.string.welcome_hint), style = IosTypography.body, color = Color.Black.copy(alpha = 0.6f), textAlign = TextAlign.Center)
                // A glass capsule field, the same as the iPhone setup screen.
                GlassSurface(Modifier.fillMaxWidth().height(52.dp), shape = ContinuousCapsule, contentAlignment = Alignment.CenterStart) {
                    if (address.isEmpty()) {
                        Text(stringResource(R.string.server_placeholder), style = IosTypography.body, color = Color.Black.copy(alpha = 0.35f),
                            modifier = Modifier.padding(horizontal = 18.dp))
                    }
                    BasicTextField(
                        value = address,
                        onValueChange = { address = it },
                        singleLine = true,
                        textStyle = IosTypography.body.copy(color = Color.Black),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { connect() }),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    )
                }
                GlasmioButton(onClick = ::connect, modifier = Modifier.fillMaxWidth(), size = GlasmioButtonSize.Large, enabled = address.isNotBlank() && !checking) {
                    Text(stringResource(if (checking) R.string.connecting else R.string.connect), fontWeight = FontWeight.SemiBold)
                }
                error?.let { Text(it, style = IosTypography.footnote, color = colors.destructive, textAlign = TextAlign.Center) }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.server_footer), style = IosTypography.footnote, color = Color.Black.copy(alpha = 0.55f), textAlign = TextAlign.Center)
            }
        },
    )
}
