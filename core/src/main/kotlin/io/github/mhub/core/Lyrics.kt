package io.github.mhub.core

import kotlin.math.abs

data class LyricWord(val start: Long, val end: Long, val text: String)

data class LyricLine(
    val id: Int,
    /** Milliseconds. */
    val start: Long,
    val end: Long,
    val words: List<LyricWord>,
    val translation: String = "",
    val roman: String = "",
    /** Background vocals, shown smaller under the preceding main line. */
    val isBackground: Boolean = false,
) {
    val text: String get() = words.joinToString("") { it.text }
}

/** Lyrics as the player shows them; built like the web `buildLyricLines` and iOS `Lyrics.build`. */
data class Lyrics(
    val lines: List<LyricLine>,
    val credits: List<String>,
    /** Lines carry real timestamps (LRC or word timing). */
    val isSynced: Boolean,
    /** Lines carry per-word timing. */
    val hasWordTiming: Boolean,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** Index of the main line playing at [ms], or null before the first line. */
    fun activeIndex(ms: Long): Int? {
        var low = 0
        var high = lines.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].start <= ms) { found = mid; low = mid + 1 } else high = mid - 1
        }
        if (found < 0) return null
        // Background vocals follow their main line; highlight the main line.
        while (found > 0 && lines[found].isBackground && lines[found - 1].end > lines[found].start) found -= 1
        return found
    }

    companion object {
        val Empty = Lyrics(emptyList(), emptyList(), isSynced = false, hasWordTiming = false)

        fun build(payload: LyricsPayload): Lyrics {
            if (!payload.available) return Empty
            val wordLines = payload.words.mapNotNull { line ->
                val words = line.words.map { LyricWord(it.start, it.end, it.text) }
                if (words.joinToString("") { it.text }.isBlank()) null
                else LyricLine(0, line.start, line.end, words, isBackground = line.bg)
            }
            return when {
                wordLines.isNotEmpty() -> finish(wordLines, payload, synced = true, wordTimed = true, sides = true)
                payload.plain.isBlank() -> {
                    // Only a translation exists: show it as the lyrics.
                    val (lines, synced) = parse(payload.translation)
                    finish(lines, payload, synced, wordTimed = false, sides = false)
                }
                else -> {
                    val (lines, synced) = parse(payload.plain)
                    finish(lines, payload, synced, wordTimed = false, sides = true)
                }
            }
        }

        private fun finish(input: List<LyricLine>, payload: LyricsPayload, synced: Boolean, wordTimed: Boolean, sides: Boolean): Lyrics {
            var lines = input
            if (sides) {
                lines = merge(lines, payload.translation) { line, text -> line.copy(translation = text) }
                if (payload.roman.isNotEmpty()) lines = merge(lines, payload.roman) { line, text -> line.copy(roman = text) }
            }
            return Lyrics(lines.mapIndexed { i, line -> line.copy(id = i) }, payload.credits, synced, wordTimed)
        }

        private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        /** Parses LRC (several timestamps per line allowed) or unsynced text. */
        internal fun parse(text: String): Pair<List<LyricLine>, Boolean> {
            val source = text.trim()
            if (source.isEmpty()) return emptyList<LyricLine>() to true
            val timed = mutableListOf<Pair<Long, String>>()
            var anyStamp = false
            for (raw in source.lines()) {
                val matches = stamp.findAll(raw).toList()
                if (matches.isEmpty()) continue
                anyStamp = true
                // Timestamps lead the line; the text follows the last one.
                val body = raw.substring(matches.last().range.last + 1).trim()
                if (body.isEmpty()) continue
                for (match in matches) {
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val fraction = match.groupValues[3]
                    val millis = if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').toLong()
                    timed += (minutes * 60 + seconds) * 1000 + millis to body
                }
            }
            if (anyStamp) {
                timed.sortBy { it.first }
                val lines = timed.mapIndexed { i, (start, body) ->
                    val end = timed.getOrNull(i + 1)?.first ?: (start + 5000)
                    LyricLine(0, start, end, listOf(LyricWord(start, end, body)))
                }
                return lines to true
            }
            val plain = source.lines().map { it.trim() }.filter { it.isNotEmpty() }
            return plain.mapIndexed { i, body ->
                val start = i * 4000L
                LyricLine(0, start, start + 3800, listOf(LyricWord(start, start + 3800, body)))
            } to false
        }

        /** Attaches each side line to the nearest unused main line within a second. */
        internal fun merge(lines: List<LyricLine>, side: String, apply: (LyricLine, String) -> LyricLine): List<LyricLine> {
            val sideLines = parse(side).first
            if (sideLines.isEmpty()) return lines
            val used = HashSet<Int>()
            return lines.map { line ->
                if (line.isBackground) return@map line
                var best = -1
                var distance = 1001L
                sideLines.forEachIndexed { i, candidate ->
                    val delta = abs(candidate.start - line.start)
                    if (i !in used && delta < distance) { best = i; distance = delta }
                }
                if (best >= 0) {
                    used += best
                    apply(line, sideLines[best].text.trim())
                } else line
            }
        }
    }
}

