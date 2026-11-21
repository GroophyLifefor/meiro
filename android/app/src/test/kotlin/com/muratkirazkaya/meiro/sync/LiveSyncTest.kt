package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.LiveTest
import com.muratkirazkaya.meiro.data.Clock
import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.MemoryPersistence
import com.muratkirazkaya.meiro.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Two independent engines ("phone A", "phone B") against the LOCAL dev server. Runs only when MEIRO_LIVE_URL is set
 * (see android/tools/live-sync.sh, which creates and deletes throwaway `e2e-android-` users). It refuses any host
 * that is not localhost.
 */
@Category(LiveTest::class)
class LiveSyncTest {
    private val url = System.getenv("MEIRO_LIVE_URL").orEmpty()
    private val email = System.getenv("MEIRO_LIVE_EMAIL").orEmpty()
    private val password = System.getenv("MEIRO_LIVE_PASSWORD").orEmpty()
    private val dailyMixAllowed = System.getenv("MEIRO_LIVE_DAILYMIX") == "1"

    private val results = mutableListOf<String>()
    private val requests = AtomicInteger()
    private val latencyNanos = AtomicLong()

    private class PinnableClock : Clock {
        @Volatile var pinned: Instant? = null
        private val ticking = Clock.strictlyIncreasing()
        override fun now(): Instant = pinned ?: ticking.now()
    }

    private inner class Phone(val name: String, signedIn: Boolean = true) {
        val clock = PinnableClock()
        val persistence = MemoryPersistence()
        val tokens = InMemoryTokenStore()
        val libraries = LibraryStore(persistence, clock)
        val outbox = Outbox(persistence, clock)
        val engine = SyncEngine(
            libraries, outbox, tokens, persistence, null, clock, ZoneId.of("UTC"), name,
            http = http(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO), sleep = { },
        )
        init {
            engine.setSyncUrl(url)
            if (signedIn) runBlocking { engine.signIn(email, password); engine.settled() }
        }
        fun sync() = runBlocking { engine.syncNow(); engine.settled(); engine.pullChanges(); engine.settled(); engine.pullEvents(); engine.settled() }
        fun keys(id: String) = libraries.get(id)!!.items.map { it.key }
    }

