package io.github.mhub.core

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Runs the estimate cases generated from the web player (`contract/lyrics-estimate-cases.json`). */
class WordEstimateTest {
    @Test fun sharedCases() {
        val cases = MHubJson.parseToJsonElement(File(Contract.root, "lyrics-estimate-cases.json").readText()).jsonObject["cases"]!!.jsonArray
        assertEquals(true, cases.size >= 5)
        for (element in cases) {
            val c = element.jsonObject
            val text = c["text"]!!.jsonPrimitive.content
            val start = c["start"]!!.jsonPrimitive.long
            val end = c["end"]!!.jsonPrimitive.long
            val line = LyricLine(0, start, end, listOf(LyricWord(start, end, text)))
            val words = WordEstimate.estimate(line, c["next"]!!.jsonPrimitive.long).words
            val expect = c["expect"]!!.jsonArray.map { it.jsonArray }
            val name = c["name"]!!.jsonPrimitive.content
            assertEquals(name, expect.map { it[0].jsonPrimitive.int.toLong() }, words.map { it.start })
            assertEquals(name, expect.map { it[1].jsonPrimitive.int.toLong() }, words.map { it.end })
            assertEquals(name, expect.map { it[2].jsonPrimitive.content }, words.map { it.text })
        }
    }

    @Test fun modesKeepRealTimingAndBackground() {
        val words = Lyrics.build(MHubJson.decodeFromString(LyricsPayload.serializer(), Contract.fixture("lyrics-words.json")))
        assertEquals(words, words.applying(LyricWordMode.Estimate))
        assertEquals(1, words.applying(LyricWordMode.Line).lines[0].words.size)
        val lrc = Lyrics.build(MHubJson.decodeFromString(LyricsPayload.serializer(), Contract.fixture("lyrics-lrc.json"))).applying(LyricWordMode.Estimate)
        assertEquals(listOf("晴", "天"), lrc.lines[0].words.map { it.text })
    }
}
