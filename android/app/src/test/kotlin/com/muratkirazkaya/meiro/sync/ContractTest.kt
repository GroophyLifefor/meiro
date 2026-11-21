package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.Rfc3339Nano
import com.muratkirazkaya.meiro.data.Source
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Contract tests: the JSON fixtures in src/test/resources/fixtures were RECORDED from the real local dev server
 * (android/tools/record-fixtures.sh, tokens scrubbed) and are replayed through MockWebServer, so the Kotlin client
 * is checked against what the server really writes, not against what we think it writes.
 */
class ContractTest {
    private val web = MockWebServer()
    @After fun tearDown() { web.shutdown() }

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name.json")!!.readText()
    private fun status(name: String): Int = javaClass.getResource("/fixtures/$name.status")!!.readText().trim().toInt()

    private fun serve(vararg names: String): MeiroClient {
        for (n in names) web.enqueue(MockResponse().setResponseCode(status(n)).setHeader("Content-Type", "application/json").setBody(fixture(n)))
        web.start()
        return MeiroClient(web.url("/").toString(), "TOKEN")
    }

    @Test fun loginFixture() = runBlocking {
        val c = serve("login")
        assertEquals("SCRUBBED-TOKEN-0000000000000000000000000000", c.login("e@x", "p"))
    }

    @Test fun errorFixturesCarryStatusAndTurkishMessageButWeBranchOnStatus() = runBlocking {
        val c = serve("login_bad", "changes_401", "changes_bad_since", "push_libraries_bad_kind", "push_items_unknown_library")
        val expected = listOf(401 to "gecersiz kimlik", 401 to "gecersiz kimlik", 400 to "since gecersiz", 400 to "kind gecersiz", 404 to "kutuphane bulunamadi")
        val calls: List<suspend () -> Unit> = listOf(
            { c.login("a", "b") }, { c.fetchChanges(0, 10) }, { c.fetchChanges(-1, 10) },
            { c.pushLibraries(emptyList()) }, { c.pushLibraryItems(LibraryItems("x", emptyList())) },
        )
        calls.forEachIndexed { i, call ->
            try { call(); fail("call $i succeeded") } catch (e: ApiException) {
                assertEquals(expected[i].first, e.status); assertEquals(expected[i].second, e.serverMessage)
                assertEquals("a 4xx is not retryable", false, isRetryable(e))
            }
        }
    }

    @Test fun pushResponsesDecode() = runBlocking {
        val c = serve("push_libraries", "push_items", "push_events", "push_events_dup", "push_downloads", "push_links")
        assertEquals(listOf("11111111-2222-4333-8444-555555555555", "bab208cb-a929-55cd-a091-c414d0e03864"), c.pushLibraries(emptyList()))
        assertEquals(3, c.pushLibraryItems(LibraryItems("x", emptyList())).size)
        val (acc, dup) = c.pushEvents(emptyList())
        assertEquals(2, acc.size); assertTrue(dup.isEmpty())
        val (acc2, dup2) = c.pushEvents(emptyList())
        assertTrue(acc2.isEmpty()); assertEquals(listOf("aaaaaaaa-0000-4000-8000-000000000001"), dup2)
        assertEquals(1, c.pushDownloads(emptyList()).size)
        c.pushLinks(Links()) // {"accepted":3}: an int, not a list; the client ignores the body
    }

    @Test fun changesFixtureParsesTimesNotesTombstonesAndNulls() = runBlocking {
        val c = serve("changes_all")
        val ch = c.fetchAllChanges(0)
        assertEquals(2, ch.libraries.size); assertEquals(3, ch.items.size)
        val gym = ch.libraries[0]
        assertEquals("Gym", gym.name); assertEquals("custom", gym.kind); assertEquals("https://example.com/c.jpg", gym.cover)
        // The server keeps microseconds: ...00.123456789 went in, ...00.123456 came back, and we keep what it gave.
        assertEquals(Instant.parse("2026-11-21T10:00:00.123456Z"), gym.updatedAt)
        assertEquals("", gym.provider) // null on the wire reads as the empty string
        assertEquals("", ch.libraries[1].cover)
        assertEquals(Ids.dailyMixId(), ch.libraries[1].id)
        assertEquals(Instant.parse("2026-11-21T10:00:01.5Z"), ch.libraries[1].updatedAt)
        val first = ch.items[0]
        assertTrue(first.track.provider == "ytm" && first.track.providerTrackId == "dQw4w9WgXcQ" && first.note == "because" && first.track.thumbnail == "https://example.com/t.jpg" && first.track.albumThumbnail == "https://example.com/a.jpg")
        assertEquals("album null reads as empty", "", first.track.album); assertEquals(0L, first.track.durationMs)
        assertEquals(Instant.parse("2026-11-21T10:00:02Z"), first.updatedAt) // 10:00:02.000000001 sent, microseconds kept: .000000
        val gone = ch.items.single { it.deleted }
        assertEquals("gone0000001", gone.track.providerTrackId)
        assertEquals(Source.SOUNDCLOUD, ch.items[1].track.provider)
        assertEquals("", ch.items[1].note)
        assertTrue(ch.items.all { it.seq > 0 } && ch.next == ch.items.last().seq.coerceAtLeast(ch.libraries.last().seq))
    }

    @Test fun changesPagingFixtures() = runBlocking {
        val c = serve("changes_page1", "changes_page2")
        val p1 = c.fetchChanges(0, 2)
        assertTrue(p1.more && p1.libraries.size == 2 && p1.items.isEmpty())
        val p2 = c.fetchChanges(p1.next, 2)
        assertTrue(p2.more && p2.libraries.isEmpty() && p2.items.size == 2 && p2.next > p1.next)
    }

