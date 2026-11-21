package com.muratkirazkaya.meiro.sync

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** Ports of internal/sync changes_test.go, events_test.go and sync_test.go (client parts). */
class ClientTest {
    private val servers = mutableListOf<MockWebServer>()
    @After fun tearDown() { servers.forEach { it.shutdown() } }

    /** Answers reads of one path with pages it is given in order, and records the since/limit of each read. */
    private fun pagesServer(path: String, pages: List<String>, asked: MutableList<String>): MeiroClient {
        val web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer TOKEN" || request.path!!.substringBefore('?') != path) {
                    return MockResponse().setResponseCode(401).setBody("""{"error":"no"}""")
                }
                asked += request.requestUrl!!.queryParameter("since") + "/" + request.requestUrl!!.queryParameter("limit")
                return MockResponse().setHeader("Content-Type", "application/json").setBody(pages[asked.size - 1])
            }
        }
        web.start(); servers += web
        return MeiroClient(web.url("/").toString(), "TOKEN")
    }

    // TestFetchChangesDecodesTheServersAnswer
    @Test fun fetchChangesDecodesTheServersAnswer() = runBlocking {
        val asked = mutableListOf<String>()
        val c = pagesServer("/api/v1/sync/changes", listOf("""{"libraries":[{"id":"L1","kind":"custom","provider":null,"provider_id":null,"name":"X","cover":"https://c","updated_at":"2026-01-02T03:04:05Z","deleted":false,"seq":12}],
            "items":[{"library_id":"L1","track":{"provider":"ytm","provider_track_id":"abc","title":"T","artist":"A","album":null,"duration_ms":200000,"isrc":null,"thumbnail":"https://t","album_thumbnail":null},"position":3,"updated_at":"2026-01-02T03:04:06Z","deleted":true,"seq":13}],
            "next":13,"more":false}"""), asked)
        val got = c.fetchChanges(7, 5000)
        assertEquals(listOf("7/1000"), asked) // since 7 and the largest limit
        val lib = got.libraries[0]; val item = got.items[0]
        assertTrue("library = $lib", lib.id == "L1" && lib.kind == "custom" && lib.cover == "https://c" && lib.seq == 12L && lib.updatedAt == Instant.parse("2026-01-02T03:04:05Z"))
        assertTrue("item = $item", item.libraryId == "L1" && item.track.providerTrackId == "abc" && item.track.thumbnail == "https://t" && item.position == 3 && item.deleted && item.seq == 13L)
        assertEquals(200000L, item.track.durationMs)
        assertTrue(got.next == 13L && !got.more)
    }

    // TestFetchAllChangesPagesThroughMore
    @Test fun fetchAllChangesPagesThroughMore() = runBlocking {
        val asked = mutableListOf<String>()
        val c = pagesServer("/api/v1/sync/changes", listOf(
            """{"libraries":[{"id":"A","kind":"custom","name":"A","updated_at":"2026-01-01T00:00:00Z","seq":1}],"items":[],"next":1,"more":true}""",
            """{"libraries":[],"items":[{"library_id":"A","track":{"provider":"ytm","provider_track_id":"v"},"position":0,"updated_at":"2026-01-01T00:00:00Z","seq":2}],"next":2,"more":false}""",
        ), asked)
        val got = c.fetchAllChanges(0)
        assertEquals(listOf("0/1000", "1/1000"), asked)
        assertTrue("joined = $got", got.libraries.size == 1 && got.items.size == 1 && got.next == 2L && !got.more)
    }

    // TestFetchAllChangesStopsOnAServerThatDoesNotMoveOn
    @Test fun fetchAllChangesStopsOnAServerThatDoesNotMoveOn() = runBlocking {
        val asked = mutableListOf<String>()
        val stuck = """{"libraries":[],"items":[],"next":5,"more":true}"""
        val c = pagesServer("/api/v1/sync/changes", listOf(stuck, stuck, stuck), asked)
        try { c.fetchAllChanges(5); fail("a server that did not move on was believed") } catch (_: IOException) {}
        assertEquals(1, asked.size)
    }

    // TestFetchChangesRefused
    @Test fun fetchChangesRefused() = runBlocking {
        try { MeiroClient("http://127.0.0.1:1").fetchChanges(0, 10); fail("an unreachable server did not fail the read") } catch (_: IOException) {}
        val asked = mutableListOf<String>()
        val c = pagesServer("/api/v1/sync/changes", emptyList(), asked)
        c.token = "WRONG"
        try { c.fetchChanges(0, 10); fail() } catch (e: ApiException) { assertEquals(401, e.status) }
    }

    // TestFetchEventsDecodesTheServersAnswer
    @Test fun fetchEventsDecodesTheServersAnswer() = runBlocking {
        val asked = mutableListOf<String>()
        val c = pagesServer("/api/v1/events", listOf("""{"events":[{"client_event_id":"e1","track":{"provider":"ytm","provider_track_id":"abc","title":"T","artist":"A"},
            "played_ms":31000,"duration_ms":200000,"completed":false,"device":"phone","played_at":"2026-01-02T03:04:05Z","seq":7}],"next":7,"more":false}"""), asked)
        val got = c.fetchEvents(3, 5000)
        assertEquals(listOf("3/1000"), asked)
        val e = got.events[0]
        assertTrue("event = $e", e.clientEventId == "e1" && e.track.providerTrackId == "abc" && e.track.artist == "A" && e.playedMs == 31000L &&
            e.durationMs == 200000L && e.device == "phone" && e.seq == 7L && e.playedAt == Instant.parse("2026-01-02T03:04:05Z"))
        assertTrue(got.next == 7L && !got.more)
        assertNull(got.clearedAt)
    }

    // TestFetchAllEventsPagesThroughMore
    @Test fun fetchAllEventsPagesThroughMore() = runBlocking {
        val asked = mutableListOf<String>()
        val c = pagesServer("/api/v1/events", listOf(
            """{"events":[{"client_event_id":"a","track":{"provider":"ytm","provider_track_id":"v"},"played_ms":1,"played_at":"2026-01-01T00:00:00Z","seq":1}],"next":1,"more":true}""",
            """{"events":[{"client_event_id":"b","track":{"provider":"ytm","provider_track_id":"w"},"played_ms":1,"played_at":"2026-01-01T00:00:00Z","seq":2}],"next":2,"more":false}""",
        ), asked)
        val got = c.fetchAllEvents(0)
        assertEquals(listOf("0/1000", "1/1000"), asked)
        assertTrue(got.events.size == 2 && got.next == 2L && !got.more)
    }

    // TestFetchAllEventsStopsOnAServerThatDoesNotMoveOn
    @Test fun fetchAllEventsStopsOnAServerThatDoesNotMoveOnAndARefusedReadFails() = runBlocking {
        val asked = mutableListOf<String>()
        val stuck = """{"events":[],"next":5,"more":true}"""
        val c = pagesServer("/api/v1/events", listOf(stuck, stuck, stuck), asked)
        try { c.fetchAllEvents(5); fail() } catch (_: IOException) {}
        assertEquals(1, asked.size)
        c.token = "WRONG"
        try { c.fetchEvents(0, 10); fail() } catch (_: ApiException) {}
    }

    // TestClearEventsAndFeedClearedAt
    @Test fun clearEventsAndFeedClearedAt() = runBlocking {
        val cleared = "2026-10-30T10:00:00.123456789Z"
        val web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertEquals("Bearer tok", request.getHeader("Authorization"))
                val p = request.path!!.substringBefore('?')
                return when {
                    request.method == "DELETE" && p == "/api/v1/events" -> MockResponse().setBody("""{"cleared_at":"$cleared"}""")
                    request.method == "GET" && p == "/api/v1/events" -> MockResponse().setBody("""{"events":[],"next":0,"more":false,"cleared_at":"$cleared"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        web.start(); servers += web
        val c = MeiroClient(web.url("/").toString(), "tok")
        val at = c.clearEvents()
        assertEquals(cleared, com.muratkirazkaya.meiro.data.Rfc3339Nano.format(at))
        val plays = c.fetchAllEvents(0)
        assertEquals(at, plays.clearedAt)
    }

    // TestClearEventsReportsRefusals
    @Test fun clearEventsReportsRefusals() = runBlocking {
        val web = MockWebServer(); web.enqueue(MockResponse().setResponseCode(405).setBody("""{"error":"no"}""")); web.start(); servers += web
        try { MeiroClient(web.url("/").toString()).clearEvents(); fail() } catch (e: ApiException) { assertEquals(405, e.status); assertEquals("no", e.serverMessage) }
    }

    // TestFetchLinks
    @Test fun fetchLinks() = runBlocking {
        val f = FakeMeiroServer().start().also { }
        try {
            f.serverLinks = Links(
                songs = listOf(SongLink(provider = "ytm", providerTrackId = "v", state = "matched", mbid = "r", updatedAt = Instant.ofEpochSecond(1_700_000_000))),
                alternates = listOf(AltLink(provider = "ytm", providerTrackId = "v", otherProvider = "soundcloud", otherTrackId = "u")),
            )
            val got = MeiroClient(f.url, "TOKEN").fetchLinks()
            assertTrue("links read back = $got", got.songs.size == 1 && got.songs[0].mbid == "r" && got.alternates.size == 1 && got.alternates[0].otherTrackId == "u")
            f.ended = true
            try { MeiroClient(f.url, "TOKEN").fetchLinks(); fail("a refused read did not fail") } catch (e: ApiException) { assertEquals(401, e.status) }
        } finally { f.stop() }
    }

    @Test fun loginSendsEmailAndPasswordAndBaseUrlTrailingSlashIsTrimmed() = runBlocking {
        val web = MockWebServer(); web.enqueue(MockResponse().setBody("""{"token":"T0K"}""")); web.start(); servers += web
        val c = MeiroClient(web.url("/").toString() + "/") // trailing slashes trimmed (Client.url)
        assertEquals("T0K", c.login("a@b.c", "pw"))
        val req = web.takeRequest()
        assertEquals("POST", req.method); assertEquals("/api/v1/auth/login", req.path)
        assertEquals("""{"email":"a@b.c","password":"pw"}""", req.body.readUtf8())
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun badLoginIsA401WithTheServersMessage() = runBlocking {
        val web = MockWebServer(); web.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"gecersiz kimlik"}""")); web.start(); servers += web
        try { MeiroClient(web.url("/").toString()).login("a", "b"); fail() } catch (e: ApiException) {
            assertEquals(401, e.status); assertEquals("gecersiz kimlik", e.serverMessage)
            assertEquals("sunucu 401: gecersiz kimlik", e.message)
        }
    }
}
