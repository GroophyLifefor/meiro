package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.LiveTest
import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.MemoryPersistence
import com.muratkirazkaya.meiro.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Cross-check with the DESKTOP client: android/tools/cross-check.sh first runs the desktop's own Go sync client
 * (a copy of internal/sync in a scratch module; the repo is untouched) against the local dev server, which pushes
 * data and dumps every request body it sent. This test then (1) parses those exact Go-written bodies with the Kotlin
 * wire types, (2) syncs a phone engine from the same account and checks it adopts what the desktop wrote, and
 * (3) lets the phone write back, which the script reads with the desktop's reader.
 */
@Category(LiveTest::class)
class GoDesktopCrossTest {
    private val url = System.getenv("MEIRO_LIVE_URL").orEmpty()
    private val dumpDir = System.getenv("MEIRO_GO_DUMP_DIR").orEmpty()
    private val email = System.getenv("MEIRO_LIVE_EMAIL").orEmpty()
    private val password = System.getenv("MEIRO_LIVE_PASSWORD").orEmpty()

    private fun dump(suffix: String): String = File(dumpDir).listFiles()!!.single { it.name.endsWith(suffix) }.readText()

    @Test fun phoneReadsWhatTheDesktopWroteAndTheDesktopReadsWhatThePhoneWrote() {
        assumeTrue("MEIRO_GO_DUMP_DIR not set", dumpDir.isNotBlank())
        require(url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1")) { "local only" }
        val out = StringBuilder()

        // (1) the bodies the Go client sent parse with the Kotlin wire types
        @kotlinx.serialization.Serializable data class Libs(val libraries: List<Library>)
        @kotlinx.serialization.Serializable data class Evs(val events: List<Event>)
        val libs = SyncJson.decodeFromString(Libs.serializer(), dump("sync_libraries.json")).libraries
        assertEquals(listOf("Go desktop list", "Daily mix"), libs.map { it.name })
        assertEquals(Ids.libraryId("go-local-1"), libs[0].id) // LibraryID(localID) derived by Go == Kotlin's
        assertEquals(Ids.dailyMixId(), libs[1].id)            // DailyMixID golden, Go vs Kotlin
        val items = File(dumpDir).listFiles()!!.filter { it.name.endsWith("sync_library_items.json") }.sorted()
            .map { SyncJson.decodeFromString(LibraryItems.serializer(), it.readText()) }
        assertEquals(3, items[0].items.size); assertTrue(items[0].items[2].deleted); assertEquals("a reason", items[0].items[1].note)
        val ev = SyncJson.decodeFromString(Evs.serializer(), dump("sync_events.json")).events.single()
        assertTrue(ev.clientEventId == "7e57ab1e-0000-4000-8000-000000000001" && ev.playedMs == 150000L && !ev.completed && ev.device == "go-desktop")
        val links = SyncJson.decodeFromString(Links.serializer(), dump("sync_links.json"))
        assertTrue("Go writes nil slices as null: ${dump("sync_links.json")}", links.songs.isEmpty() && links.artists.single().artistKey == "go artist")
        out.append("parsed ${File(dumpDir).listFiles()!!.size} Go-written request bodies; local-id go-local-1 -> ${libs[0].id}; ").append("Go time offset sample: ${Regex("\"updated_at\":\"([^\"]+)\"").find(dump("sync_libraries.json"))!!.groupValues[1]}\n")

        // (2) a phone engine adopts what the desktop wrote
        val persistence = MemoryPersistence()
        val clock = com.muratkirazkaya.meiro.data.Clock.strictlyIncreasing()
        val libraries = LibraryStore(persistence, clock)
        val outbox = Outbox(persistence, clock)
        val linksStore = LinksStore(persistence, clock)
        val engine = SyncEngine(libraries, outbox, InMemoryTokenStore(), persistence, linksStore, clock, ZoneId.of("UTC"), "phone",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), sleep = { })
        engine.setSyncUrl(url)
        runBlocking { engine.signIn(email, password); engine.settled(); engine.pullLinks(); engine.settled() }
        val go = libraries.list().single { it.name == "Go desktop list" }
        assertEquals(Ids.libraryId("go-local-1"), go.serverId)
        assertEquals(listOf("go-song-1", "go-song-2"), go.items.map { it.key })
        assertEquals("a reason", go.items[1].why)
        assertEquals("https://pic/go-song-1", go.items[0].thumbnail); assertEquals("https://cover/go", go.cover)
        val mix = libraries.list().single { it.serverId == Ids.dailyMixId() }
        assertEquals(mix.id, engine.settings.dailyMixId)
        assertEquals("because desktop", mix.items[0].why)
        val play = engine.history().single { it.eventId == "7e57ab1e-0000-4000-8000-000000000001" }
        assertTrue(play.device == "go-desktop" && play.title == "Go song one" && !play.completed)
        assertEquals("go-mbid", linksStore.artist("Go Artist")?.mbid)
        out.append("phone adopted: list '${go.name}' (${go.items.size} tracks, note kept), Daily mix adopted as ${mix.id}, play from '${play.device}', artist link kept\n")

        // (3) the phone writes back; the desktop reader (script step) must see all of it
        val made = libraries.create("Phone made list")
        libraries.addItems(made.id, listOf(
            Item(source = Source.YTM, key = "phone-song-1", title = "Phone song one", artist = "Phone artist", durationMs = 123_456, thumbnail = "https://pic/ps1", why = "from the phone"),
            Item(source = Source.SOUNDCLOUD, key = "https://soundcloud.com/p/q", url = "https://soundcloud.com/p/q", title = "Cloud", artist = "Phone artist"),
        ))
        libraries.removeItem(go.id, "go-song-2")
        engine.recordPlay(HistoryEntry("phone-song-1", "Phone song one", "Phone artist", Instant.now().minusSeconds(3), "ytm", 123_456, 123_456, true, "phone", "https://pic/ps1", "7e57ab1e-0000-4000-8000-0000000000aa"))
        runBlocking { engine.syncNow(); engine.settled(); engine.pullChanges(); engine.settled(); engine.pullEvents(); engine.settled() }
        assertEquals("nothing is left waiting", 0, outbox.pending())
        assertEquals(emptyList<Refused>(), outbox.refused())
        out.append("phone pushed list '${made.name}', removed go-song-2, one play; outbox empty, nothing refused\n")
        File(System.getProperty("user.dir"), "build/cross-check-summary.txt").writeText(out.toString())
        println("CROSS " + out)
    }
}
