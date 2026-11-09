package io.github.mhub.app

import android.content.Context
import io.github.mhub.core.LyricScript
import io.github.mhub.core.LyricWordMode
import io.github.mhub.core.MHubClient
import io.github.mhub.core.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl

enum class Appearance { System, Light, Dark }

/**
 * User preferences in SharedPreferences. Keys match the iOS `Settings`
 * UserDefaults keys so both apps describe the same options.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun <T> pref(initial: T, write: (T) -> Unit) = Pref(MutableStateFlow(initial), write)

    class Pref<T>(private val flow: MutableStateFlow<T>, private val write: (T) -> Unit) {
        val value: T get() = flow.value
        val state: StateFlow<T> get() = flow
        fun set(value: T) {
            flow.value = value
            write(value)
        }
    }

    val serverAddress = pref(prefs.getString("server", "").orEmpty()) { prefs.edit().putString("server", it).apply() }
    val quality = pref(Quality.from(prefs.getString("quality", null)) ?: Quality.High) { prefs.edit().putString("quality", it.wire).apply() }
    val searchPlatform = pref(prefs.getString("search_platform", null) ?: "applemusic") { prefs.edit().putString("search_platform", it).apply() }
    val lyricScript = pref(LyricScript.entries.firstOrNull { it.name.lowercase() == prefs.getString("lyric_script", null) } ?: LyricScript.Original) {
        prefs.edit().putString("lyric_script", it.name.lowercase()).apply()
    }
    /** Defaults to estimated word timing so every synced song sweeps word by word (the web default is native). */
    val lyricWordMode = pref(LyricWordMode.entries.firstOrNull { it.name.lowercase() == prefs.getString("lyric_word_mode", null) } ?: LyricWordMode.Estimate) {
        prefs.edit().putString("lyric_word_mode", it.name.lowercase()).apply()
    }
    val showTranslation = pref(prefs.getBoolean("lyric_translation", true)) { prefs.edit().putBoolean("lyric_translation", it).apply() }
    val showRoman = pref(prefs.getBoolean("lyric_roman", false)) { prefs.edit().putBoolean("lyric_roman", it).apply() }
    val appearance = pref(Appearance.entries.firstOrNull { it.name.lowercase() == prefs.getString("appearance", null) } ?: Appearance.System) {
        prefs.edit().putString("appearance", it.name.lowercase()).apply()
    }
    /** Audio cache limit in bytes; the cache picks it up on the next start. */
    val cacheLimit = pref(prefs.getLong("cache_limit", 2L shl 30)) { prefs.edit().putLong("cache_limit", it).apply() }

    val serverUrl: HttpUrl? get() = MHubClient.serverUrl(serverAddress.value)
}
