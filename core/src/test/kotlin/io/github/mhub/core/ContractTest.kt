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
        assertEquals(listOf("applemusic", "netease", "tencent", "bilibili"), platforms.map { it.id })
        assertTrue(platforms[0].hasAlbums && platforms[0].hasArtists)
        assertTrue(platforms[2].hasAlbums && !platforms[2].hasArtists)
        assertTrue(!platforms[3].hasAlbums)
        // Servers before catalog search list no sections.
        val old = MHubJson.decodeFromString(PlatformInfo.serializer(), """{"id":"netease","label":"网易云音乐"}""")
        assertTrue(old.catalog.isEmpty() && !old.hasAlbums)
    }

    @Test fun catalogSearchSections() {
        val found = MHubJson.decodeFromString(CatalogSearch.serializer(), Contract.fixture("catalog-search.json"))
        assertEquals(CatalogSection.entries.toList(), found.visibleSections)
        assertEquals(listOf(CatalogItem.ARTIST, CatalogItem.SONG), found.top.map { it.type })
        val song = found.songs[0].track
        assertEquals("applemusic:536115195", song.id)
        assertEquals("七里香", song.album)
        assertEquals(297_200L, song.durationMs)
        assertEquals("2001 · 周杰伦", found.albums[0].cardSubtitle(withKind = false))
        assertEquals("专辑 · 周杰伦", found.albums[0].cardSubtitle(withKind = true))
        assertTrue(found.playlists[0].url.startsWith("https://music.apple.com/"))
        val songsOnly = MHubJson.decodeFromString(CatalogSearch.serializer(), Contract.fixture("catalog-search-songs-only.json"))
        assertTrue(songsOnly.visibleSections.isEmpty())
        assertEquals(1, songsOnly.songs.size)
    }

    @Test fun albumArtistAndTrackInfo() {
        val album = MHubJson.decodeFromString(AlbumDetail.serializer(), Contract.fixture("album.json"))
        assertEquals("300117743", album.artistId)
        assertEquals(listOf(1, 2), album.discs.map { it.disc })
        assertEquals(listOf(1, 2), album.discs[0].tracks.map { it.value.trackNumber })
        assertEquals(listOf(2), album.discs[1].tracks.map { it.index })
        assertEquals(listOf("2001年9月14日", "3 首歌曲，12 分钟", "℗ 2001 JVR MUSIC INTERNATIONAL LTD."), album.footer)
        val artist = MHubJson.decodeFromString(ArtistDetail.serializer(), Contract.fixture("artist.json"))
        assertEquals("七里香", artist.topSongs.first().track.album)
        assertEquals(1, artist.albums.size)
        assertEquals(1, artist.singles.size)
        val info = MHubJson.decodeFromString(TrackInfo.serializer(), Contract.fixture("track-info.json"))
        assertEquals("003DFRzD192KKD", info.albumId)
        assertTrue(info.artistId.isEmpty())
        assertEquals("2004年", AlbumDetail.releaseText("2004"))
        assertEquals("", AlbumDetail.summary(emptyList()))
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