    private fun http() = OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val t0 = System.nanoTime()
            val r = chain.proceed(chain.request())
            requests.incrementAndGet(); latencyNanos.addAndGet(System.nanoTime() - t0)
            r
        }).build()

    @Before fun guard() {
        assumeTrue("MEIRO_LIVE_URL not set", url.isNotBlank())
        val host = URI(url).host
        require(host == "localhost" || host == "127.0.0.1" || host == "[::1]") { "refusing to run against a non-local server: $url" }
        require(email.startsWith("e2e-android-")) { "the live test only uses throwaway e2e-android- users, got '$email'" }
        require(password.isNotEmpty())
    }

    private fun ok(name: String, detail: String) { results += "PASS  $name  ($detail)"; println("LIVE PASS: $name ($detail)") }
    private fun track(prefix: String) = prefix + "-" + UUID.randomUUID().toString().take(8)
    private fun song(key: String, title: String = "e2e $key") = Item(source = Source.YTM, key = key, title = title, artist = "e2e artist", durationMs = 181_000, thumbnail = "https://example.com/$key.jpg")

    /** Reads the server directly (a third, independent client) to see what it really holds. */
    private fun serverChanges(): Changes = runBlocking {
        val c = MeiroClient(url, "")
        val t = c.login(email, password)
        MeiroClient(url, t).fetchAllChanges(0)
    }
    private fun serverEvents(): Plays = runBlocking {
        val t = MeiroClient(url, "").login(email, password)
        MeiroClient(url, t).fetchAllEvents(0)
    }

    @Test fun twoPhonesOneAccount() {
        val t0 = System.nanoTime()
        val a = Phone("phone-A"); val b = Phone("phone-B")
        assertTrue("both phones signed in", a.engine.token.isNotEmpty() && b.engine.token.isNotEmpty() && a.engine.token != b.engine.token)
        ok("sign in", "two independent engines, two different session tokens, syncing enabled=${a.engine.settings.syncEnabled}")

        // 1. create list on A -> appears on B
        val k1 = track("k1"); val k2 = track("k2"); val k3 = track("k3")
        val list = a.libraries.create("e2e list " + UUID.randomUUID().toString().take(6))
        a.libraries.addItems(list.id, listOf(song(k1), song(k2), song(k3)))
        a.sync(); b.sync()
        val onB = b.libraries.list().singleOrNull { it.serverId == Ids.libraryId(list.id) }
        assertNotNull("the list did not appear on B: ${b.libraries.list()}", onB)
        assertEquals(listOf(k1, k2, k3), b.keys(onB!!.id))
        assertEquals(list.name, onB.name)
        assertEquals("cover/thumbnail travel", "https://example.com/$k1.jpg", onB.items[0].thumbnail)
        ok("create list on A appears on B", "3 tracks, same order, name and thumbnails kept; B server id ${onB.serverId}")

        // 2. rename on B -> A
        b.libraries.rename(onB.id, list.name + " renamed on B")
        b.sync(); a.sync()
        assertEquals(list.name + " renamed on B", a.libraries.get(list.id)!!.name)
        ok("rename on B reaches A", "A now '${a.libraries.get(list.id)!!.name}'")

        // 3. delete item on A -> tombstone on B
        a.libraries.removeItem(list.id, k2)
        a.sync(); b.sync()
        assertEquals(listOf(k1, k3), b.keys(onB.id))
        assertTrue("B must not echo a tombstone: ${b.libraries.get(onB.id)!!.removed}", b.libraries.get(onB.id)!!.removed.isEmpty())
        val row = serverChanges().items.single { it.track.providerTrackId == k2 }
        assertTrue("the server holds the item as a tombstone", row.deleted)
        ok("delete item on A is a tombstone on B", "B keys ${b.keys(onB.id).size}, server row deleted=${row.deleted}, seq=${row.seq}")

        // 4. same-second concurrent edits, sub-second LWW (the older edit arrives last, then the other way round)
        val base = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        a.clock.pinned = base.plusMillis(200); b.clock.pinned = base.plusMillis(700)
        a.libraries.rename(list.id, "A edit .200"); b.libraries.rename(onB.id, "B edit .700")
        a.sync(); b.sync(); a.sync()                   // A pushes first (.200), B second (.700): the later fraction must win
        assertEquals("B edit .700", serverChanges().libraries.single { it.id == Ids.libraryId(list.id) }.name)
        assertEquals("B edit .700", a.libraries.get(list.id)!!.name); assertEquals("B edit .700", b.libraries.get(onB.id)!!.name)
        val base2 = base.plusSeconds(10)
        a.clock.pinned = base2.plusMillis(800); b.clock.pinned = base2.plusMillis(300)
        a.libraries.rename(list.id, "A edit .800"); b.libraries.rename(onB.id, "B edit .300")
        a.sync(); b.sync(); a.sync()                   // newer (A, .800) pushed first; older (B, .300) arrives later and must lose
        val rec = serverChanges().libraries.single { it.id == Ids.libraryId(list.id) }
        assertEquals("A edit .800", rec.name)
        assertEquals("A edit .800", a.libraries.get(list.id)!!.name); assertEquals("A edit .800", b.libraries.get(onB.id)!!.name)
        assertEquals("the server's updated_at keeps the fraction", base2.plusMillis(800), rec.updatedAt)
        ok("same-second edits: sub-second LWW", "round 1 winner 'B edit .700' (pushed last), round 2 winner 'A edit .800' (pushed first), server updated_at ${rec.updatedAt}")
        a.clock.pinned = null; b.clock.pinned = null

        // 5. history play on A -> B
        val ev = HistoryEntry(track("play"), "Played on A", "e2e artist", Instant.now().minusSeconds(5), "ytm", 200_000, 200_000, true, "phone-A", "https://example.com/p.jpg", UUID.randomUUID().toString())
        a.engine.recordPlay(ev)
        a.sync(); b.sync()
        val got = b.engine.history().singleOrNull { it.eventId == ev.eventId }
        assertNotNull("the play did not reach B: ${b.engine.history()}", got)
        assertTrue(got!!.device == "phone-A" && got.title == "Played on A" && got.thumbnail == "https://example.com/p.jpg" && got.completed)
        assertEquals("A's own play came back and was not doubled", 1, a.engine.history().count { it.eventId == ev.eventId })
        ok("history play on A reaches B", "device=${got.device}, playedAt ns kept=${got.playedAt == ev.playedAt.truncatedTo(ChronoUnit.MICROS)}")

        // 6. clear history on B -> gone on A
        b.engine.clearHistory(); runBlocking { b.engine.settled() }
        a.sync()
        assertTrue("history left on A: ${a.engine.history()}", a.engine.history().isEmpty())
        val plays = serverEvents()
        assertTrue("server events ${plays.events.size}, cleared_at=${plays.clearedAt}", plays.events.isEmpty() && plays.clearedAt != null)
        assertNull(b.engine.settings.historyClearPending)
        ok("clear history on B clears A", "server cleared_at=${plays.clearedAt}, A history=0, B pending=null")

        // 7. Daily mix: one identity on every device
        if (dailyMixAllowed) {
            val mix = a.libraries.addRemote(Ids.dailyMixId(), "Daily mix", "", Instant.now())
            a.libraries.addItems(mix.id, listOf(song(track("dm1"), "Mix one").copy(why = "because e2e"), song(track("dm2"), "Mix two")))
            a.sync(); b.sync()
            val mixB = b.libraries.list().filter { it.name == "Daily mix" }
            assertEquals("B holds exactly one Daily mix: $mixB", 1, mixB.size)
            assertEquals(Ids.dailyMixId(), mixB[0].serverId)
            assertEquals(2, mixB[0].items.size)
            assertEquals("because e2e", mixB[0].items[0].why)
            assertEquals(mixB[0].id, b.engine.settings.dailyMixId)
            assertEquals("one such list on the server", 1, serverChanges().libraries.count { it.id == Ids.dailyMixId() })
            ok("Daily mix id identical", "server id ${Ids.dailyMixId()} on A, B and the server; B adopted it; note travelled")
        } else {
            results += "SKIP  Daily mix id identical  (the fixed id belongs to another user on this server; see the server bug in the report)"
        }

        // 8. a fresh phone adopts the server
        val c = Phone("phone-C"); c.sync()
        assertEquals(a.libraries.list().map { it.name }.sorted(), c.libraries.list().map { it.name }.sorted())
        assertTrue(c.engine.settings.syncTwoWay)
        ok("fresh phone adopts the server", "C holds ${c.libraries.list().size} lists, first sync is two-way")

        // 9. a refused session: 401 against the real server
        val d = Phone("phone-D", signedIn = false)
        d.engine.testSignIn(url, email, "not-a-real-token")
        d.tokens.save("not-a-real-token")
        d.engine.testUpdateSettings { it.copy(syncCursor = 5, syncCursorUrl = url, syncCursorEmail = email) }
        runBlocking { d.engine.pullChanges(); d.engine.settled() }
        assertTrue("expired=${d.engine.expired} token='${d.engine.token}'", d.engine.expired && d.engine.token == "")
        assertNull(d.tokens.load()); assertEquals(5L, d.engine.settings.syncCursor)
        ok("401 -> signed-out state", "message '$SESSION_ENDED_MESSAGE', cursors and account kept")

        val ms = (System.nanoTime() - t0) / 1_000_000
        val summary = results.joinToString("\n") + "\nrequests=${requests.get()} avgLatencyMs=%.1f totalMs=$ms\n".format(latencyNanos.get() / 1e6 / maxOf(1, requests.get()))
        println("LIVE SUMMARY\n$summary")
        File(System.getProperty("user.dir"), "build/live-sync-summary.txt").writeText(summary)
    }
}
