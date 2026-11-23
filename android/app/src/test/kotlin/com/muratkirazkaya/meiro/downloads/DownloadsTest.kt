package com.muratkirazkaya.meiro.downloads

import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.Resolved
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** The download store (`internal/downloads`), the fetcher (`audio.Save` plus HLS), the playback cache and the queue. */
class DownloadsTest {
    private lateinit var dir: File
    private val server = MockWebServer()

    @Before fun setUp() { dir = Files.createTempDirectory("meiro-dl").toFile() }
    @After fun tearDown() { dir.deleteRecursively(); server.shutdown() }

    private fun rec(key: String, at: Long, size: Long = 10, source: String = Source.YTM) =
        DownloadItem(source = source, key = key, title = "T $key", artist = "A", sizeBytes = size, downloadedAt = at, file = DownloadStore.fileName(source, key, ".webm"))

    // ---- store ------------------------------------------------------------------------------------------

    @Test fun storeKeepsNewestFirstAndSurvivesARestart() {
        val s = DownloadStore(dir) { 1000 }
        s.add(rec("a", 1)); s.add(rec("b", 3)); s.add(rec("c", 2, source = Source.SOUNDCLOUD))
        assertEquals(listOf("b", "c", "a"), s.list().map { it.key })
        assertEquals(setOf("ytm:a", "ytm:b", "soundcloud:c"), s.keys.value)
        val again = DownloadStore(dir) { 1000 }
        assertEquals(listOf("b", "c", "a"), again.list().map { it.key })
        assertEquals(30L to 3, again.usage())
    }

    @Test fun addingTheSameTrackReplacesIt() {
        val s = DownloadStore(dir)
        s.add(rec("a", 1, size = 5)); s.add(rec("a", 2, size = 7))
        assertEquals(1, s.list().size)
        assertEquals(7, s.get(Source.YTM, "a")!!.sizeBytes)
    }

    @Test fun removeDeletesTheFileAndRemembersItForTheServer() {
        var now = 5_000L
        val s = DownloadStore(dir) { now }
        val r = rec("a", 1)
        File(dir, r.file).writeText("audio")
        s.add(r)
        assertNotNull(s.file(Source.YTM, "a"))
        val gone = s.remove(Source.YTM, "a")
        assertEquals("a", gone!!.key)
        assertFalse(File(dir, r.file).exists())
        assertNull(s.file(Source.YTM, "a"))
        assertEquals(listOf(DownloadTombstone(Source.YTM, "a", "T a", "A", 5_000)), s.removedList())
        // downloaded again: no longer a removal
        s.add(r); assertTrue(s.removedList().isEmpty())
        s.remove(Source.YTM, "a")
        // removals are forgotten after 90 days
        now += DownloadStore.TOMBSTONE_LIFE_MS + 1
        assertTrue(DownloadStore(dir) { now }.removedList().isEmpty())
    }

    @Test fun touchSetsLastListenedOnlyForKeptTracks() {
        val s = DownloadStore(dir) { 42 }
        s.add(rec("a", 1))
        s.touch(Source.YTM, "a"); s.touch(Source.YTM, "nope")
        assertEquals(42, s.get(Source.YTM, "a")!!.lastListenedAt)
        assertEquals(1, s.list().size)
    }

    @Test fun aDamagedIndexStartsEmpty() {
        File(dir, "index.json").writeText("{not json")
        assertTrue(DownloadStore(dir).list().isEmpty())
    }

    @Test fun fileNamesAreStableAndProviderQualified() {
        val a = DownloadStore.fileName(Source.YTM, "x", ".m4a")
        assertEquals(a, DownloadStore.fileName("", "x", ".m4a"))
        assertTrue(a.matches(Regex("[0-9a-f]{64}\\.m4a")))
        assertTrue(a != DownloadStore.fileName(Source.SOUNDCLOUD, "x", ".m4a"))
    }

