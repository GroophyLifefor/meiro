package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.MemoryPersistence
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant

/** Ports of internal/sync/sync_test.go (outbox, push, backoff, ids) and changes_test.go / events_test.go (outbox parts). */
class OutboxTest {
    private val servers = mutableListOf<FakeMeiroServer>()
    @After fun tearDown() { servers.forEach { it.stop() } }

    private fun fake(): FakeMeiroServer = FakeMeiroServer().also { it.strictItems = false }.start().also { servers += it }
    private fun client(f: FakeMeiroServer) = MeiroClient(f.url, "TOKEN")

    private fun sampleEvent(id: String) = Event(
        clientEventId = id, track = trackFor("ytm", "vid-$id", "", "Song $id", "Artist", 180000),
        playedMs = 60000, durationMs = 180000, playedAt = Instant.ofEpochSecond(1000),
    )

    private fun eventsRequests(f: FakeMeiroServer): Int = f.requests

    // TestOutboxSurvivesARestart
    @Test fun outboxSurvivesARestart() {
        val store = MemoryPersistence()
        val o = Outbox(store)
        o.addEvent(sampleEvent("a"))
        assertEquals(1, o.eventCount())
        val again = Outbox(store)
        assertEquals(1, again.eventCount())
        assertEquals("a", again.snapshot().events[0].clientEventId)
    }

    // TestARefusedPushKeepsTheQueueAndATakenOneClearsIt
    @Test fun aRefusedPushKeepsTheQueueAndATakenOneClearsIt() = runBlocking {
        val f = fake()
        val o = Outbox()
        o.addEvent(sampleEvent("a")); o.addEvent(sampleEvent("b"))
        f.refuse = { _, _ -> 500 }
        assertNotNull("a 500 was treated as delivered", o.push(client(f)).error)
        assertEquals("a refused push dropped plays", 2, o.eventCount())
        f.refuse = { _, _ -> 0 }
        val r = o.push(client(f))
        assertNull(r.error); assertEquals(2, r.sent)
        assertEquals(0, o.eventCount())
    }

    // TestALargeQueueGoesOutInBatches
    @Test fun aLargeQueueGoesOutInBatches() = runBlocking {
        val f = fake()
        val o = Outbox()
        val total = Outbox.MAX_BATCH * 5 + 1
        repeat(total) { o.addEvent(sampleEvent(it.toString())) }
        assertNull(o.push(client(f)).error)
        assertEquals("requests", 6, f.requests)
        assertEquals(total, f.plays.size)
        assertEquals(0, o.eventCount())
    }

    // TestBackoffGrowsAndIsCapped
    @Test fun backoffGrowsAndIsCapped() {
        assertTrue(Outbox.backoff(1) >= Duration.ofSeconds(1))
        assertTrue(Outbox.backoff(2) > Outbox.backoff(1)); assertTrue(Outbox.backoff(3) > Outbox.backoff(2))
        assertEquals(Duration.ofMinutes(5), Outbox.backoff(30))
        assertEquals(Duration.ofSeconds(2), Outbox.backoff(0)) // below 1 reads as 1
        assertEquals(Duration.ofSeconds(4), Outbox.backoff(2))
    }

    // TestTrackForMapsTheProvider
    @Test fun trackForMapsTheProvider() {
        data class C(val name: String, val source: String, val key: String, val url: String, val prov: String, val id: String)
        for (c in listOf(
            C("ytm", "ytm", "dQw4w9WgXcQ", "", "ytm", "dQw4w9WgXcQ"),
            C("soundcloud", "soundcloud", "https://soundcloud.com/x/y", "https://soundcloud.com/x/y", "soundcloud", "https://soundcloud.com/x/y"),
            C("empty source is ytm", "", "vid", "", "ytm", "vid"),
            C("key missing falls back to the url", "soundcloud", "", "https://soundcloud.com/a/b", "soundcloud", "https://soundcloud.com/a/b"),
        )) {
            val got = trackFor(c.source, c.key, c.url, "T", "A", 1000)
            assertEquals(c.name, c.prov, got.provider); assertEquals(c.name, c.id, got.providerTrackId)
        }
    }

    // TestRetryable
    @Test fun retryable() {
        assertTrue(isRetryable(ApiException(503, "")))
        assertFalse(isRetryable(ApiException(400, "")))
        assertTrue(isRetryable(SocketTimeoutException()))
        assertFalse(isRetryable(null))
    }

