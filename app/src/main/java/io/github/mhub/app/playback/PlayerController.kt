package io.github.mhub.app.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.SilenceMediaSource
import io.github.mhub.app.AppModel
import io.github.mhub.app.R
import io.github.mhub.core.ApiException
import io.github.mhub.core.Lyrics
import io.github.mhub.core.MHubJson
import io.github.mhub.core.PlatformInfo
import io.github.mhub.core.PlayQueue
import io.github.mhub.core.QueueSource
import io.github.mhub.core.Quality
import io.github.mhub.core.RateLimit
import io.github.mhub.core.RateLimitPolicy
import io.github.mhub.core.QueueAdvance
import io.github.mhub.core.RepeatMode
import io.github.mhub.core.Track
import io.github.mhub.core.enriched
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Owns the play queue and the system player.
 *
 * Queue logic lives in the shared `PlayQueue`. This class turns queue
 * positions into media items, keeps the next song in ExoPlayer's playlist once
 * the current one is fully cached (gapless, and the media session never loses
 * its player at a song boundary), and serves the MediaSession through
 * [sessionPlayer]. It mirrors iOS `PlayerController`.
 */
@OptIn(UnstableApi::class)
class PlayerController(private val context: Context, private val model: AppModel, private val cache: AudioCache) {
    enum class Phase { Idle, Loading, Playing, Paused, Failed }
    enum class LyricsState { Idle, Loading, Loaded, Failed }
    /** The last AutoPlay request, for the queue's notice (web `autoplayState`). */
    enum class AutoplayStatus { Idle, Loading, Ready, Empty, Unsupported, Error }

