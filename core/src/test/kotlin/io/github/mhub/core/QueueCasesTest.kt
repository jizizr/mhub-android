package io.github.mhub.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Runs the queue cases shared with iOS (`contract/queue-cases.json`). */
class QueueCasesTest {
    private fun track(token: String): Track {
        val recommended = token.endsWith("*")
        val id = token.removeSuffix("*")
        return Track("t", id, id, isRecommendation = recommended)
    }

    private fun token(track: Track) = track.sourceId + if (track.isRecommendation) "*" else ""

    private fun JsonElement.strings() = jsonArray.map { it.jsonPrimitive.content }

    private fun repeat(value: String?) = when (value) { "all" -> RepeatMode.All; "one" -> RepeatMode.One; else -> RepeatMode.Off }

    @Test fun sharedCases() {
        val cases = MHubJson.parseToJsonElement(File(Contract.root, "queue-cases.json").readText()).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.size >= 30)
        for (element in cases) {
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            runCatching { run(case) }.onFailure { throw AssertionError("case failed: $name", it) }
        }
    }

    private fun run(case: JsonObject) {
        val initial = case["initial"]!!.jsonObject
        var queue = PlayQueue.of(
            initial["queue"]!!.strings().map(::track),
            initial["index"]!!.jsonPrimitive.int,
            repeat(initial["repeat"]?.jsonPrimitive?.content),
            initial["autoplay"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
        val random = Random(7)
        var result: String? = null
        var count: Int? = null
        for (opElement in case["ops"]!!.jsonArray) {
            val op = opElement.jsonObject
            val tracks = op["tracks"]?.strings()?.map(::track).orEmpty()
            when (op["op"]!!.jsonPrimitive.content) {
                "replace" -> queue = queue.replace(tracks, op["start"]?.jsonPrimitive?.int ?: 0, shuffle = false, random = random)
                "playNow" -> queue = queue.playNow(track(op["track"]!!.jsonPrimitive.content))
                "append" -> queue.append(tracks).also { queue = it.queue; count = it.count }
                "playNext" -> queue.playNext(tracks).also { queue = it.queue; count = it.count }
                "appendRecommendations" -> queue.appendRecommendations(tracks).also { queue = it.queue; count = it.count }
                "remove" -> queue = queue.remove(op["index"]!!.jsonPrimitive.int)
                "move" -> queue = queue.move(op["from"]!!.jsonPrimitive.int, op["to"]!!.jsonPrimitive.int)
                "clear" -> queue = queue.clear()
                "next" -> {
                    val advance = queue.next(op["manual"]?.jsonPrimitive?.boolean ?: false, random)
                    queue = advance.queue
                    result = when (val r = advance.result) {
                        is QueueAdvance.Index -> r.index.toString()
                        QueueAdvance.Ended -> "ended"
                        QueueAdvance.NeedsRecommendations -> "autoplay"
                    }
                }
                "previous" -> { queue = queue.previous(); result = queue.index.toString() }
                "cycleRepeat" -> queue = queue.cycleRepeat()
                "setAutoplay" -> queue = queue.withAutoplay(op["enabled"]!!.jsonPrimitive.boolean)
                "toggleShuffle" -> queue = queue.toggleShuffle(random)
                else -> error("unknown op $op")
            }
        }
        val tokens = queue.items.map(::token)
        val expect = case["expect"]!!.jsonObject
        expect["queue"]?.let { assertEquals(it.strings(), tokens) }
        expect["index"]?.let { assertEquals(it.jsonPrimitive.int, queue.index) }
        expect["repeat"]?.let { assertEquals(repeat(it.jsonPrimitive.content), queue.repeatMode) }
        expect["shuffle"]?.let { assertEquals(it.jsonPrimitive.boolean, queue.shuffled) }
        expect["autoplay"]?.let { assertEquals(it.jsonPrimitive.boolean, queue.autoplay) }
        expect["result"]?.let { assertEquals(it.jsonPrimitive.intOrNull?.toString() ?: it.jsonPrimitive.content, result) }
        expect["count"]?.let { assertEquals(it.jsonPrimitive.int, count) }
        expect["first"]?.let { assertEquals(it.jsonPrimitive.content, tokens.first()) }
        expect["set"]?.let { assertEquals(it.strings().toSet(), tokens.toSet()); assertEquals(it.strings().size, tokens.size) }
        expect["tail"]?.let { assertEquals(it.strings().toSet(), tokens.takeLast(it.strings().size).toSet()) }
    }
}
