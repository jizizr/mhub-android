package io.github.mhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    private fun load(name: String) = Lyrics.build(MHubJson.decodeFromString(LyricsPayload.serializer(), Contract.fixture(name)))

    @Test fun wordTimedLinesWithTranslationAndBackground() {
        val lyrics = load("lyrics-words.json")
        assertTrue(lyrics.hasWordTiming && lyrics.isSynced)
        assertEquals(listOf("有没有人曾告诉你", "(告诉你)", "我很爱你"), lyrics.lines.map { it.text })
        assertEquals("Has anyone ever told you", lyrics.lines[0].translation)
        assertTrue(lyrics.lines[1].isBackground && lyrics.lines[1].translation.isEmpty())
        assertEquals("I love you so", lyrics.lines[2].translation)
        assertEquals(listOf("作词：林夕"), lyrics.credits)
        assertNull(lyrics.activeIndex(500))
        assertEquals(0, lyrics.activeIndex(4500))
        assertEquals(2, lyrics.activeIndex(6000))
    }

    @Test fun lrcWithRepeatedStampsAndRoman() {
        val lyrics = load("lyrics-lrc.json")
        assertTrue(!lyrics.hasWordTiming && lyrics.isSynced)
        assertEquals(listOf("晴天", "故事的小黄花", "从出生那年就飘着", "故事的小黄花"), lyrics.lines.map { it.text })
        assertEquals(listOf(500L, 12_300L, 20_000L, 72_300L), lyrics.lines.map { it.start })
        assertEquals(12_300L, lyrics.lines[0].end)
        assertEquals(77_300L, lyrics.lines[3].end)
        assertEquals("gu shi de xiao huang hua", lyrics.lines[1].roman)
        assertTrue(lyrics.lines[3].roman.isEmpty())
    }

    @Test fun unavailableAndUnsynced() {
        assertTrue(load("lyrics-unavailable.json").isEmpty)
        val plain = Lyrics.build(LyricsPayload(available = true, plain = "第一行\n\n第二行"))
        assertFalse(plain.isSynced)
        assertEquals(listOf(0L, 4000L), plain.lines.map { it.start })
    }
}
