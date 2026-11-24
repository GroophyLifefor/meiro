package com.muratkirazkaya.meiro.downloads

import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.Resolved
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * M6e: the download service's start and stop rules ([DownloadForeground]) against the real queue, and the
 * notification's contents (the mock's lock screen `.notif`, which mirrors the "İniyor" row).
 */
class DownloadServiceTest {
    private lateinit var dir: File
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before fun setUp() { dir = Files.createTempDirectory("meiro-fg").toFile() }
    @After fun tearDown() { scope.cancel(); dir.deleteRecursively(); server.shutdown() }

    private fun item(k: String) = QItem(key = k, title = "T$k", artist = "A", duration = "2:00", thumbnail = "https://i/$k.jpg")
    private fun batch(name: String, total: Int, done: Int, running: Int, progress: Float, failed: Int = 0, single: Boolean = false, artist: String = "", wifi: Boolean = false) =
        DownloadBatch("b$name", name, "", total, done, failed, running, progress, single, artist, wifi)

    /** A stand-in for [DownloadService]: a start runs [DownloadForeground.serve] as onStartCommand does. */
    private inner class FakeService(val fg: DownloadForeground, val refuse: AtomicBoolean = AtomicBoolean(false)) {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val shown: MutableList<DownloadNotice> = Collections.synchronizedList(mutableListOf())
        fun start() {
            starts.incrementAndGet()
            if (refuse.get()) throw IllegalStateException("startForegroundService() not allowed")
            scope.launch { fg.serve(show = { shown += it }, stop = { stops.incrementAndGet() }) }
        }
    }

    private suspend fun until(what: String, cond: () -> Boolean) {
        try { withTimeout(10_000) { while (!cond()) delay(10) } } catch (e: Exception) { throw AssertionError("timed out waiting for $what") }
    }

    private fun slowServer(code: (RecordedRequest) -> Int = { 200 }) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val c = code(request)
                return if (c != 200) MockResponse().setResponseCode(c)
                else MockResponse().setHeader("Content-Type", "audio/webm").setBody(Buffer().write(ByteArray(4000) { it.toByte() }))
                    .throttleBody(500, 50, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun downloader(store: DownloadStore, allowed: () -> Boolean = { true }) = Downloader(
        store, scope, resolve = { Resolved(server.url("/${it.key}").toString(), 0, false) }, fetch = AudioFetch(), allowed = allowed, recheckMs = 20,
    )

    // ---- the notification -------------------------------------------------------------------------------

    @Test fun theNoticeMirrorsTheRowAndIsNullForAnEmptyQueue() {
        assertNull(DownloadForeground.notice(emptyList()))
        // the mock: "Daily mix", "12 / 30 şarkı · Wi-Fi bekleniyor", the bar at 40%
        assertEquals(DownloadNotice("Daily mix", "12 / 30 şarkı · Wi-Fi bekleniyor", 40),
            DownloadForeground.notice(listOf(batch("Daily mix", 30, 12, 0, 0.4f, wifi = true))))
        assertEquals(DownloadNotice("Gece Sürüşü", "3 / 12 şarkı", 25), DownloadForeground.notice(listOf(batch("Gece Sürüşü", 12, 3, 2, 0.25f))))
        assertEquals(DownloadNotice("Gece Sürüşü", "3 / 12 şarkı · 1 indirilemedi", 33), DownloadForeground.notice(listOf(batch("Gece Sürüşü", 12, 3, 1, 0.33f, failed = 1))))
        // one track from its menu: the artist and its percent, as the row
        assertEquals(DownloadNotice("Stolen Dance", "Milky Chance · %50", 50), DownloadForeground.notice(listOf(batch("Stolen Dance", 1, 0, 1, 0.5f, single = true, artist = "Milky Chance"))))
    }

    @Test fun severalBatchesAreCountedAndSummed() {
        val n = DownloadForeground.notice(listOf(batch("Daily mix", 30, 12, 2, 0.4f), batch("Stolen Dance", 1, 0, 0, 0f, single = true, artist = "Milky Chance")))!!
        assertEquals("2 indirme", n.title)
        assertEquals("12 / 31 şarkı", n.text)
        assertEquals(38, n.percent) // (0.4 * 30 + 0) / 31
        val waiting = DownloadForeground.notice(listOf(batch("A", 2, 0, 0, 0f, wifi = true), batch("B", 3, 1, 0, 0.33f, wifi = true)))!!
        assertEquals("1 / 5 şarkı · Wi-Fi bekleniyor", waiting.text)
    }

    // ---- start and stop ---------------------------------------------------------------------------------

    @Test fun startsOnceForAQueueAndStopsWhenItIsDone() = runBlocking {
        slowServer()
        val store = DownloadStore(File(dir, "dl"))
        val d = downloader(store)
        val fg = DownloadForeground(d.state)
        val svc = FakeService(fg)
        fg.watch(scope) { svc.start() }
        delay(100)
        assertEquals("an empty queue starts nothing", 0, svc.starts.get())
        d.download(listOf(item("a"), item("b"), item("c")), "Liste")
        until("the queue to finish") { store.list().size == 3 && d.state.value.isEmpty() }
        until("the service to stop") { svc.stops.get() == 1 }
        assertEquals("one start for the whole queue, not one per progress step", 1, svc.starts.get())
        assertFalse(fg.running)
        assertTrue("the notification followed the queue", svc.shown.size > 1)
        assertEquals("Liste", svc.shown.first().title)
        assertTrue(svc.shown.any { it.text.startsWith("1 / 3") || it.text.startsWith("2 / 3") })
        delay(200)
        assertEquals("nothing starts it again while the queue stays empty", 1, svc.starts.get())
    }

    @Test fun aQueueWhoseTracksAllFailStopsIt() = runBlocking {
        slowServer { 403 }
        val store = DownloadStore(File(dir, "dl"))
        val d = downloader(store)
        val fg = DownloadForeground(d.state)
        val svc = FakeService(fg)
        fg.watch(scope) { svc.start() }
        d.download(listOf(item("a"), item("b")), "Liste")
        until("the service to stop") { svc.stops.get() == 1 }
        assertTrue(store.list().isEmpty()); assertTrue(d.state.value.isEmpty()); assertFalse(fg.running)
        assertEquals(1, svc.starts.get())
    }

    @Test fun hepsiniDurdurStopsIt() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val d = Downloader(DownloadStore(File(dir, "dl")), scope, resolve = { gate.await(); error("never") }, fetch = AudioFetch())
        val fg = DownloadForeground(d.state)
        val svc = FakeService(fg)
        fg.watch(scope) { svc.start() }
        d.download(listOf(item("a"), item("b"), item("c")), "L")
        until("the service to run") { fg.running }
        assertEquals(0, svc.stops.get())
        d.cancelAll() // the notification's action calls this
        until("the service to stop") { svc.stops.get() == 1 }
        assertFalse(fg.running)
    }

