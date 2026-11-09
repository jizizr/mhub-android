package io.github.mhub.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mhub.core.RecommendationHome

/**
 * The home tab's recommendations. Pages are rebuilt whenever the user leaves a
 * tab, so the data lives here for the process and is only fetched again on
 * 「换一批」 or when the server changes.
 */
class HomeStore {
    var home by mutableStateOf<RecommendationHome?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var selectedTag by mutableStateOf("")
    var seen by mutableStateOf(listOf<String>())
    private var server: String? = null

    fun forServer(address: String) {
        if (server == address) return
        if (server != null) {
            home = null
            error = null
            selectedTag = ""
            seen = emptyList()
        }
        server = address
    }
}