    @Test fun syncSnapshotSendsKeptTracksAndRemovals() {
        var now = 1_700_000_000_000L
        val s = DownloadStore(dir) { now }
        s.add(rec("a", now - 10_000, size = 99).copy(duration = "3:20"))
        s.add(rec("https://soundcloud.com/x/y", now - 5_000, source = Source.SOUNDCLOUD))
        s.touch(Source.YTM, "a")
        now += 1000
        s.remove(Source.SOUNDCLOUD, "https://soundcloud.com/x/y")
        val snap = DownloadSync.snapshot(s, "Pixel")
        assertEquals(2, snap.size)
        val kept = snap.first { !it.deleted }
        assertEquals("ytm", kept.track.provider); assertEquals("a", kept.track.providerTrackId); assertEquals(200_000, kept.track.durationMs)
        assertEquals("Pixel", kept.device); assertEquals(99, kept.sizeBytes)
        assertEquals(java.time.Instant.ofEpochMilli(now - 1000), kept.lastListenedAt)
        assertEquals(kept.lastListenedAt, kept.updatedAt)
        val gone = snap.first { it.deleted }
        assertEquals("soundcloud", gone.track.provider); assertEquals("https://soundcloud.com/x/y", gone.track.providerTrackId)
        assertEquals(java.time.Instant.ofEpochMilli(now), gone.updatedAt)
    }

