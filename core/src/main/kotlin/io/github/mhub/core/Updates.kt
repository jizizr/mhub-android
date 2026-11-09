package io.github.mhub.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Semantic version order (`contract/version-cases.json`); a leading `v` and build metadata are ignored. */
data class AppVersion(val core: List<Int>, val prerelease: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (index in 0 until maxOf(core.size, other.core.size)) {
            val difference = core.getOrElse(index) { 0 }.compareTo(other.core.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            // A release is newer than any of its pre-releases.
            return other.prerelease.size.coerceAtMost(1) - prerelease.size.coerceAtMost(1)
        }
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            val difference = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (difference != 0) return difference
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString() = core.joinToString(".") + if (prerelease.isEmpty()) "" else "-" + prerelease.joinToString(".")

    companion object {
        fun parse(text: String): AppVersion? {
            val value = text.trim().removePrefix("v").substringBefore('+')
            val main = value.substringBefore('-')
            val core = main.split('.').map { it.toIntOrNull() ?: return null }
            if (core.isEmpty()) return null
            val prerelease = if ('-' in value) value.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
            return AppVersion(core, prerelease)
        }
    }
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("html_url") val page: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<Asset> = emptyList(),
) {
    @Serializable
    data class Asset(val name: String, @SerialName("browser_download_url") val url: String)
}

/** A release newer than the running app. */
data class AvailableUpdate(val version: AppVersion, val notes: String, val page: String, val download: String?)

object UpdateCheck {
    /** The newest published release (pre-releases included) if it is newer than [current]. */
    fun newer(releases: List<GitHubRelease>, current: String, assetSuffix: String): AvailableUpdate? {
        val running = AppVersion.parse(current) ?: return null
        val latest = releases.filter { !it.draft }
            .mapNotNull { release -> AppVersion.parse(release.tag)?.let { it to release } }
            .maxByOrNull { it.first } ?: return null
        if (latest.first <= running) return null
        val release = latest.second
        return AvailableUpdate(
            version = latest.first,
            notes = release.body.orEmpty().trim(),
            page = release.page,
            download = release.assets.firstOrNull { it.name.endsWith(assetSuffix) }?.url,
        )
    }

    /** The first [lines] lines of release notes as plain text (Markdown links, marks and headings removed). */
    fun summary(notes: String, lines: Int = 6): String = notes.lines()
        .map { line ->
            line.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
                .replace(Regex("[`*_]"), "")
                .replace(Regex("^\\s*#+\\s*"), "")
                .replace(Regex("^\\s*[-+]\\s+"), "• ")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .take(lines)
        .joinToString("\n")

    fun decode(text: String): List<GitHubRelease> = MHubJson.decodeFromString(ListSerializer(GitHubRelease.serializer()), text)

    /** Asks GitHub for the repository's recent releases. */
    suspend fun fetch(repository: String, http: OkHttpClient = MHubClient.defaultHttp): List<GitHubRelease> {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repository/releases?per_page=10")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MHub-App")
            .build()
        val client = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw ApiException.Http(response.code, "")
                decode(response.body.string())
            }
        }
    }
}
