package io.github.mhub.app

import android.content.Context
import io.github.mhub.core.AvailableUpdate
import io.github.mhub.core.UpdateCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** GitHub release check (docs/design.md「检查更新」). */
class UpdateManager(private val context: Context, private val settings: Settings) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** A newer release to offer; the dialog clears it. */
    private val _offer = MutableStateFlow<AvailableUpdate?>(null)
    val offer: StateFlow<AvailableUpdate?> = _offer

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    val currentVersion: String get() = BuildConfigCompat.versionName(context)

    /** At launch: at most once a day, silent on failure, skipped versions stay quiet. */
    fun checkAutomatically() {
        if (!settings.autoUpdate.value) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("checked_at", 0) < 24 * 3600_000L) return
        scope.launch {
            val update = runCatching { find() }.getOrNull() ?: return@launch
            if (update.version.toString() != prefs.getString("skipped", null)) _offer.value = update
        }
    }

    /** From settings: always asks GitHub; the result is a message or the offer dialog. */
    suspend fun checkNow(): String? {
        _checking.value = true
        return try {
            val update = find()
            if (update == null) context.getString(R.string.up_to_date) else { _offer.value = update; null }
        } catch (error: Exception) {
            context.getString(R.string.update_check_failed)
        } finally {
            _checking.value = false
        }
    }

    private suspend fun find(): AvailableUpdate? {
        val releases = UpdateCheck.fetch(REPOSITORY)
        prefs.edit().putLong("checked_at", System.currentTimeMillis()).apply()
        return UpdateCheck.newer(releases, currentVersion, ".apk")
    }

    fun later(update: AvailableUpdate) {
        prefs.edit().putString("skipped", update.version.toString()).apply()
        _offer.value = null
    }

    fun dismiss() { _offer.value = null }

    companion object {
        const val REPOSITORY = "jizizr/mhub-android"
    }
}
