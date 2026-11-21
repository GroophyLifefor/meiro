package com.muratkirazkaya.meiro.data

import android.content.Context
import androidx.room.Room
import com.muratkirazkaya.meiro.data.db.MeiroDatabase
import com.muratkirazkaya.meiro.data.db.RoomPersistence
import com.muratkirazkaya.meiro.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.time.Instant

/** The Room layer: what the stores hand over comes back after a restart (closing and reopening the database file). */
@RunWith(RobolectricTestRunner::class)
class RoomPersistenceTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val dbs = mutableListOf<MeiroDatabase>()
    private fun open(): RoomPersistence {
        val db = Room.databaseBuilder(ctx, MeiroDatabase::class.java, "meiro-test.db").allowMainThreadQueries().build()
        dbs += db
        return RoomPersistence(db)
    }
    @After fun tearDown() { dbs.forEach { it.close() }; ctx.deleteDatabase("meiro-test.db") }

    @Test fun librariesSurviveARestartWithNanosecondsTombstonesAndSources() {
        val clock = ManualClock(Instant.parse("2026-11-21T10:00:00.123456789Z"))
        val first = open()
        val s = LibraryStore(first, clock)
        val l = s.create("Road trip")
        val cloud = SourceRef(Source.SOUNDCLOUD, url = "https://soundcloud.com/x/y")
        s.addItem(l.id, Item(source = Source.YTM, key = "a", title = "A", artist = "X", durationMs = 1234, why = "because", thumbnail = "t", albumThumbnail = "at", sources = listOf(SourceRef(Source.YTM, id = "a"), cloud)))
        s.addItem(l.id, Item(source = Source.YTM, key = "b", title = "B", artist = "Y", state = LinkState.MATCHED, mbid = "rec", artistMbid = "am", artistState = "matched"))
        s.removeItem(l.id, "b")
        val gone = s.create("Gone"); s.delete(gone.id)
        s.addRemote("srv-1", "From server", "https://c", Instant.parse("2026-11-20T00:00:00.000000001Z"))
        val before = LibraryFile(s.list(), s.deleted())
        dbs.forEach { it.close() }; dbs.clear()

        val again = LibraryStore(open(), clock)
        assertEquals(before.libraries, again.list())
        assertEquals(before.deleted, again.deleted())
        assertEquals(Instant.parse("2026-11-21T10:00:00.123456789Z"), again.list()[0].updatedAt)
        assertEquals("Road trip", again.list()[0].name)
        assertEquals(1, again.list()[0].removed.size)
    }

    @Test fun historySettingsOutboxAndLinksSurviveARestart() {
        val first = open()
        val entries = (0 until 50).map { HistoryEntry("v$it", "T$it", "A", Instant.parse("2026-11-21T10:00:00.5Z").plusSeconds(it.toLong()), "ytm", 1000, 500, it % 2 == 0, "phone", "th", "e$it") }
        first.saveHistory(entries)
        val settings = SyncSettings(
            syncUrl = "http://localhost:8080", syncEnabled = true, syncEmail = "me@example.com", syncLastAt = Instant.parse("2026-11-21T10:00:00.000000007Z"),
            syncCursor = 12, syncCursorUrl = "u", syncCursorEmail = "e", eventCursor = 3, eventCursorUrl = "u2", eventCursorEmail = "e2",
            historyClearedAt = Instant.parse("2026-11-21T09:00:00Z"), historyClearPending = null, syncTwoWay = true, dailyMixId = "dm", dailyMixDate = "2026-11-21",
        )
        first.saveSettings(settings)
        val outbox = Outbox(first)
        outbox.addEvent(Event("e1", trackFor("ytm", "v", "", "T", "A", 1), 5, 0, true, "phone", Instant.parse("2026-11-21T10:00:00.123456789Z")))
        outbox.addLibraries(listOf(Library("x", "custom", name = "N", updatedAt = Instant.parse("2026-11-21T10:00:00.000000001Z"))))
        val links = LinksStore(first)
        links.setSong(Song("ytm", "v", "matched", "rec", updatedAt = Instant.parse("2026-11-21T10:00:00.25Z")))
        dbs.forEach { it.close() }; dbs.clear()

        val again = open()
        assertEquals(entries, again.loadHistory())
        assertEquals(settings, again.loadSettings())
        val o2 = Outbox(again)
        assertEquals(1, o2.eventCount()); assertEquals(Instant.parse("2026-11-21T10:00:00.123456789Z"), o2.snapshot().events[0].playedAt)
        assertEquals(Instant.parse("2026-11-21T10:00:00.000000001Z"), o2.snapshot().libraries[0].updatedAt)
        assertEquals("rec", LinksStore(again).song("ytm", "v")?.mbid)
        // a fresh database reads as the defaults
        ctx.deleteDatabase("meiro-test.db")
    }

    @Test fun anEmptyDatabaseReadsAsDefaults() {
        val p = open()
        assertEquals(LibraryFile(), p.loadLibraries()); assertTrue(p.loadHistory().isEmpty())
        assertEquals(SyncSettings(), p.loadSettings()); assertNull(p.loadOutboxJson()); assertNull(p.loadLinksJson())
    }

    @Test fun aFullHistorySavesInReasonableTime() {
        val p = open()
        val entries = (0 until History.MAX_ENTRIES).map { HistoryEntry("v$it", "T", "A", Instant.parse("2026-11-21T10:00:00Z").plusSeconds(it.toLong()), "ytm", 1000, 500, true, "phone", "", "e$it") }
        val t0 = System.nanoTime()
        p.saveHistory(entries)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("RoomPersistence.saveHistory(5000) took $ms ms")
        assertEquals(History.MAX_ENTRIES, p.loadHistory().size)
        assertTrue("saving 5000 plays took $ms ms", ms < 5_000)
    }

    /** Two devices on Room storage converge through a fake server, and the first survives a restart in between. */
    @Test fun anEngineOnRoomStorageSurvivesARestartAndKeepsSyncing() {
        val server = FakeMeiroServer().start()
        try {
            val storage = open()
            val a = Device(server, persistence = storage, tokens = InMemoryTokenStore())
            val trip = a.libraries.create("Trip"); a.addSong(trip.id, "s1")
            a.playAt("e1", Instant.now().minusSeconds(60))
            a.syncBoth()
            assertEquals(listOf("e1"), server.playIds())
            dbs.forEach { it.close() }; dbs.clear()

            val restarted = Device(server, persistence = open(), tokens = InMemoryTokenStore(), signedIn = false)
            assertEquals("the account and cursors came back", "me@example.com", restarted.engine.settings.syncEmail)
            assertTrue(restarted.engine.settings.syncCursor > 0)
            restarted.engine.testSignIn(server.url)
            restarted.addSong(restarted.libraries.list()[0].id, "s2")
            restarted.syncBoth()
            val b = Device(server)
            b.syncBoth()
            assertEquals(listOf("s1", "s2"), listKeys(b.libraries.list()[0]))
        } finally { server.stop() }
    }
}
