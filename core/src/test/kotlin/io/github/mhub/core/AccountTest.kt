package io.github.mhub.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTest {
    private fun eq(expected: Any?, actual: Any?, message: String = "") = org.junit.Assert.assertEquals(message, expected, actual)

    private val cases = MHubJson.parseToJsonElement(File(Contract.root, "library-ops-cases.json").readText()).jsonObject

    private fun JsonElement.text(key: String) = jsonObject[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun keys(element: JsonElement?) = element?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    /** Contract playlists list tracks by key; titles do not matter to the rules. */
    private fun playlists(element: JsonElement) = element.jsonArray.map { item ->
        Playlist(
            id = item.text("id"),
            name = item.text("name"),
            tracks = keys(item.jsonObject["tracks"]).map { key -> Track(key.substringBefore(':'), key.substringAfter(':')) },
            sourceUrl = item.text("source_url"),
        )
    }

    private fun summary(list: List<Playlist>) = list.map { Triple(it.id, it.name, it.tracks.map(Track::id)) }

    @Test
    fun localReplayMatchesTheServerRules() {
        for (case in cases.getValue("apply").jsonArray) {
            val ops = LibraryOpJson.decodeFromString(ListSerializer(LibraryOp.serializer()), case.jsonObject.getValue("ops").toString())
            val result = LibrarySync.apply(playlists(case.jsonObject.getValue("before")), ops, now = 1)
            eq(summary(playlists(case.jsonObject.getValue("after"))), summary(result), case.text("name"))
        }
    }

    @Test
    fun dragReorderBecomesTheFewestMoves() {
        for (case in cases.getValue("reorder").jsonArray) {
            val expected = case.jsonObject.getValue("moves").jsonArray.map {
                it.text("key") to (it.jsonObject["after"] as? JsonElement)?.takeIf { after -> after !is JsonNull }?.jsonPrimitive?.content
            }
            val from = keys(case.jsonObject["from"])
            val to = keys(case.jsonObject["to"])
            val moves = LibrarySync.reorderMoves(from, to)
            eq(expected, moves, case.text("name"))
            // Replaying the moves yields the new order.
            val start = listOf(Playlist("p", "p", from.map { Track(it.substringBefore(':'), it.substringAfter(':')) }))
            val replayed = LibrarySync.apply(start, moves.map { LibraryOp.Move("p", it.first, it.second) }, 0)
            eq(to, replayed.single().tracks.map(Track::id), case.text("name"))
        }
    }

    @Test
    fun firstLoginMergeMatchesTheWebPlan() {
        for (case in cases.getValue("merge").jsonArray) {
            val plan = LibrarySync.planMerge(playlists(case.jsonObject.getValue("local")), playlists(case.jsonObject.getValue("cloud")))
            eq(keys(case.jsonObject["create"]), plan.create.map { it.id }, case.text("name"))
            val merges = case.jsonObject.getValue("merge").jsonArray.map { Triple(it.text("local"), it.text("cloud"), keys(it.jsonObject["keys"])) }
            eq(merges, plan.merge.map { Triple(it.local.id, it.cloudId, it.tracks.map(Track::id)) }, case.text("name"))
        }
    }

    @Test
    fun opsEncodeAsTheServerExpects() {
        val text = LibraryOpJson.encodeToString(LibraryOps.serializer(), LibraryOps(listOf(
            LibraryOp.Move("p", "netease:1", null),
            LibraryOp.Create("p2", "新", tracks = listOf(SyncTrack("netease", "1", "歌"))),
        )))
        val ops = MHubJson.parseToJsonElement(text).jsonObject.getValue("ops").jsonArray
        eq("move", ops[0].text("op"))
        assertTrue(ops[0].jsonObject.containsKey("after"))
        eq("create", ops[1].text("op"))
        eq("1", (ops[1].jsonObject.getValue("tracks") as JsonArray)[0].text("source_id"))
    }

    @Test
    fun accountFixturesDecode() {
        val me = MHubJson.decodeFromString(MeResponse.serializer(), Contract.fixture("auth-me.json"))
        eq("alice", me.user?.username)
        eq(8, MHubJson.decodeFromString(LoginResponse.serializer(), Contract.fixture("auth-register.json")).recoveryCodes.size)
        val snapshot = MHubJson.decodeFromString(LibrarySnapshot.serializer(), Contract.fixture("library-snapshot.json")).library
        eq(listOf("netease:1", "applemusic:1440833098"), snapshot.first().tracks.map(Track::id))
        eq(1762000000123, snapshot.first().createdAt)
        eq("https://music.example/playlist?id=123", snapshot[1].sourceUrl)
    }

    @Test
    fun sessionCookieIsReadFromSetCookie() {
        eq("abc", MHubClient.sessionFrom(listOf("other=1", "mhub_session=abc; Path=/; HttpOnly; SameSite=Lax")))
        eq("", MHubClient.sessionFrom(listOf("mhub_session=; Path=/; Max-Age=0")))
        assertNull(MHubClient.sessionFrom(listOf("other=1")))
    }

    @Test
    fun versionsCompareLikeTheContract() {
        val versions = MHubJson.parseToJsonElement(File(Contract.root, "version-cases.json").readText()).jsonObject
        for (case in versions.getValue("cases").jsonArray) {
            val (a, b, sign) = case.jsonArray
            val result = AppVersion.parse(a.jsonPrimitive.content)!!.compareTo(AppVersion.parse(b.jsonPrimitive.content)!!)
            eq(sign.jsonPrimitive.int, result.coerceIn(-1, 1), "${a.jsonPrimitive.content} vs ${b.jsonPrimitive.content}")
        }
    }

    @Test
    fun releaseNotesBecomePlainText() {
        eq("变化\n• 歌单同步\n见 MHub 与 /health", UpdateCheck.summary("## 变化\n\n- 歌单同步\n见 [MHub](https://x) 与 `/health`"))
    }

    @Test
    fun newestPublishedReleaseIsOffered() {
        val releases = UpdateCheck.decode(Contract.fixture("github-releases.json"))
        val update = UpdateCheck.newer(releases, "0.1.0-beta.1", ".apk")!!
        eq("0.1.0-beta.2", update.version.toString())
        assertTrue(update.download!!.endsWith("MHub-0.1.0-beta.2.apk"))
        assertNull(UpdateCheck.newer(releases, "0.1.0-beta.2", ".apk"))
        assertNull(UpdateCheck.newer(releases, "0.1.0", ".apk"))
        assertNull(UpdateCheck.newer(releases, "0.1.0-beta.1", ".ipa")?.download)
    }
}