    // ---- fetch ------------------------------------------------------------------------------------------

    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    @Test fun progressiveStreamComesInRangesWithChromesUserAgent() = runBlocking {
        val body = bytes(2500)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val m = Regex("bytes=(\\d+)-(\\d+)").find(request.getHeader("Range")!!)!!
                val a = m.groupValues[1].toInt(); val b = minOf(m.groupValues[2].toInt(), body.size - 1)
                return MockResponse().setResponseCode(206).setHeader("Content-Type", "audio/mp4")
                    .setHeader("Content-Range", "bytes $a-$b/${body.size}").setBody(Buffer().write(body.copyOfRange(a, b + 1)))
            }
        }
        val seen = mutableListOf<Pair<Long, Long>>()
        val f = AudioFetch(range = 1000).save(server.url("/audio").toString(), false, File(dir, "x")) { b, t -> seen += b to t }
        assertEquals("x.m4a", f.name)
        assertArrayEquals(body, f.readBytes())
        assertEquals(3, server.requestCount)
        val first = server.takeRequest()
        assertEquals("bytes=0-999", first.getHeader("Range"))
        assertTrue(first.getHeader("User-Agent")!!.contains("Chrome/"))
        assertEquals(2500L to 2500L, seen.last())
        assertFalse(File(dir, ".part-x").exists())
    }

    @Test fun aServerThatIgnoresTheRangeSendsItAllAtOnce() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/webm").setBody(Buffer().write(bytes(300))))
        val f = AudioFetch(range = 100).save(server.url("/a").toString(), false, File(dir, "y"))
        assertEquals(300, f.length()); assertEquals("y.webm", f.name); assertEquals(1, server.requestCount)
    }

    @Test fun anErrorLeavesNoFile() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        try { AudioFetch().save(server.url("/a").toString(), false, File(dir, "z")); fail("no error") } catch (e: FetchException) {
            assertTrue(e.message!!.contains("403"))
        }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun hlsJoinsTheInitSectionAndTheSegmentsInOrder() = runBlocking {
        val parts = mapOf("/init.mp4" to bytes(10), "/s1.m4s" to bytes(20), "/s2.m4s" to bytes(30))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (val p = request.path!!.substringBefore('?')) {
                "/master.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\nmedia/list.m3u8?sig=1\n")
                "/media/list.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-MAP:URI=\"/init.mp4\"\n#EXTINF:10,\n/s1.m4s\n#EXTINF:10,\n/s2.m4s\n#EXT-X-ENDLIST\n")
                else -> parts[p]?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
            }
        }
        val f = AudioFetch().save(server.url("/master.m3u8").toString(), true, File(dir, "h"))
        assertEquals("h.m4a", f.name)
        assertArrayEquals(parts["/init.mp4"]!! + parts["/s1.m4s"]!! + parts["/s2.m4s"]!!, f.readBytes())
    }

    @Test fun anEncryptedPlaylistIsRefused() = runBlocking {
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:10,\ns1.ts\n"))
        try { AudioFetch().save(server.url("/l.m3u8").toString(), true, File(dir, "e")); fail("no error") } catch (e: FetchException) {
            assertTrue(e.message!!.contains("encrypted"))
        }
    }

    @Test fun extensionsFollowTheMediaType() {
        assertEquals(".webm", AudioFetch.extensionFor("audio/webm; codecs=opus"))
        assertEquals(".m4a", AudioFetch.extensionFor("audio/mp4"))
        assertEquals(".mp3", AudioFetch.extensionFor("audio/mpeg"))
        assertEquals(".webm", AudioFetch.extensionFor(""))
        assertTrue(AudioFetch.isPlaylist("https://x/y/playlist.m3u8?a=1", ""))
        assertTrue(AudioFetch.isPlaylist("https://x/y", "application/vnd.apple.mpegurl"))
        assertEquals(".ts", Hls.parse("#EXTM3U\nseg1.ts\n", "https://h/a/").extension)
        assertEquals(".mp3", Hls.parse("#EXTM3U\nhttps://cf/seg1.mp3?x\n", "https://h/a/").extension)
    }

    // ---- playback cache ---------------------------------------------------------------------------------

    @Test fun cacheKeepsCompleteFilesAndEvictsTheLeastRecentlyUsed() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "audio/webm").setBody(Buffer().write(bytes(400)))
        }
        val cache = AudioCache(dir, limitBytes = 1000, fetch = AudioFetch(), scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
        suspend fun put(k: String) {
            cache.add(QItem(key = k, title = "T$k", artist = "A$k", duration = "1:00"), server.url("/$k").toString(), false)
            withTimeout(5_000) { while (!cache.has(k)) delay(20) }
            delay(50)
        }
        put("a"); Thread.sleep(20); put("b")
        // a is used again, so b is the least recently used when c needs room
        assertNotNull(cache.get("a")); Thread.sleep(20)
        put("c")
        withTimeout(5_000) { while (cache.has("b")) delay(20) }
        assertTrue(cache.has("a")); assertTrue(cache.has("c"))
        assertTrue(cache.usage() <= 1000)
        assertEquals(setOf("a", "c"), cache.metas().map { it.key }.toSet())
        assertEquals("Ta", cache.metas().first { it.key == "a" }.title)
        // a second add of a cached track asks nothing
        val before = server.requestCount
        cache.add(QItem(key = "a"), server.url("/a").toString(), false); delay(100)
        assertEquals(before, server.requestCount)
    }

    @Test fun aCacheWithNoRoomIsOff() {
        val cache = AudioCache(dir, limitBytes = 0, fetch = AudioFetch(), scope = CoroutineScope(Dispatchers.IO))
        File(dir, DownloadStore.fileName("", "k", "").take(64) + ".webm").writeText("x")
        assertNull(cache.get("k"))
    }

    // ---- queue ------------------------------------------------------------------------------------------

    private fun item(k: String) = QItem(key = k, title = "T$k", artist = "A", duration = "2:00", thumbnail = "https://i/$k.jpg")

    @Test fun atMostTwoRunAtOnceAndAKeptOrPendingTrackIsNotAskedAgain() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setHeader("Content-Type", "audio/webm").setBody(Buffer().write(bytes(100))).setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        val store = DownloadStore(File(dir, "dl"))
        val running = AtomicInteger(); val most = AtomicInteger()
        val finished = CompletableDeferred<DownloadBatch>()
        val d = Downloader(
            store, CoroutineScope(SupervisorJob() + Dispatchers.IO),
            resolve = { q ->
                most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                delay(50)
                running.decrementAndGet()
                Resolved(server.url("/${q.key}").toString(), 0, false)
            },
            fetch = AudioFetch(), onFinished = { finished.complete(it) },
        )
        val ask = d.download(listOf(item("a"), item("b"), item("c"), item("d"), item("a")), "Liste")
        assertEquals(DownloadAsk(4, 0, 0), ask)
        assertEquals(DownloadAsk(0, 0, 2), d.download(listOf(item("a"), item("b")), "Liste"))
        assertEquals(1, d.state.value.size)
        val b = withTimeout(10_000) { finished.await() }
        assertEquals(4, b.done); assertEquals(0, b.failed); assertEquals("Liste", b.name)
        assertTrue("at most 2 at once, saw ${most.get()}", most.get() <= 2)
        assertEquals(4, store.list().size)
        assertEquals(DownloadAsk(0, 2, 0), d.download(listOf(item("a"), item("b")), "Liste"))
        val kept = store.get(Source.YTM, "a")!!
        assertEquals("Ta", kept.title); assertEquals(100, kept.sizeBytes); assertEquals("https://i/a.jpg", kept.thumbnail)
        assertTrue(File(store.dir, kept.file).isFile)
        assertTrue(d.state.value.isEmpty())
    }

    @Test fun aFailureIsCountedAndAFinishedBatchLeaves() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path!!.contains("bad")) MockResponse().setResponseCode(403) else MockResponse().setBody(Buffer().write(bytes(10)))
        }
        val store = DownloadStore(File(dir, "dl"))
        val finished = CompletableDeferred<DownloadBatch>()
        val d = Downloader(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), resolve = { Resolved(server.url("/${it.key}").toString(), 0, false) },
            fetch = AudioFetch(), onFinished = { finished.complete(it) })
        d.download(listOf(item("ok"), item("bad")), null)
        val b = withTimeout(10_000) { finished.await() }
        assertTrue(b.single); assertEquals("Tok", b.name); assertEquals(1, b.done); assertEquals(1, b.failed)
        assertEquals(listOf("ok"), store.list().map { it.key })
    }

    @Test fun stoppingABatchLeavesNothingBehind() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val store = DownloadStore(File(dir, "dl"))
        val d = Downloader(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), resolve = { gate.await(); Resolved("http://127.0.0.1:1/x", 0, false) }, fetch = AudioFetch())
        d.download(listOf(item("a"), item("b"), item("c")), "L")
        withTimeout(5_000) { while (d.state.value.firstOrNull()?.running != 2) delay(10) }
        d.cancelAll()
        assertTrue(d.state.value.isEmpty())
        delay(200)
        assertTrue(store.list().isEmpty())
        assertEquals(emptyList<String>(), store.dir.listFiles()!!.map { it.name }.filter { !it.startsWith("index.json") })
        assertFalse(d.pending(item("a")))
    }

    @Test fun aTrackInThePlaybackCacheIsCopiedFromThere() = runBlocking {
        val cached = File(dir, "c.webm").apply { writeBytes(bytes(77)) }
        val store = DownloadStore(File(dir, "dl"))
        val finished = CompletableDeferred<DownloadBatch>()
        val d = Downloader(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), resolve = { error("no network needed") }, fetch = AudioFetch(),
            cached = { if (it.key == "a") cached else null }, onFinished = { finished.complete(it) })
        d.download(listOf(item("a")), null)
        assertEquals(1, withTimeout(5_000) { finished.await() }.done)
        val kept = store.file(Source.YTM, "a")!!
        assertEquals(77, kept.length()); assertTrue(kept.name.endsWith(".webm"))
    }

    /** "Yalnız Wi-Fi'de indir": off Wi-Fi nothing starts and the row says "Wi-Fi bekleniyor"; on Wi-Fi the batch goes on. */
    @Test fun withWifiOnlyNothingStartsUntilWifi() = runBlocking {
        val cached = File(dir, "c.webm").apply { writeBytes(bytes(10)) }
        val store = DownloadStore(File(dir, "dl"))
        val wifi = java.util.concurrent.atomic.AtomicBoolean(false)
        val finished = CompletableDeferred<DownloadBatch>()
        val d = Downloader(store, CoroutineScope(SupervisorJob() + Dispatchers.IO), resolve = { error("cached") }, fetch = AudioFetch(),
            cached = { cached }, onFinished = { finished.complete(it) }, allowed = { wifi.get() }, recheckMs = 20)
        d.download(listOf(item("a"), item("b")), "L")
        delay(200)
        assertTrue(store.list().isEmpty())
        val row = d.state.value.single()
        assertTrue(row.waitingWifi); assertEquals(0, row.running)
        assertEquals("0 / 2 şarkı · Wi-Fi bekleniyor", com.muratkirazkaya.meiro.ui.DownloadsMapping.pending(row).meta)
        wifi.set(true)
        assertEquals(2, withTimeout(5_000) { finished.await() }.done)
        assertEquals(2, store.list().size)
    }

    @Test fun clearingACacheRemovesItsFilesAndWhatItKnew() {
        val cache = AudioCache(dir, fetch = AudioFetch(), scope = CoroutineScope(Dispatchers.IO))
        File(dir, DownloadStore.fileName("", "k", "").take(64) + ".webm").writeBytes(bytes(30))
        assertEquals(1, cache.count()); assertEquals(30L, cache.usage())
        assertEquals(30L, cache.clear())
        assertEquals(0, cache.count()); assertNull(cache.get("k")); assertTrue(cache.metas().isEmpty())
    }
}
