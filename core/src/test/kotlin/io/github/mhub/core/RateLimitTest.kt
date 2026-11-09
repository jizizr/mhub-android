package io.github.mhub.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Runs `contract/rate-limit-cases.json`, the 429 handling shared with iOS and the web client. */
class RateLimitTest {
    private val cases = MHubJson.parseToJsonElement(File(Contract.root, "rate-limit-cases.json").readText()).jsonObject

    private fun JsonObject.str(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

    @Test fun limitsMatchPolicies() {
        val limits = cases["limits_ms"]!!.jsonObject
        assertEquals(limits["default"]!!.jsonPrimitive.long, RateLimitPolicy.Standard.maxWaitMs)
        assertEquals(limits["prefetch"]!!.jsonPrimitive.long, RateLimitPolicy.Prefetch.maxWaitMs)
        assertEquals(limits["playback"]!!.jsonPrimitive.long, RateLimitPolicy.playback().maxWaitMs)
        assertEquals(limits["import"]!!.jsonPrimitive.long, RateLimitPolicy.importing().maxWaitMs)
    }

    @Test fun retryAfter() {
        for (element in cases["retry_after"]!!.jsonArray) {
            val c = element.jsonObject
            assertEquals(c.str("name"), c["expect_ms"]!!.jsonPrimitive.long, RateLimit.retryAfterMs(c.str("header"), c["now_ms"]!!.jsonPrimitive.long))
        }
    }

    @Test fun secondsAndPrefetchDelay() {
        for (element in cases["seconds"]!!.jsonArray) {
            val c = element.jsonObject
            assertEquals(c["expect"]!!.jsonPrimitive.long, RateLimit.seconds(c["ms"]!!.jsonPrimitive.long))
        }
        for (element in cases["prefetch_delay"]!!.jsonArray) {
            val c = element.jsonObject
            assertEquals(c["expect_ms"]!!.jsonPrimitive.long, RateLimit.prefetchDelayMs(c["attempt"]!!.jsonPrimitive.int, c["retry_after_ms"]!!.jsonPrimitive.long))
        }
    }

    /** Answers every request with the next scripted response. */
    private class Script(val responses: List<JsonObject>) : Interceptor {
        val requests = AtomicInteger()
        override fun intercept(chain: Interceptor.Chain): Response {
            val index = requests.getAndIncrement()
            val scripted = responses.getOrNull(index) ?: buildJsonObject { put("status", 500); put("detail", "unscripted") }
            val status = scripted["status"]!!.jsonPrimitive.int
            val body = when {
                status < 300 -> Contract.fixture("playback-streaming.json")
                scripted["detail"]?.takeIf { it !is JsonNull } != null -> buildJsonObject { put("detail", scripted["detail"]!!.jsonPrimitive.content) }.toString()
                else -> ""
            }
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("")
                .body(body.toResponseBody("application/json".toMediaType()))
            scripted["retry_after"]?.takeIf { it !is JsonNull }?.let { builder.header("Retry-After", it.jsonPrimitive.content) }
            return builder.build()
        }
    }

    private val track = Track("applemusic", "1", "t", "a")

    @Test fun waits() = runBlocking {
        val all = cases["waits"]!!.jsonArray
        assertTrue(all.size >= 5)
        for (element in all) {
            val c = element.jsonObject
            val name = c.str("name")
            val expect = c["expect"]!!.jsonObject
            val script = Script(c["responses"]!!.jsonArray.map { it.jsonObject })
            val waits = mutableListOf<Long>()
            val notices = mutableListOf<Long>()
            val client = MHubClient("https://mock.test".toHttpUrl(), OkHttpClient.Builder().addInterceptor(script).build()) { waits += it }
            var outcome = "ok"
            try {
                client.createPlayback(track, Quality.Lossless, RateLimitPolicy(c["max_wait_ms"]!!.jsonPrimitive.long) { notices += it })
            } catch (limited: ApiException.RateLimited) {
                outcome = "rate_limited"
                assertEquals(name, expect["retry_after_ms"]!!.jsonPrimitive.long, limited.retryAfterMs)
                assertEquals(name, expect.str("message"), limited.message)
            } catch (http: ApiException.Http) {
                outcome = "http"
                assertEquals(name, expect["status"]!!.jsonPrimitive.int, http.status)
            }
            val expectedWaits = (expect["waits"] as JsonArray).map { it.jsonPrimitive.long }
            assertEquals(name, expect.str("outcome"), outcome)
            assertEquals(name, expectedWaits, waits)
            assertEquals(name, expectedWaits, notices)
            assertEquals(name, expectedWaits.size + 1, script.requests.get())
        }
    }

    @Test fun cancellingStopsTheWait() = runBlocking {
        val script = Script(listOf(buildJsonObject { put("status", 429); put("retry_after", "60") }))
        val client = MHubClient("https://mock.test".toHttpUrl(), OkHttpClient.Builder().addInterceptor(script).build())
        val job = async { client.createPlayback(track, Quality.Lossless, RateLimitPolicy.playback()) }
        delay(300)
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(1, script.requests.get())
    }
}
