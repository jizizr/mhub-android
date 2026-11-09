package io.github.mhub.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
enum class RepeatMode {
    @SerialName("off") Off,
    @SerialName("all") All,
    @SerialName("one") One,
}

/** What [PlayQueue.next] decided. */
sealed interface QueueAdvance {
    data class Index(val index: Int) : QueueAdvance
    data object Ended : QueueAdvance
    /** At the end with AutoPlay on: fetch, [PlayQueue.appendRecommendations], then call next again. */
    data object NeedsRecommendations : QueueAdvance
}

/**
 * The play queue and its modes, as an immutable value. Behaviour matches the
 * web player and iOS `PlayQueue`; `contract/queue-cases.json` holds the
 * cases both test suites run. Every operation returns the new queue.
 */
@Serializable
data class PlayQueue(
    val items: List<Track> = emptyList(),
    /** Order before shuffling; additions made while shuffled are recorded too. */
    @SerialName("original") private val original: List<Track>? = null,
    val index: Int = -1,
    @SerialName("repeat_mode") val repeatMode: RepeatMode = RepeatMode.Off,
    val autoplay: Boolean = false,
) {
    val shuffled: Boolean get() = original != null
    val current: Track? get() = items.getOrNull(index)
    val isEmpty: Boolean get() = items.isEmpty()
    val upcoming: List<Track> get() = if (index + 1 < items.size) items.subList(index + 1, items.size) else emptyList()

    fun position(track: Track): Int = items.indexOfFirst { it.id == track.id }

    /** Where a natural end of the current song leads, without changing anything. */
    val naturalNextIndex: Int?
        get() = when {
            items.isEmpty() -> null
            repeatMode == RepeatMode.One -> if (index >= 0) index else 0
            index + 1 < items.size -> index + 1
            repeatMode == RepeatMode.All -> 0
            else -> null
        }

    companion object {
        fun of(items: List<Track>, index: Int, repeatMode: RepeatMode = RepeatMode.Off, autoplay: Boolean = false): PlayQueue {
            val unique = items.uniqued()
            return PlayQueue(unique, null, if (unique.isEmpty()) -1 else index.coerceIn(-1, unique.size - 1), repeatMode, autoplay)
        }
    }

    // Editing -------------------------------------------------------------

    /** "播放全部": replaces the queue. With shuffle on, a random song starts. */
    fun replace(tracks: List<Track>, start: Int = 0, shuffle: Boolean? = null, random: Random = Random): PlayQueue {
        val wantsShuffle = shuffle ?: shuffled
        val list = tracks.map { it.chosen }.uniqued()
        if (list.isEmpty()) return copy(items = emptyList(), original = null, index = -1)
        val startTrack = tracks.getOrNull(start) ?: list[0]
        var next = copy(items = list, original = null, index = list.indexOfFirst { it.id == startTrack.id }.coerceAtLeast(0))
        if (wantsShuffle) next = next.shuffleKeeping(random.nextInt(list.size), random).copy(index = 0)
        return next
    }

    /** "立即播放": the song goes right after the current one and becomes current. */
    fun playNow(track: Track): PlayQueue {
        val item = track.chosen
        val after = index + 1
        val existing = position(item)
        return if (existing >= 0) {
            val updated = copy(items = items.replaceAt(existing, item), original = original?.replaceById(item))
            val moved = updated.move(existing, after.coerceIn(0, items.size - 1))
            moved.copy(index = moved.position(item))
        } else {
            val at = after.coerceIn(0, items.size)
            copy(items = items.insertAt(at, item), original = original?.plus(item), index = at)
        }
    }

    data class Result(val queue: PlayQueue, val count: Int)

    /** "添加到播放队列": appends before the AutoPlay tail. Upcoming recommendations added again become chosen songs. */
    fun append(tracks: List<Track>): Result {
        var queue = this
        var insertion = items.size
        val firstAuto = items.indices.firstOrNull { it > index && items[it].isRecommendation }
        if (firstAuto != null) insertion = minOf(insertion, firstAuto)
        var added = 0
        for (track in tracks.uniqued()) {
            val item = track.chosen
            val existing = queue.position(item)
            if (existing >= 0) {
                if (existing > queue.index && queue.items[existing].isRecommendation) {
                    queue = queue.copy(items = queue.items.replaceAt(existing, item), original = queue.original?.replaceById(item))
                    queue = queue.move(existing, insertion.coerceIn(0, queue.items.size - 1))
                    insertion += 1
                    added += 1
                }
                continue
            }
            val newIndex = if (insertion <= queue.index) queue.index + 1 else queue.index
            queue = queue.copy(items = queue.items.insertAt(insertion, item), original = queue.original?.plus(item), index = newIndex)
            insertion += 1
            added += 1
        }
        return Result(queue, added)
    }

    /** "播放下一首": inserts after the current song, moving songs already queued. The current song never moves. */
    fun playNext(tracks: List<Track>): Result {
        val currentId = current?.id
        val next = tracks.map { it.chosen }.uniqued().filter { it.id != currentId }
        if (next.isEmpty()) return Result(this, 0)
        val ids = next.map { it.id }.toSet()
        fun arrange(list: List<Track>): List<Track> {
            val remaining = list.filter { it.id !in ids }.toMutableList()
            val at = remaining.indexOfFirst { it.id == currentId }.let { if (it >= 0) it + 1 else 0 }
            remaining.addAll(at, next)
            return remaining
        }
        val arranged = arrange(items)
        return Result(
            copy(
                items = arranged,
                original = original?.let(::arrange),
                index = currentId?.let { id -> arranged.indexOfFirst { it.id == id } } ?: -1,
                repeatMode = if (repeatMode == RepeatMode.One) RepeatMode.All else repeatMode,
            ),
            next.size,
        )
    }

    fun remove(at: Int): PlayQueue {
        if (at !in items.indices) return this
        val removed = items[at]
        val list = items.toMutableList().apply { removeAt(at) }
        val newIndex = when {
            list.isEmpty() -> -1
            at < index -> index - 1
            index >= list.size -> list.size - 1
            else -> index
        }
        return copy(items = list, original = original?.filter { it.id != removed.id }, index = newIndex)
    }

    /** Moving a song is a user choice: a moved recommendation stops being one. */
    fun move(from: Int, to: Int): PlayQueue {
        if (from == to || from !in items.indices || to !in items.indices) return this
        val list = items.toMutableList()
        var item = list.removeAt(from)
        var originalList = original
        if (item.isRecommendation) {
            item = item.chosen
            originalList = originalList?.replaceById(item)
        }
        list.add(to, item)
        val newIndex = when {
            index == from -> to
            from < index && to >= index -> index - 1
            from > index && to <= index -> index + 1
            else -> index
        }
        return copy(items = list, original = originalList, index = newIndex)
    }

    fun clear() = copy(items = emptyList(), original = null, index = -1)

    fun select(position: Int) = if (position in items.indices) copy(index = position) else this

    /** Refreshes a song's metadata without changing its position or AutoPlay flag. */
    fun update(track: Track): PlayQueue {
        fun refresh(list: List<Track>) = list.map { if (it.id == track.id) track.copy(isRecommendation = it.isRecommendation) else it }
        return copy(items = refresh(items), original = original?.let(::refresh))
    }

    // Navigation ----------------------------------------------------------

    data class Advance(val queue: PlayQueue, val result: QueueAdvance)

    fun next(manual: Boolean = false, random: Random = Random): Advance {
        if (items.isEmpty()) return Advance(this, QueueAdvance.Ended)
        if (index < 0) return Advance(copy(index = 0), QueueAdvance.Index(0))
        if (repeatMode == RepeatMode.One && !manual) return Advance(this, QueueAdvance.Index(index))
        if (index + 1 < items.size) return Advance(copy(index = index + 1), QueueAdvance.Index(index + 1))
        if (autoplay) return Advance(this, QueueAdvance.NeedsRecommendations)
        if (repeatMode != RepeatMode.Off || manual) {
            val wrapped = if (shuffled && items.size > 1) shuffleKeeping(index, random).copy(index = 1) else copy(index = 0)
            return Advance(wrapped, QueueAdvance.Index(wrapped.index))
        }
        return Advance(this, QueueAdvance.Ended)
    }

    fun previous(): PlayQueue = if (items.isEmpty()) this else copy(index = if (index > 0) index - 1 else items.size - 1)

    // Modes ---------------------------------------------------------------

    /** 全部 → 单曲 → 关闭. Repeat and AutoPlay exclude each other. */
    fun cycleRepeat(): PlayQueue {
        val mode = when (repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        return withRepeat(mode)
    }

    fun withRepeat(mode: RepeatMode): PlayQueue {
        val next = copy(repeatMode = mode)
        return if (mode != RepeatMode.Off) next.withAutoplay(false) else next
    }

    fun withAutoplay(enabled: Boolean): PlayQueue {
        if (enabled) return copy(autoplay = true, repeatMode = RepeatMode.Off)
        val drop = items.indices.filter { it > index && items[it].isRecommendation }.map { items[it].id }.toSet()
        return copy(autoplay = false, items = items.filter { it.id !in drop }, original = original?.filter { it.id !in drop })
    }

    fun toggleShuffle(random: Random = Random): PlayQueue {
        val next = if (original != null) {
            val currentId = current?.id
            var restored = original
            if (autoplay && currentId != null) {
                val at = restored.indexOfFirst { it.id == currentId }
                if (at >= 0) {
                    val upcoming = restored.subList(at + 1, restored.size)
                    restored = restored.subList(0, at + 1) + upcoming.filter { !it.isRecommendation } + upcoming.filter { it.isRecommendation }
                }
            }
            val newIndex = currentId?.let { id -> restored.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
                ?: if (restored.isEmpty()) -1 else 0
            copy(items = restored, original = null, index = newIndex)
        } else {
            if (items.isEmpty()) return this
            shuffleKeeping(maxOf(index, 0), random).copy(index = 0)
        }
        return if (autoplay) next.copy(repeatMode = RepeatMode.Off) else next
    }

    /** Adds AutoPlay results to the end, skipping songs already queued. */
    fun appendRecommendations(tracks: List<Track>): Result {
        val known = items.map { it.id }.toHashSet()
        val added = tracks.filter { known.add(it.id) }.map { it.copy(isRecommendation = true) }
        return Result(copy(items = items + added, original = original?.plus(added)), added.size)
    }

    /** The latest played songs of the current platform, oldest first. */
    fun autoplaySeeds(limit: Int = 10): List<String> {
        val seed = current ?: return emptyList()
        return items.take(maxOf(index, 0) + 1).filter { it.platform == seed.platform }.map { it.sourceId }.takeLast(limit)
    }

    // Private -------------------------------------------------------------

    private fun shuffleKeeping(keep: Int, random: Random): PlayQueue {
        val base = original ?: items
        if (items.size <= 1) return copy(original = base)
        val safe = if (keep in items.indices) keep else 0
        val first = items[safe]
        val rest = items.filterIndexed { i, _ -> i != safe }
        val shuffledItems = if (autoplay) {
            listOf(first) + rest.filter { !it.isRecommendation }.shuffled(random) + rest.filter { it.isRecommendation }.shuffled(random)
        } else {
            listOf(first) + rest.shuffled(random)
        }
        return copy(items = shuffledItems, original = base)
    }
}

private fun <T> List<T>.insertAt(at: Int, item: T): List<T> = toMutableList().apply { add(at, item) }
private fun <T> List<T>.replaceAt(at: Int, item: T): List<T> = toMutableList().apply { set(at, item) }
private fun List<Track>.replaceById(item: Track): List<Track> = map { if (it.id == item.id) item else it }
