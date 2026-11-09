package io.github.mhub.core

import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {
    private val server = "https://mhub.example.test".toHttpUrl()

    @Test fun searchAsTracks() {
        val items = MHubJson.decodeFromString(ListSerializer(SearchItem.serializer()), Contract.fixture("search.json"))
        assertEquals(2, items.size)
        assertEquals("applemusic:1440818839", items[0].track.id)
        assertEquals("陈奕迅", items[0].track.artist)
        assertEquals("", items[1].track.cover)
    }

    @Test fun homeContinuationCollectionPlatforms() {
        val home = MHubJson.decodeFromString(RecommendationHome.serializer(), Contract.fixture("recommendation-home.json"))
        assertEquals(listOf("pl.1"), home.tags.first().playlistIds)
        assertEquals("playlists", home.playlists.first().resourceType)
        val next = MHubJson.decodeFromString(Continuation.serializer(), Contract.fixture("continuation.json"))
        assertEquals("ra.autoplay.abc", next.station)
        val collection = MHubJson.decodeFromString(Collection.serializer(), Contract.fixture("collection.json"))
        assertEquals("热歌榜", collection.title)
        val platforms = MHubJson.decodeFromString(ListSerializer(PlatformInfo.serializer()), Contract.fixture("platforms.json"))
        assertEquals(listOf("applemusic", "netease", "tencent"), platforms.map { it.id })
    }

    @Test fun progressiveStreamingIsPlayable() {
        val response = MHubJson.decodeFromString(CreatePlaybackResponse.serializer(), Contract.fixture("playback-streaming.json"))
        assertEquals(30_123_456L, response.playback.streamLength)
        assertEquals(
            PlaybackReadiness.Ready("https://mhub.example.test/api/v1/playbacks/netease--186016--lossless/audio?rev=native-v2"),
            PlaybackReadiness.evaluate(response.playback, server),
        )
    }

    @Test fun remuxedStreamingWaits() {
        val task = MHubJson.decodeFromString(PlaybackTask.serializer(), Contract.fixture("playback-remux-streaming.json"))
        assertEquals(PlaybackReadiness.Wait("streaming"), PlaybackReadiness.evaluate(task, server))
    }

    @Test fun processingWaitsAndKeepsMediaOrigin() {
        val task = MHubJson.decodeFromString(PlaybackTask.serializer(), Contract.fixture("playback-processing.json"))
        assertEquals(PlaybackReadiness.Wait("processing"), PlaybackReadiness.evaluate(task, server))
        assertEquals(
            PlaybackReadiness.Ready("https://media.example.test/api/v1/playbacks/applemusic--1440818839--high/audio?rev=native-v3"),
            PlaybackReadiness.evaluate(task.copy(status = "completed"), server),
        )
    }

    @Test fun completedAndFailed() {
        val done = MHubJson.decodeFromString(PlaybackTask.serializer(), Contract.fixture("playback-completed.json"))
        assertEquals(246L, done.duration)
        assertTrue(PlaybackReadiness.evaluate(done, server) is PlaybackReadiness.Ready)
        val failed = MHubJson.decodeFromString(PlaybackTask.serializer(), Contract.fixture("playback-failed.json"))
        assertEquals(PlaybackReadiness.Failed("该歌曲需要付费"), PlaybackReadiness.evaluate(failed, server))
        // No source can ever play it: players skip it instead of retrying.
        val unplayable = MHubJson.decodeFromString(PlaybackTask.serializer(), Contract.fixture("playback-unplayable.json"))
        assertEquals(PlaybackReadiness.Failed("Apple Music 暂无可用音源", unplayable = true), PlaybackReadiness.evaluate(unplayable, server))
    }

    @Test fun serverAddressParsing() {
        assertEquals("https://music.example.test", MHubClient.serverUrl(" music.example.test/ ")?.let(MHubClient::origin))
        assertEquals("http://127.0.0.1:8000", MHubClient.serverUrl("http://127.0.0.1:8000")?.let(MHubClient::origin))
        assertNull(MHubClient.serverUrl("ftp://x.test"))
        assertNull(MHubClient.serverUrl("https://user:pw@x.test"))
        assertNull(MHubClient.serverUrl(""))
        assertNull(MHubClient.resolve("//evil.test/a", server))
        assertNull(MHubClient.resolve("javascript:alert(1)", server))
    }

    @Test fun trackJsonRoundTrips() {
        val track = Track("netease", "1", "t", "a", durationMs = 1000, isRecommendation = true)
        assertEquals(track, MHubJson.decodeFromString(Track.serializer(), MHubJson.encodeToString(Track.serializer(), track)))
    }

    @Test fun shareAndImportLinks() {
        assertEquals("https://mhub.example.test/?song=netease:1", Links.shareUrl(Track("netease", "1"), server))
        assertNull(Links.shareUrl(Track("bilibili", "1"), server))
        assertEquals("applemusic", Links.collectionPlatform("https://music.apple.com/cn/playlist/x"))
        assertEquals("netease", Links.collectionPlatform("https://music.163.com/playlist?id=1"))
    }
}
