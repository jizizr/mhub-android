package io.github.mhub.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mhub.app.AccountManager
import io.github.mhub.app.Appearance
import io.github.mhub.app.R
import io.github.mhub.app.ui.LocalGraph
import io.github.mhub.core.ApiException
import io.github.mhub.core.LyricScript
import io.github.mhub.core.LyricWordMode
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.preference.GlasmioListPreference
import io.github.neoruaa.glasmio.preference.GlasmioPreferenceGroupTitle
import io.github.neoruaa.glasmio.preference.GlasmioSwitchPreference
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonRole
import io.github.neoruaa.glasmio.ui.GlasmioAlertButtonSpec
import io.github.neoruaa.glasmio.ui.GlasmioAlertDialog
import io.github.neoruaa.glasmio.ui.GlasmioBottomSheet
import io.github.neoruaa.glasmio.ui.GlasmioButton
import io.github.neoruaa.glasmio.ui.GlasmioButtonSize
import io.github.neoruaa.glasmio.ui.GlasmioGroupedList
import io.github.neoruaa.glasmio.ui.GlasmioGroupedTableFooter
import io.github.neoruaa.glasmio.ui.GlasmioListRow
import io.github.neoruaa.glasmio.ui.GlasmioSheetTopToolbar
import io.github.neoruaa.glasmio.ui.GlasmioTextField
import kotlinx.coroutines.launch

enum class AccountForm { Login, Register, Recover }

/** Settings「账号」: sign-in entries for guests, sync state and sign-out for members. */
@Composable
fun AccountSection() {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by graph.account.state.collectAsStateWithLifecycle()
    var form by remember { mutableStateOf<AccountForm?>(null) }
    var confirmingLogout by remember { mutableStateOf(false) }
    if (!state.config.enabled && state.user == null) return
    val accent = LocalGlassColors.current.accent
    GlasmioPreferenceGroupTitle(stringResource(R.string.account))
    val user = state.user
    GlasmioGroupedList {
        if (user == null) {
            GlasmioListRow(stringResource(R.string.sign_in), systemName = "person.crop.circle", titleColor = accent, onClick = { form = AccountForm.Login }, showTopSeparator = false)
            GlasmioListRow(stringResource(R.string.register), systemName = "person.badge.plus", titleColor = accent, onClick = {
                if (state.config.registersOnWeb) {
                    graph.settings.serverUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it.toString()))) }
                } else form = AccountForm.Register
            })
            GlasmioListRow(stringResource(R.string.recovery_sign_in), systemName = "key", titleColor = accent, onClick = { form = AccountForm.Recover })
        } else {
            GlasmioListRow(user.username, systemName = "person.crop.circle.fill", detail = syncText(state), showTopSeparator = false)
            GlasmioListRow(stringResource(R.string.sync_now), titleColor = accent, enabled = state.sync != AccountManager.Sync.Syncing, onClick = graph.account::syncNow, showDisclosure = false)
            GlasmioListRow(stringResource(R.string.sign_out), titleColor = LocalGlassColors.current.destructive, onClick = { confirmingLogout = true }, showDisclosure = false)
        }
    }
    GlasmioGroupedTableFooter(state.error?.let { stringResource(R.string.sync_failed, it) } ?: stringResource(if (user == null) R.string.account_footer else R.string.account_linked_footer))

    form?.let { AccountSheet(it) { form = null } }
    if (confirmingLogout) {
        GlasmioAlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = stringResource(R.string.sign_out),
            message = if (state.pending > 0) stringResource(R.string.sign_out_pending, state.pending) else stringResource(R.string.sign_out_message),
            buttons = listOf(
                GlasmioAlertButtonSpec(stringResource(R.string.cancel), { confirmingLogout = false }, role = GlasmioAlertButtonRole.Cancel),
                GlasmioAlertButtonSpec(stringResource(R.string.sign_out), {
                    confirmingLogout = false
                    scope.launch { graph.account.logout() }
                }, role = GlasmioAlertButtonRole.Destructive),
            ),
        )
    }
}

@Composable
private fun syncText(state: AccountManager.State): String = when {
    state.sync == AccountManager.Sync.Syncing -> stringResource(R.string.syncing)
    state.pending > 0 -> stringResource(R.string.sync_pending, state.pending)
    state.sync == AccountManager.Sync.Offline -> stringResource(R.string.sync_offline)
    else -> stringResource(R.string.synced)
}