    @Test fun eventsFixtures() = runBlocking {
        val c = serve("events_all", "events_page1", "clear_events", "events_after_clear")
        val all = c.fetchAllEvents(0)
        assertEquals(2, all.events.size); assertNull("never cleared: null", all.clearedAt)
        val e = all.events[0]
        assertTrue(e.clientEventId == "aaaaaaaa-0000-4000-8000-000000000001" && e.playedMs == 187000L && e.durationMs == 213000L && e.completed && e.device == "Pixel" && e.track.thumbnail == "https://example.com/t.jpg")
        assertEquals(Instant.parse("2026-11-21T10:05:00.123456Z"), e.playedAt)
        val second = all.events[1]
        assertTrue("null duration, device and thumbnail read as zero values", second.durationMs == 0L && second.device == "" && second.track.thumbnail == "" && !second.completed)
        val page = c.fetchEvents(0, 1)
        assertTrue(page.more && page.events.size == 1 && page.next == page.events[0].seq)
        val at = c.clearEvents()
        assertEquals(Instant.parse("2026-11-21T15:59:31.84663Z"), at)
        val after = c.fetchAllEvents(0)
        assertTrue(after.events.isEmpty() && after.clearedAt == at)
    }

    @Test fun linksFixture() = runBlocking {
        val l = serve("get_links").fetchLinks()
        val s = l.songs.single()
        assertTrue(s.provider == "ytm" && s.state == "matched" && s.mbid == "11111111-1111-4111-8111-111111111111" && s.artistState == "matched")
        assertEquals(Instant.parse("2026-11-21T10:08:00.5Z"), s.updatedAt); assertEquals(Instant.parse("2026-11-21T10:08:00Z"), s.checkedAt)
        assertEquals("rick", l.artists.single().artistKey)
        val alt = l.alternates.single()
        assertTrue(alt.otherProvider == "soundcloud" && alt.otherTrackId == "https://soundcloud.com/a/b")
    }

    /** The engine on top of the recorded pages: the real server's answer merged into a fresh phone. */
    @Test fun aFreshPhoneAdoptsWhatTheRecordedServerHolds() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!.substringBefore('?')
                val name = when {
                    p.endsWith("/sync/changes") -> "changes_all"
                    p.endsWith("/api/v1/events") -> "events_all"
                    p.endsWith("/api/v1/links") -> "get_links"
                    else -> return MockResponse().setBody("""{"accepted":[],"duplicates":[]}""")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(fixture(name))
            }
        }
        web.start()
        val d = Device(null, withLinks = true)
        d.engine.testSignIn(web.url("/").toString().trimEnd('/'))
        runBlocking { d.engine.pullLinks(); d.engine.pullChanges(); d.engine.pullEvents(); d.engine.settled() }
        val lists = d.libraries.list().associateBy { it.name }
        assertEquals(setOf("Gym", "Daily mix"), lists.keys)
        val gym = lists.getValue("Gym")
        assertEquals(listOf("dQw4w9WgXcQ", "https://soundcloud.com/a/b"), gym.items.map { it.key }) // the tombstone is not a track
        assertEquals("because", gym.items[0].why)
        assertEquals("https://soundcloud.com/a/b", gym.items[1].url)
        assertEquals(Instant.parse("2026-11-21T10:00:00.123456Z"), gym.updatedAt)
        assertEquals("the server's Daily mix id is adopted as the Daily mix", lists.getValue("Daily mix").id, d.engine.settings.dailyMixId)
        assertEquals(2, d.engine.history().size)
        assertTrue(d.engine.settings.syncCursor > 0 && d.engine.settings.eventCursor > 0 && d.engine.settings.syncTwoWay)
        assertEquals("rec", "rec".takeIf { d.links!!.song("ytm", "dQw4w9WgXcQ")?.state == "matched" })
        assertEquals("a pull sends nothing", 0, d.outbox.pending())
    }

    /** Shapes the Go desktop writes (local-offset times, nil slices as null, omitted fields) parse in Kotlin. */
    @Test fun recordsInTheGoDesktopFormatParse() {
        val goEvent = """{"client_event_id":"11111111-1111-4111-8111-111111111111","track":{"provider":"ytm","provider_track_id":"v","title":"T","artist":"A","duration_ms":180000},
            "played_ms":60000,"duration_ms":180000,"completed":false,"device":"laptop","played_at":"2026-10-30T13:03:11.123456789+03:00"}"""
        val e = SyncJson.decodeFromString(Event.serializer(), goEvent)
        assertEquals(Instant.parse("2026-10-30T10:03:11.123456789Z"), e.playedAt)
        val goLinks = """{"songs":null,"artists":[{"artist_key":"k","name":"N","state":"matched","updated_at":"2026-10-30T13:03:11+03:00"}],"alternates":null}"""
        val l = SyncJson.decodeFromString(Links.serializer(), goLinks)
        assertTrue(l.songs.isEmpty() && l.artists.size == 1 && l.alternates.isEmpty())
        val goOutbox = """{"events":null,"libraries":[{"id":"x","kind":"custom","name":"N","updated_at":"2026-10-30T13:03:11.5+03:00"}],"libraryItems":null,"downloads":null,"links":{"songs":null,"artists":null,"alternates":null}}"""
        val o = SyncJson.decodeFromString(OutboxFile.serializer(), goOutbox)
        assertEquals(Instant.parse("2026-10-30T10:03:11.5Z"), o.libraries[0].updatedAt)
    }
}