/** 原文 / 简体 / 繁體. The Android app converts with the system ICU transliterator. */
enum class LyricScript(val label: String) {
    Original("原文"),
    Simplified("简体"),
    Traditional("繁體"),
}

/** How word highlighting is produced, the same three modes as the web player. */
enum class LyricWordMode(val label: String) {
    /** Real word timing only; line-only lyrics highlight whole lines. */
    Native("仅原生逐字"),
    /** Real word timing, estimated for lines that have none. */
    Estimate("估算逐字"),
    /** Whole-line highlighting even when word timing exists. */
    Line("整行"),
}

/** Applies a word mode; ports `applyWordMode` from the web player. */
fun Lyrics.applying(mode: LyricWordMode): Lyrics {
    if (!isSynced) return this
    return when (mode) {
        LyricWordMode.Native -> this
        LyricWordMode.Line -> copy(lines = lines.map { line ->
            if (line.words.size <= 1) line else line.copy(words = listOf(LyricWord(line.start, line.end, line.text)))
        })
        LyricWordMode.Estimate -> copy(lines = lines.mapIndexed { index, line ->
            if (line.isBackground || line.words.size != 1) line
            else WordEstimate.estimate(line, lines.drop(index + 1).firstOrNull { !it.isBackground }?.start)
        })
    }
}

/** Spreads a line-only lyric line over its sung units (web `estimateLineWords`). */
object WordEstimate {
    private const val MAX_UNIT_MS = 600.0
    private const val MIN_UNIT_MS = 120.0
    private const val SUNG_SHARE = 0.9
    private val syllabic = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]")
    private val letter = Regex("[\\p{L}\\p{N}'’]")
    private val vowels = Regex("[aeiouyàáâãäåèéêëìíîïòóôõöùúûüāēīōū]+")

    data class Unit(val text: String, val weight: Int)

    fun syllables(word: String) = vowels.findAll(word.lowercase()).count().coerceIn(1, 4)

    /** One unit per CJK/kana/hangul character, one per Latin word; punctuation stays with its neighbour. */
    fun units(text: String): List<Unit> {
        val units = mutableListOf<Unit>()
        var prefix = ""
        val chars = text.codePoints().toArray().map { String(Character.toChars(it)) }
        var index = 0
        while (index < chars.size) {
            val char = chars[index]
            when {
                syllabic.matches(char) -> { units += Unit(prefix + char, 1); prefix = ""; index += 1 }
                letter.matches(char) -> {
                    val word = StringBuilder()
                    while (index < chars.size && letter.matches(chars[index]) && !syllabic.matches(chars[index])) {
                        word.append(chars[index]); index += 1
                    }
                    units += Unit(prefix + word, syllables(word.toString()))
                    prefix = ""
                }
                else -> {
                    if (units.isEmpty()) prefix += char else units[units.lastIndex] = units.last().let { it.copy(text = it.text + char) }
                    index += 1
                }
            }
        }
        if (prefix.isNotEmpty()) units += Unit(prefix, 0)
        return units
    }

    fun estimate(line: LyricLine, nextStart: Long?): LyricLine {
        val units = units(line.text)
        val weight = units.sumOf { it.weight }
        if (units.size < 2 || weight <= 0) return line
        val limit = nextStart?.takeIf { it > line.start } ?: line.end
        val available = (limit - line.start).coerceAtLeast(0) * SUNG_SHARE
        val unit = (available / weight).coerceIn(MIN_UNIT_MS, MAX_UNIT_MS)
        var time = line.start.toDouble()
        return line.copy(words = units.map { item ->
            val start = Math.round(time)
            time += unit * item.weight
            LyricWord(start, maxOf(start + 1, Math.round(time)), item.text)
        })
    }
}