/** Username and password (or recovery code); registering shows the recovery codes once. */
@Composable
fun AccountSheet(form: AccountForm, onDismiss: () -> Unit) {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var codes by remember { mutableStateOf<List<String>?>(null) }
    val title = stringResource(when (form) {
        AccountForm.Login -> R.string.sign_in
        AccountForm.Register -> R.string.register
        AccountForm.Recover -> R.string.recovery_sign_in
    })
    val mismatch = stringResource(R.string.password_mismatch)
    val ready = username.isNotBlank() && secret.isNotEmpty() && (form != AccountForm.Register || confirm.isNotEmpty())
    fun submit() {
        if (!ready || working) return
        if (form == AccountForm.Register && secret != confirm) { error = mismatch; return }
        working = true
        error = null
        scope.launch {
            try {
                when (form) {
                    AccountForm.Login -> { graph.account.login(username, secret); onDismiss() }
                    AccountForm.Recover -> { graph.account.recover(username, secret); onDismiss() }
                    AccountForm.Register -> codes = graph.account.register(username, secret)
                }
            } catch (failure: ApiException) {
                error = failure.message
            } catch (failure: java.io.IOException) {
                error = context.getString(R.string.connect_failed, failure.message.orEmpty())
            } finally {
                working = false
            }
        }
    }
    GlasmioBottomSheet(
        onDismissRequest = onDismiss,
        title = title,
        topBar = { GlasmioSheetTopToolbar(title = title) },
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 72.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlasmioGroupedList {
                Field(username, { username = it }, stringResource(R.string.username), KeyboardType.Ascii)
                Field(
                    secret, { secret = it },
                    stringResource(if (form == AccountForm.Recover) R.string.recovery_code else R.string.password),
                    if (form == AccountForm.Recover) KeyboardType.Ascii else KeyboardType.Password,
                    password = form != AccountForm.Recover,
                )
                if (form == AccountForm.Register) Field(confirm, { confirm = it }, stringResource(R.string.confirm_password), KeyboardType.Password, password = true)
            }
            error?.let { Text(it, style = IosTypography.footnote, color = LocalGlassColors.current.destructive, modifier = Modifier.padding(horizontal = 16.dp)) }
            GlasmioButton(onClick = ::submit, modifier = Modifier.fillMaxWidth(), size = GlasmioButtonSize.Large, enabled = ready && !working) {
                Text(if (working) stringResource(R.string.loading) else title, fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(if (form == AccountForm.Register) R.string.register_footer else R.string.account_footer),
                style = IosTypography.footnote, color = LocalGlassColors.current.secondaryContent, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    codes?.let { list ->
        GlasmioAlertDialog(
            onDismissRequest = {},
            title = stringResource(R.string.recovery_codes_title),
            message = stringResource(R.string.recovery_codes_message) + "\n\n" + list.joinToString("\n"),
            buttons = listOf(
                GlasmioAlertButtonSpec(stringResource(R.string.copy), {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("MHub", list.joinToString("\n")))
                    codes = null
                    onDismiss()
                }),
                GlasmioAlertButtonSpec(stringResource(R.string.done), { codes = null; onDismiss() }, role = GlasmioAlertButtonRole.Cancel),
            ),
        )
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, type: KeyboardType, password: Boolean = false) {
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        GlasmioTextField(
            value = value,
            onValueChange = onChange,
            placeholder = placeholder,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        )
    }
}

/** Settings「歌词」, also shown from the player. */
@Composable
fun LyricsSettingsGroup() {
    val settings = LocalGraph.current.settings
    val script by settings.lyricScript.state.collectAsStateWithLifecycle()
    val translation by settings.showTranslation.state.collectAsStateWithLifecycle()
    val roman by settings.showRoman.state.collectAsStateWithLifecycle()
    val wordMode by settings.lyricWordMode.state.collectAsStateWithLifecycle()
    GlasmioPreferenceGroupTitle(stringResource(R.string.lyrics))
    GlasmioGroupedList {
        GlasmioListPreference(
            title = { Text(stringResource(R.string.lyric_word_mode)) },
            selectedValue = wordMode,
            values = LyricWordMode.entries,
            valueText = { it.label },
            onValueSelected = settings.lyricWordMode::set,
            showTopSeparator = false,
        )
        GlasmioListPreference(
            title = { Text(stringResource(R.string.lyric_script)) },
            selectedValue = script,
            values = LyricScript.entries,
            valueText = { it.label },
            onValueSelected = settings.lyricScript::set,
        )
        GlasmioSwitchPreference(title = { Text(stringResource(R.string.show_translation)) }, checked = translation, onCheckedChange = settings.showTranslation::set)
        GlasmioSwitchPreference(title = { Text(stringResource(R.string.show_roman)) }, checked = roman, onCheckedChange = settings.showRoman::set)
    }
    GlasmioGroupedTableFooter(stringResource(R.string.lyric_word_mode_footer))
}

/** Settings「外观」, also shown from the player. */
@Composable
fun AppearanceSettingsGroup() {
    val settings = LocalGraph.current.settings
    val appearance by settings.appearance.state.collectAsStateWithLifecycle()
    val labels = mapOf(
        Appearance.System to stringResource(R.string.appearance_system),
        Appearance.Light to stringResource(R.string.appearance_light),
        Appearance.Dark to stringResource(R.string.appearance_dark),
    )
    GlasmioPreferenceGroupTitle(stringResource(R.string.appearance))
    GlasmioGroupedList {
        GlasmioListPreference(
            title = { Text(stringResource(R.string.appearance)) },
            selectedValue = appearance,
            values = Appearance.entries,
            valueText = { labels.getValue(it) },
            onValueSelected = settings.appearance::set,
            showTopSeparator = false,
        )
    }
}

/** Full player「…」→「歌词与外观…」: the two groups without leaving the song. */
@Composable
fun DisplaySettingsSheet(onDismiss: () -> Unit) {
    val title = stringResource(R.string.display_settings)
    GlasmioBottomSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = false, title = title, topBar = { GlasmioSheetTopToolbar(title = title) }) {
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 64.dp, bottom = 24.dp)) {
            LyricsSettingsGroup()
            AppearanceSettingsGroup()
        }
    }
}

/** A newer GitHub release (docs/design.md「检查更新」). */
@Composable
fun UpdateDialog() {
    val graph = LocalGraph.current
    val context = LocalContext.current
    val offer by graph.updates.offer.collectAsStateWithLifecycle()
    val update = offer ?: return
    val notes = io.github.mhub.core.UpdateCheck.summary(update.notes)
    GlasmioAlertDialog(
        onDismissRequest = graph.updates::dismiss,
        title = stringResource(R.string.update_available, update.version.toString()),
        message = notes.ifEmpty { null },
        buttons = listOf(
            GlasmioAlertButtonSpec(stringResource(R.string.later), { graph.updates.later(update) }, role = GlasmioAlertButtonRole.Cancel),
            GlasmioAlertButtonSpec(stringResource(R.string.download), {
                graph.updates.dismiss()
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.download ?: update.page)))
            }),
        ),
    )
}