    data class State(
        val queue: PlayQueue = PlayQueue(),
        val phase: Phase = Phase.Idle,
        val error: String? = null,
        val durationMs: Long = 0,
        val playingQuality: Quality? = null,
        val lyrics: Lyrics = Lyrics.Empty,
        val lyricsState: LyricsState = LyricsState.Idle,
        val autoplay: AutoplayStatus = AutoplayStatus.Idle,
        /** Where the queue came from (docs/design.md「播放队列」). */
        val source: QueueSource? = null,
        val wantsPlay: Boolean = false,
    ) {
        val current: Track? get() = queue.current
        /** What the play button shows: playing, or about to play. */
        val isPlaying: Boolean get() = phase == Phase.Playing || (phase == Phase.Loading && wantsPlay)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    /** The current song's id alone, for list rows that only highlight it. */
    val currentTrackId: androidx.compose.runtime.State<String?> get() = _currentTrackId
    private val _currentTrackId = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val queue get() = _state.value.queue

    init {
        scope.launch { _state.collect { _currentTrackId.value = it.current?.id } }
    }

    /** Whole songs are buffered (through the cache), so "fully cached" is reached early. */
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(cache.dataSourceFactory))
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 20 * 60_000, 1_000, 2_000)
                .setTargetBufferBytes(96 shl 20)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    /** What the MediaSession exposes: next/previous/play go through the queue. */
    val sessionPlayer: Player = SessionPlayer(player, this)

    private class Entry(val track: Track, val quality: Quality, val url: String, var cached: Boolean)

    private val entries = HashMap<String, Entry>()
    private var currentId: String? = null
    private var prefetchedId: String? = null
    private var generation = 0
    private var itemCounter = 0
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var lyricsJob: Job? = null
    private var autoplayJob: Deferred<Int>? = null
    private var autoplayStation: String? = null
    private val autoplayPlayed = ArrayDeque<String>()
    private var restorePositionMs: Long? = null
    /** The song shown by the media session while a silent placeholder holds it. */
    internal var holdMetadata: MediaMetadata? = null
        private set
    private val lyricsMemory = LinkedHashMap<String, Lyrics>()
    private var persistJob: Job? = null
    private val stateFile = File(context.filesDir, "playback.json")

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = refreshPhase()
            override fun onPlaybackStateChanged(playbackState: Int) {
                refreshPhase()
                if (playbackState == Player.STATE_ENDED) itemEnded()
                checkFullyLoaded()
            }
            override fun onIsLoadingChanged(isLoading: Boolean) = checkFullyLoaded()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) playerAdvanced(mediaItem)
                else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) refreshPhase()
            }
            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(phase = Phase.Failed, error = error.localizedMessage) }
                model.show(error.localizedMessage ?: context.getString(R.string.preparing))
            }
        })
        restore()
        _state.value.current?.let { loadSideContent(it) }
    }

    val positionMs: Long get() = if (currentId == null) restorePositionMs ?: 0 else player.currentPosition
    /** True while the position actually moves (the song is sounding), for frame-driven UI. */
    val isAdvancing: Boolean get() = currentId != null && player.isPlaying

    // Queue actions (same names and semantics as the web player) ------------

    /** Tapping a song: it plays right after the current one. */
    fun playNow(track: Track) {
        if (track.id == queue.current?.id && currentId != null) {
            if (!_state.value.isPlaying) play()
            return
        }
        setQueue(queue.playNow(track))
        resetAutoplayStation()
        load()
    }

    /** "播放" / "随机播放" on a list: replaces the queue. */
    fun play(tracks: List<Track>, start: Int = 0, shuffle: Boolean? = null, source: QueueSource? = null) {
        if (tracks.isEmpty()) return
        setQueue(queue.replace(tracks, start, shuffle))
        _state.update { it.copy(source = source) }
        resetAutoplayStation()
        load()
    }

    fun playNext(tracks: List<Track>) {
        val title = tracks.firstOrNull()?.title.orEmpty()
        val hadCurrent = queue.current != null
        val result = queue.playNext(tracks)
        setQueue(result.queue)
        queueChanged()
        if (tracks.size == 1) {
            model.show(
                when {
                    result.count == 0 -> context.getString(R.string.now_playing_already, title)
                    hadCurrent -> context.getString(R.string.queued_next, title)
                    else -> context.getString(R.string.queued_first, title)
                },
            )
        }
    }

    fun append(tracks: List<Track>) {
        val result = queue.append(tracks)
        setQueue(result.queue)
        queueChanged()
        tracks.singleOrNull()?.let {
            model.show(context.getString(if (result.count > 0) R.string.queued_at_end else R.string.already_queued, it.title))
        }
    }

    fun select(index: Int) {
        setQueue(queue.select(index))
        load()
    }

    fun remove(index: Int) {
        val wasCurrent = index == queue.index
        setQueue(queue.remove(index))
        if (wasCurrent) { if (queue.isEmpty) stop() else load() } else queueChanged()
    }

    fun move(from: Int, to: Int) {
        setQueue(queue.move(from, to))
        queueChanged()
    }

    fun clearQueue() {
        setQueue(queue.clear())
        _state.update { it.copy(source = null, autoplay = AutoplayStatus.Idle) }
        stop()
    }

    // Transport ----------------------------------------------------------

    fun togglePlay() = if (_state.value.isPlaying) pause() else play()

    fun play() {
        _state.update { it.copy(wantsPlay = true) }
        if (queue.current == null) return
        if (currentId == null) {
            // Still preparing (or waiting out the quota): keep that load, just resume.
            if (loadJob?.isActive == true) player.play() else load(seekMs = restorePositionMs)
            return
        }
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun pause() {
        _state.update { it.copy(wantsPlay = false, phase = if (it.phase == Phase.Loading) Phase.Paused else it.phase) }
        player.pause()
        persistSoon()
    }

    fun next() = advance(manual = true)

    /** Restarts the song after three seconds, otherwise goes back one. */
    fun previous() {
        if (positionMs > 3000) {
            seek(0)
            return
        }
        setQueue(queue.previous())
        load()
    }

    fun seek(ms: Long) {
        if (currentId == null) restorePositionMs = ms else player.seekTo(ms)
    }

    /** Replays the current song in another quality, keeping the position. */
    fun reload(quality: Quality) {
        model.settings.quality.set(quality)
        load(seekMs = positionMs)
    }

    // Modes ----------------------------------------------------------------

    fun cycleRepeat() {
        setQueue(queue.cycleRepeat())
        queueChanged()
    }

    fun toggleShuffle() {
        setQueue(queue.toggleShuffle())
        queueChanged()
    }

    fun setAutoplay(enabled: Boolean) {
        setQueue(queue.withAutoplay(enabled))
        resetAutoplayStation()
        queueChanged()
        if (enabled) scope.launch { fetchRecommendations(force = false) }
        else _state.update { it.copy(autoplay = AutoplayStatus.Idle) }
    }

    /** The queue notice's「重试」. */
    fun retryAutoplay() { scope.launch { fetchRecommendations(force = true) } }

    // Loading ----------------------------------------------------------------

    private fun load(seekMs: Long? = null) {
        generation += 1
        val generation = generation
        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedId = null
        entries.clear()
        currentId = null
        restorePositionMs = null
        val track = queue.current ?: run {
            releaseHold()
            player.clearMediaItems()
            _state.update { it.copy(phase = Phase.Idle) }
            return
        }
        holdSession(track)
        val quality = model.settings.quality.value
        _state.update {
            it.copy(phase = Phase.Loading, wantsPlay = true, error = null, durationMs = track.durationMs ?: 0, playingQuality = quality)
        }
        loadSideContent(track)
        persistSoon()
        // A song the listener asked for waits out the server's quota and plays
        // when allowed; it never fails over to the next song.
        val policy = RateLimitPolicy.playback { ms ->
            if (generation == this.generation) model.show(context.getString(R.string.playback_rate_limited, RateLimit.seconds(ms)))
        }
        loadJob = scope.launch {
            try {
                val (item, entry) = makeItem(track, quality, policy)
                if (generation != this@PlayerController.generation) return@launch
                entries[item.mediaId] = entry
                currentId = item.mediaId
                releaseHold()
                player.setMediaItem(item, seekMs ?: 0)
                player.prepare()
                if (_state.value.wantsPlay) player.play() else _state.update { it.copy(phase = Phase.Paused) }
                trackBecameCurrent(entry)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (generation != this@PlayerController.generation) return@launch
                releaseHold()
                player.clearMediaItems()
                val message = error.message ?: context.getString(R.string.preparing)
                _state.update { it.copy(phase = Phase.Failed, error = message) }
                model.show(message)
            }
        }
    }

    /** A complete cached copy plays directly (offline too); otherwise the server prepares the song. */
    private suspend fun makeItem(track: Track, quality: Quality, rateLimit: RateLimitPolicy): Pair<MediaItem, Entry> {
        val key = track.cacheKey(quality)
        cache.complete(key)?.let { cached ->
            val known = cached.track.takeIf { it.cover.isNotEmpty() && track.cover.isEmpty() }?.let { track.copy(cover = it.cover) } ?: track
            return mediaItem(known, quality, cached.url, key) to Entry(known, quality, cached.url, cached = true)
        }
        val client = model.client ?: throw ApiException.InvalidServer()
        val prepared = client.preparePlayback(track, quality, rateLimit)
        val enriched = track.enriched(prepared.task, client.server)
        if (enriched != track) {
            setQueue(queue.update(enriched))
            if (enriched.cover != track.cover && queue.current?.id == track.id) persistSoon()
        }
        return mediaItem(enriched, quality, prepared.url, key) to Entry(enriched, quality, prepared.url, cached = false)
    }

    private fun mediaItem(track: Track, quality: Quality, url: String, key: String): MediaItem {
        itemCounter += 1
        return MediaItem.Builder()
            .setMediaId("${track.id}|${quality.wire}|$itemCounter")
            .setUri(Uri.parse(url))
            .setCustomCacheKey(key)
            .setMediaMetadata(metadata(track))
            .build()
    }

    private fun metadata(track: Track): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
        .setAlbumTitle(track.album.ifEmpty { null })
        .setArtworkUri(track.cover.takeIf { it.isNotEmpty() }?.let(Uri::parse))
        .setDurationMs(track.durationMs)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .build()

    /**
     * While a song is prepared (or waits out the server's quota) ExoPlayer loops
     * a silent placeholder, so the media session, its notification and the
     * foreground service survive on the lock screen; the session shows [track].
     */
    @OptIn(UnstableApi::class)
    private fun holdSession(track: Track) {
        holdMetadata = metadata(track)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.setMediaSource(SilenceMediaSource.Factory().setDurationUs(5_000_000).createMediaSource())
        player.prepare()
        player.play()
    }

    private fun releaseHold() {
        holdMetadata = null
        player.repeatMode = Player.REPEAT_MODE_OFF
    }

    private fun trackBecameCurrent(entry: Entry) {
        model.recordRecent(entry.track)
        if (entry.track.isRecommendation) {
            autoplayPlayed.addLast(entry.track.sourceId)
            while (autoplayPlayed.size > 200) autoplayPlayed.removeFirst()
        }
        persistSoon()
        scope.launch { fetchRecommendations(force = false) }
        if (entry.cached) schedulePrefetch()
    }

    /** ExoPlayer buffered the whole current song through the cache. */
    private fun checkFullyLoaded() {
        val id = currentId ?: return
        val entry = entries[id] ?: return
        if (entry.cached || player.isLoading) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || player.bufferedPosition < duration - 500) return
        entry.cached = true
        cache.markComplete(entry.track.cacheKey(entry.quality), entry.url, entry.track, entry.quality)
        schedulePrefetch()
    }

    // Next song ------------------------------------------------------------

    /**
     * The next song is resolved only after the current one is fully cached,
     * then added to ExoPlayer's playlist so the system plays it without a gap.
     */
    private fun schedulePrefetch() {
        if (prefetchedId != null || prefetchJob?.isActive == true || queue.repeatMode == RepeatMode.One) return
        val nextIndex = queue.naturalNextIndex ?: return
        if (nextIndex == queue.index || currentId == null) return
        val track = queue.items[nextIndex]
        val quality = model.settings.quality.value
        val generation = generation
        val current = currentId
        fun stillNext() = generation == this.generation && currentId == current &&
            queue.naturalNextIndex?.let { queue.items[it].id } == track.id
        prefetchJob = scope.launch {
            var attempt = 0
            while (true) {
                try {
                    // Over quota a prefetch gives up at once, silently.
                    val (item, entry) = makeItem(track, quality, RateLimitPolicy.Prefetch)
                    if (!stillNext()) return@launch
                    entries[item.mediaId] = entry
                    prefetchedId = item.mediaId
                    player.addMediaItem(item)
                    return@launch
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    // Retry with backoff, never sooner than the server's Retry-After.
                    val retryAfter = (error as? ApiException.RateLimited)?.retryAfterMs ?: 0
                    delay(RateLimit.prefetchDelayMs(attempt++, retryAfter))
                    if (!stillNext()) return@launch
                }
            }
        }
    }

    private fun dropPrefetched() {
        prefetchJob?.cancel()
        val id = prefetchedId ?: return
        prefetchedId = null
        entries.remove(id)
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (player.getMediaItemAt(i).mediaId == id) player.removeMediaItem(i)
        }
    }

    /** After a queue edit the prefetched song may no longer be next. */
    private fun queueChanged() {
        persistSoon()
        val nextId = if (queue.repeatMode == RepeatMode.One) null else queue.naturalNextIndex?.let { queue.items[it].id }
        val prefetchedTrack = prefetchedId?.let { entries[it]?.track?.id }
        if (prefetchedId != null && prefetchedTrack != nextId) dropPrefetched()
        if (currentId?.let { entries[it]?.cached } == true) schedulePrefetch()
        scope.launch { fetchRecommendations(force = false) }
    }

    /** ExoPlayer moved on to the prefetched item by itself. */
    private fun playerAdvanced(item: MediaItem?) {
        val id = item?.mediaId ?: return
        if (id != prefetchedId) return
        val entry = entries[id] ?: return
        val position = queue.items.indexOfFirst { it.id == entry.track.id }
        if (position < 0) return
        currentId?.let { old ->
            entries.remove(old)
            if (player.mediaItemCount > 1 && player.getMediaItemAt(0).mediaId == old) player.removeMediaItem(0)
        }
        prefetchedId = null
        currentId = id
        setQueue(queue.select(position))
        _state.update { it.copy(durationMs = entry.track.durationMs ?: 0, playingQuality = entry.quality) }
        loadSideContent(entry.track)
        trackBecameCurrent(entry)
    }

    private fun itemEnded() {
        if (prefetchedId != null) return
        advance(manual = false)
    }

    private fun advance(manual: Boolean) {
        val step = queue.next(manual)
        when (step.result) {
            is QueueAdvance.Index -> {
                setQueue(step.queue)
                load()
            }
            QueueAdvance.Ended -> {
                player.pause()
                _state.update { it.copy(wantsPlay = false, phase = Phase.Paused) }
            }
            QueueAdvance.NeedsRecommendations -> {
                _state.update { it.copy(phase = Phase.Loading) }
                scope.launch {
                    val added = fetchRecommendations(force = true)
                    val again = queue.next(manual)
                    if (added > 0 && again.result is QueueAdvance.Index) {
                        setQueue(again.queue)
                        load()
                    } else {
                        _state.update { it.copy(phase = Phase.Paused, wantsPlay = false) }
                        model.show(context.getString(R.string.no_more_recommendations))
                    }
                }
            }
        }
    }

    private fun stop() {
        generation += 1
        loadJob?.cancel()
        prefetchJob?.cancel()
        prefetchedId = null
        entries.clear()
        currentId = null
        releaseHold()
        player.clearMediaItems()
        _state.update { it.copy(phase = Phase.Idle, wantsPlay = false, durationMs = 0, lyrics = Lyrics.Empty) }
        persistSoon()
    }

    // AutoPlay -------------------------------------------------------------

    private fun resetAutoplayStation() {
        autoplayStation = null
        autoplayJob = null
    }

    /** Requests an Apple Music AutoPlay batch when two or fewer songs remain. */
    private suspend fun fetchRecommendations(force: Boolean): Int {
        val seed = queue.current ?: return 0
        val client = model.client ?: return 0
        if (!queue.autoplay) return 0
        if (seed.platform !in PlatformInfo.autoplayPlatforms) {
            _state.update { it.copy(autoplay = AutoplayStatus.Unsupported) }
            return 0
        }
        if (!force && queue.upcoming.size > 2) return 0
        autoplayJob?.takeIf { it.isActive }?.let { return it.await().coerceAtLeast(0) }
        val exclude = queue.items.filter { it.platform == seed.platform }.map { it.sourceId } + autoplayPlayed
        val seeds = queue.autoplaySeeds()
        val station = autoplayStation
        _state.update { it.copy(autoplay = AutoplayStatus.Loading) }
        val job = scope.async {
            val result = runCatching { client.continuation(seed.platform, seed.sourceId, seeds, station, exclude) }.getOrNull()
                ?: return@async -1
            if (!queue.autoplay || queue.current?.id != seed.id) return@async 0
            result.station?.let { autoplayStation = it }
            val appended = queue.appendRecommendations(result.tracks.filter { it.platform == seed.platform }.map { it.track })
            setQueue(appended.queue)
            if (appended.count > 0) queueChanged()
            appended.count
        }
        autoplayJob = job
        val added = job.await()
        val status = when {
            added < 0 -> AutoplayStatus.Error
            added == 0 && queue.autoplay -> AutoplayStatus.Empty
            added == 0 -> AutoplayStatus.Idle
            else -> AutoplayStatus.Ready
        }
        _state.update { it.copy(autoplay = status) }
        return added.coerceAtLeast(0)
    }

    // Lyrics -----------------------------------------------------------------

    private fun loadSideContent(track: Track) {
        lyricsJob?.cancel()
        lyricsMemory[track.id]?.let { cached ->
            _state.update { it.copy(lyrics = cached, lyricsState = LyricsState.Loaded) }
            return
        }
        _state.update { it.copy(lyrics = Lyrics.Empty, lyricsState = LyricsState.Loading) }
        val client = model.client ?: run {
            _state.update { it.copy(lyricsState = LyricsState.Failed) }
            return
        }
        lyricsJob = scope.launch {
            val built = runCatching { Lyrics.build(client.lyrics(track)) }.getOrNull()
            if (queue.current?.id != track.id) return@launch
            if (built == null) {
                _state.update { it.copy(lyricsState = LyricsState.Failed) }
                return@launch
            }
            if (lyricsMemory.size > 60) lyricsMemory.clear()
            lyricsMemory[track.id] = built
            _state.update { it.copy(lyrics = built, lyricsState = LyricsState.Loaded) }
        }
    }

    // State ------------------------------------------------------------------

    private fun setQueue(next: PlayQueue) = _state.update { it.copy(queue = next) }

    private fun refreshPhase() {
        if (currentId == null) return
        val phase = when {
            player.isPlaying -> Phase.Playing
            player.playbackState == Player.STATE_BUFFERING && _state.value.wantsPlay -> Phase.Loading
            player.playerError != null -> Phase.Failed
            else -> Phase.Paused
        }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: _state.value.durationMs
        _state.update { it.copy(phase = phase, durationMs = duration) }
    }

    @Serializable
    private data class Saved(val queue: PlayQueue, val position: Long, val source: QueueSource? = null)

    private fun restore() {
        val saved = runCatching { MHubJson.decodeFromString(Saved.serializer(), stateFile.readText()) }.getOrNull() ?: return
        restorePositionMs = saved.position
        _state.update {
            it.copy(
                queue = saved.queue,
                source = saved.source,
                phase = if (saved.queue.current == null) Phase.Idle else Phase.Paused,
                durationMs = saved.queue.current?.durationMs ?: 0,
                // The quality the restored song will load in.
                playingQuality = if (saved.queue.current != null) model.settings.quality.value else null,
            )
        }
    }

    private fun persistSoon() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(400)
            persistNow()
        }
    }

    fun persistNow() {
        val saved = Saved(queue, positionMs, _state.value.source)
        runCatching { stateFile.writeText(MHubJson.encodeToString(Saved.serializer(), saved)) }
    }
}