    /** "Yalnız Wi-Fi'de indir" off Wi-Fi: the queue is not done, so the service stays, and stops after the queue ran on Wi-Fi. */
    @Test fun waitingForWifiKeepsItRunning() = runBlocking {
        slowServer()
        val store = DownloadStore(File(dir, "dl"))
        val wifi = AtomicBoolean(false)
        val d = downloader(store) { wifi.get() }
        val fg = DownloadForeground(d.state)
        val svc = FakeService(fg)
        fg.watch(scope) { svc.start() }
        d.download(listOf(item("a"), item("b")), "L")
        until("the service to run") { fg.running }
        delay(300)
        assertTrue(fg.running); assertEquals(0, svc.stops.get())
        until("the notification to say it waits") { svc.shown.lastOrNull()?.text == "0 / 2 şarkı · Wi-Fi bekleniyor" }
        wifi.set(true)
        until("the service to stop") { svc.stops.get() == 1 }
        assertEquals(2, store.list().size)
    }

    @Test fun aNewQueueAfterAStopStartsItAgain() = runBlocking {
        slowServer()
        val store = DownloadStore(File(dir, "dl"))
        val d = downloader(store)
        val fg = DownloadForeground(d.state)
        val svc = FakeService(fg)
        fg.watch(scope) { svc.start() }
        d.download(listOf(item("a")), null)
        until("the first stop") { svc.stops.get() == 1 }
        d.download(listOf(item("b"), item("c")), "Liste")
        until("the second stop") { svc.stops.get() == 2 }
        assertEquals(2, svc.starts.get())
        assertEquals(3, store.list().size)
    }

    /** Android refusing the start (from the background, past the time limit) is not retried on every progress step. */
    @Test fun aRefusedStartIsNotRetriedUntilTheServiceStopsAgain() = runBlocking {
        val state = MutableStateFlow<List<DownloadBatch>>(emptyList())
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val fg = DownloadForeground(state) { logs += it }
        val svc = FakeService(fg, refuse = AtomicBoolean(true))
        fg.watch(scope) { svc.start() }
        for (i in 0 until 10) { state.value = listOf(batch("L", 10, i, 1, i / 10f)); delay(20) }
        assertEquals(1, svc.starts.get())
        assertTrue(logs.any { it.contains("could not start") })
        svc.refuse.set(false)
        fg.stopped() // what DownloadService does on Android's time limit
        until("a new start") { svc.starts.get() == 2 && fg.running }
        state.value = emptyList()
        until("the stop") { svc.stops.get() == 1 }
    }
}