    // TestLinksGoOutAndANewerChangeStays
    @Test fun linksGoOutAndANewerChangeStays() = runBlocking {
        val f = fake()
        val o = Outbox()
        val t0 = Instant.ofEpochSecond(1_700_000_000)
        val songs = (0 until Outbox.MAX_BATCH + 5).map { SongLink(provider = "ytm", providerTrackId = "v$it", state = "matched", mbid = "r", updatedAt = t0) }
        o.addLinks(Links(songs, listOf(ArtistLink("hidra", "Hidra", "matched", "a", t0)), listOf(AltLink("ytm", "v0", "soundcloud", "u", "", t0, t0))))
        assertEquals(Outbox.MAX_BATCH + 5 + 2, o.pending())
        // While the first batch is answered the same song changes again.
        var once = false
        val c = MeiroClient(f.url, "TOKEN")
        f.refuse = { path, _ ->
            if (path.endsWith("/sync/links") && !once) {
                once = true
                o.addLinks(Links(listOf(SongLink(provider = "ytm", providerTrackId = "v0", state = "other", updatedAt = t0.plusSeconds(3600)))))
            }
            0
        }
        assertNull(o.push(c).error)
        for (b in f.pushedLinks) assertTrue("batch over the limit", b.songs.size <= Outbox.MAX_BATCH && b.artists.size <= Outbox.MAX_BATCH)
        val left = o.snapshot().links
        assertTrue("what is left = $left", left.songs.size == 1 && left.songs[0].providerTrackId == "v0" && left.songs[0].state == "other")
        assertTrue(left.artists.isEmpty() && left.alternates.isEmpty())
    }

    // TestAPendingLinkKeepsTheNewerRecord
    @Test fun aPendingLinkKeepsTheNewerRecord() {
        val o = Outbox()
        val t0 = Instant.ofEpochSecond(1_700_000_000)
        o.addLinks(Links(listOf(SongLink(provider = "ytm", providerTrackId = "v", state = "matched", mbid = "new", updatedAt = t0.plusSeconds(3600)))))
        o.addLinks(Links(listOf(SongLink(provider = "ytm", providerTrackId = "v", state = "other", updatedAt = t0))))
        val got = o.snapshot().links.songs
        assertTrue(got.size == 1 && got[0].mbid == "new")
    }

    private fun queueOneOfEach(o: Outbox) {
        val t0 = Instant.ofEpochSecond(1_700_000_000)
        o.addLibraries(listOf(Library(id = "l1", kind = "custom", name = "Road trip", updatedAt = t0)))
        o.addLibraryItems("l1", listOf(LibraryItem(track = trackFor("ytm", "v1", "", "One", "A", 0), updatedAt = t0)))
        o.setDownloads(listOf(Download(track = trackFor("ytm", "v1", "", "One", "A", 0), device = "laptop", updatedAt = t0)))
        o.addLinks(Links(listOf(SongLink(provider = "ytm", providerTrackId = "v1", state = "matched", mbid = "r", updatedAt = t0))))
        o.addEvent(sampleEvent("a"))
    }

    // TestARefusedBatchIsSetAsideAndTheRestGoesOut
    @Test fun aRefusedBatchIsSetAsideAndTheRestGoesOut() = runBlocking {
        for ((kind, suffix, status) in listOf(
            Triple("libraries", "/sync/libraries", 400), Triple("library items", "/sync/library_items", 422),
            Triple("downloads", "/sync/downloads", 400), Triple("links", "/sync/links", 422), Triple("events", "/sync/events", 400),
        )) {
            val f = fake()
            f.refuse = { path, _ -> if (path.endsWith(suffix)) status else 0 }
            val store = MemoryPersistence()
            val o = Outbox(store)
            queueOneOfEach(o)
            val r = o.push(client(f))
            assertNull("$kind: a refused batch stopped the push: ${r.error}", r.error)
            assertEquals("$kind: records sent", 4, r.sent)
            assertEquals("$kind: the refused batch holds up the queue", 0, o.pending())
            val refused = o.refused()
            assertEquals(kind, 1, refused.size)
            val got = refused[0]
            assertTrue("$kind: $got", got.kind == kind && got.status == status && got.error == "boom" && got.count == 1 && got.batch.isNotEmpty() && got.at.epochSecond > 0)
            // It is kept with the rest of the outbox.
            val again = Outbox(store).refused()
            assertTrue("$kind after reopening: $again", again.size == 1 && again[0].kind == kind)
        }
    }

    // TestARefusedBatchDoesNotHoldBackTheBatchesAfterIt
    @Test fun aRefusedBatchDoesNotHoldBackTheBatchesAfterIt() = runBlocking {
        val f = fake()
        f.refuse = { path, body -> if (path.endsWith("/sync/events") && body.contains("\"client_event_id\":\"bad\"")) 400 else 0 }
        val o = Outbox()
        o.addEvent(sampleEvent("bad"))
        repeat(Outbox.MAX_BATCH * 2 + 1) { o.addEvent(sampleEvent("ok$it")) }
        val r = o.push(client(f))
        assertTrue("push = $r", r.error == null && r.sent == Outbox.MAX_BATCH + 2)
        assertTrue(o.eventCount() == 0 && o.refused().size == 1 && o.refused()[0].count == Outbox.MAX_BATCH)
        val before = f.requests
        val second = o.push(client(f))
        assertTrue(second.error == null && second.sent == 0)
        assertEquals("the refused batch was sent again", before, f.requests)
    }

    // TestAnExpiredSessionKeepsEverythingAndSetsNothingAside
    @Test fun anExpiredSessionKeepsEverythingAndSetsNothingAside() = runBlocking {
        for (status in listOf(401, 403, 500)) {
            val f = fake()
            f.refuse = { _, _ -> status }
            val o = Outbox()
            queueOneOfEach(o)
            val before = o.pending()
            val err = o.push(client(f)).error
            assertTrue("status $status: $err", err is ApiException && err.status == status)
            assertTrue("status $status", o.pending() == before && o.refused().isEmpty())
        }
    }

    // TestTheRefusedListIsCappedAndDoesNotRepeat
    @Test fun theRefusedListIsCappedAndDoesNotRepeat() = runBlocking {
        val f = fake()
        f.refuse = { _, _ -> 400 }
        val o = Outbox()
        val c = client(f)
        repeat(Outbox.MAX_REFUSED + 10) {
            o.addEvent(sampleEvent("e$it"))
            assertNull(o.push(c).error)
        }
        val refused = o.refused()
        assertEquals(Outbox.MAX_REFUSED, refused.size)
        assertTrue(refused.first().batch.contains("\"e10\"") && refused.last().batch.contains("\"e59\""))
        o.addEvent(sampleEvent("e59"))
        assertNull(o.push(c).error)
        assertEquals("the same batch refused again was added a second time", Outbox.MAX_REFUSED, o.refused().size)
    }

    // TestAddLibrariesAndItemsKeepTheLaterRecordAndTheRest
    @Test fun addLibrariesAndItemsKeepTheLaterRecordAndTheRest() {
        val o = Outbox()
        val t1 = Instant.ofEpochSecond(100); val t2 = Instant.ofEpochSecond(200)
        o.addLibraries(listOf(Library(id = "A", name = "old", updatedAt = t1), Library(id = "B", name = "other", updatedAt = t1)))
        o.addLibraries(listOf(Library(id = "A", name = "new", updatedAt = t2)))
        o.addLibraries(listOf(Library(id = "A", name = "stale", updatedAt = t1)))
        val libs = o.snapshot().libraries
        assertTrue("libraries = $libs", libs.size == 2 && libs[0].name == "new" && libs[1].id == "B")
        fun track(id: String) = Track(provider = "ytm", providerTrackId = id)
        o.addLibraryItems("A", listOf(LibraryItem(track("x"), 0, "", t1), LibraryItem(track("gone"), 0, "", t1, deleted = true)))
        o.addLibraryItems("A", listOf(LibraryItem(track("x"), 4, "", t2)))
        val items = o.snapshot().libraryItems
        assertTrue("items = $items", items.size == 1 && items[0].items.size == 2 && items[0].items[0].position == 4 && items[0].items[1].deleted)
    }

    // TestPushDropsTheTracksOfALibraryTheServerDoesNotKnow
    @Test fun pushDropsTheTracksOfALibraryTheServerDoesNotKnow() = runBlocking {
        val f = fake()
        val pushed = mutableListOf<String>()
        f.refuse = { path, body ->
            if (path.endsWith("/sync/library_items")) {
                val lib = SyncJson.decodeFromString(LibraryItems.serializer(), body).libraryId
                pushed += lib
                if (lib == "gone") 404 else 0
            } else 0
        }
        val o = Outbox()
        for (lib in listOf("gone", "kept")) {
            o.addLibraryItems(lib, (0..Outbox.MAX_BATCH).map { LibraryItem(Track(provider = "ytm", providerTrackId = lib + it), 0, "", Instant.ofEpochSecond(1)) })
        }
        val r = o.push(client(f))
        assertNull("a 404 stopped the push: ${r.error}", r.error)
        assertEquals(0, o.pending())
        // The dropped library's second batch was never tried.
        assertTrue("pushed to $pushed", pushed.size == 3 && pushed[0] == "gone" && pushed[1] == "kept")
    }

    // TestOutboxDropEventsThrough
    @Test fun outboxDropEventsThrough() {
        val o = Outbox()
        val base = Instant.parse("2026-10-30T10:00:00Z")
        listOf("a", "b", "c").forEachIndexed { i, id -> o.addEvent(Event(clientEventId = id, playedAt = base.plusSeconds(60L * i))) }
        assertEquals(2, o.dropEventsThrough(base.plusSeconds(60)))
        assertEquals(1, o.eventCount())
    }

    @Test fun eventCapDropsTheOldestFirst() {
        val o = Outbox()
        repeat(Outbox.MAX_EVENTS + 3) { o.addEvent(sampleEvent("e$it")) }
        assertEquals(Outbox.MAX_EVENTS, o.eventCount())
        assertEquals("e3", o.snapshot().events.first().clientEventId)
        // the same id replaces, it does not queue twice
        o.addEvent(sampleEvent("e10").copy(playedMs = 1))
        assertEquals(Outbox.MAX_EVENTS, o.eventCount())
    }
}
