package com.muratkirazkaya.meiro.lyrics

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `internal/lrclib` ported: the LRC reader, the short title and the line being sung (its Go tests' cases), the
 * reading of real answers recorded by `tools/record-ytm-fixtures.sh lyrics` (`/api/get` and `/api/search` for "Get
 * Lucky"), and the client's request rules against a local server (no network).
 */
class LrclibTest {
    private fun fixture(name: String) = LrclibTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()
    private val server = MockWebServer()

    @After fun stop() = server.shutdown()

    @Test fun readsTheRecordedExactMatch() {
        val l = Lrc.fromJson(JSONObject(fixture("lrclib_get_get_lucky.json")))
        assertFalse(l.instrumental)
        assertTrue(l.plain.isNotEmpty())
        assertEquals(114, l.synced.size)
        assertEquals(31_480L, l.synced.first().atMs)
        assertEquals(314_270L, l.synced.last().atMs)
        assertEquals(l.synced.sortedBy { it.atMs }, l.synced)
    }

    @Test fun readsTheRecordedSearchHits() {
        val many = JSONArray(fixture("lrclib_search_get_lucky.json"))
        assertEquals(5, many.length())
        for (i in 0 until many.length()) assertTrue(Lrc.fromJson(many.getJSONObject(i)).synced.size >= 79)
    }

    @Test fun parseSkipsTagsAndUnstampedLinesAndSplitsRepeatedStamps() {
        val lines = Lrc.parse("[ar: Someone]\n[ti: Song]\nno stamp\n[00:01.50]one\n[00:05.00][00:20.25] chorus \n[00:10]two\n[xx:yy]bad")
        assertEquals(listOf(1_500L to "one", 5_000L to "chorus", 10_000L to "two", 20_250L to "chorus"), lines.map { it.atMs to it.text })
    }

    @Test fun parseKeepsTheCopyWithMoreNonAsciiLetters() {
        // A romanised copy first, then the original from the top again (the time jumps back by more than 10 s).
        val lrc = "[00:10.00]kimi no na wa\n[00:20.00]sora\n[00:30.00]end\n[00:10.00]君の名は\n[00:20.00]空\n[00:30.00]終"
        assertEquals(listOf("君の名は", "空", "終"), Lrc.parse(lrc).map { it.text })
        // No difference: the first copy.
        assertEquals(listOf("a", "b"), Lrc.parse("[00:30.00]a\n[00:40.00]b\n[00:05.00]c\n[00:06.00]d").map { it.text })
        // A small step back is not a new copy.
        assertEquals(3, Lrc.parse("[00:30.00]a\n[00:25.00]b\n[00:40.00]c").size)
    }

    @Test fun shortTitleCutsTheVersionAndTheGuestCredit() {
        assertEquals("Get Lucky", Lrc.shortTitle("Get Lucky (feat. Pharrell Williams and Nile Rodgers)"))
        assertEquals("Song", Lrc.shortTitle("Song - Remastered 2011"))
        assertEquals("Song", Lrc.shortTitle("Song (with Someone) (ft. Other)"))
        assertEquals("Song (Live)", Lrc.shortTitle("Song (Live)"))
        assertEquals("- Intro", Lrc.shortTitle("- Intro"))
        assertEquals("Şarkı", Lrc.shortTitle("Şarkı（feat. Biri）"))
    }

    @Test fun indexAtIsTheLastLineThatStarted() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b"), LyricLine(3_000, "c"))
        assertEquals(-1, Lrc.indexAt(lines, 999))
        assertEquals(0, Lrc.indexAt(lines, 1_000))
        assertEquals(1, Lrc.indexAt(lines, 2_999))
        assertEquals(2, Lrc.indexAt(lines, 60_000))
        assertEquals(-1, Lrc.indexAt(emptyList(), 5))
    }

    private fun client() = LrclibClient(base = server.url("/").toString().trimEnd('/'), retryDelayMs = 1)

    @Test fun exactMatchFirstWithTheAppNameAndRoundedSeconds() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("lrclib_get_get_lucky.json")))
        val l = client().get(LrcQuery("Get Lucky", "Daft Punk", "Random Access Memories", 368_600))!!
        assertEquals(114, l.synced.size)
        val r = server.takeRequest()
        assertEquals("Meiro (https://github.com/elianiva/meiro)", r.getHeader("User-Agent"))
        assertEquals("/api/get", r.requestUrl!!.encodedPath)
        assertEquals("Get Lucky", r.requestUrl!!.queryParameter("track_name"))
        assertEquals("Daft Punk", r.requestUrl!!.queryParameter("artist_name"))
        assertEquals("Random Access Memories", r.requestUrl!!.queryParameter("album_name"))
        assertEquals("369", r.requestUrl!!.queryParameter("duration"))
        assertEquals(1, server.requestCount)
    }

    @Test fun noAlbumNoDurationLeavesThemOut() = runBlocking {
        server.enqueue(MockResponse().setBody(fixture("lrclib_get_get_lucky.json")))
        client().get(LrcQuery("Get Lucky", "Daft Punk"))
        val u = server.takeRequest().requestUrl!!
        assertNull(u.queryParameter("album_name")); assertNull(u.queryParameter("duration"))
    }

    @Test fun notFoundSearchesByTitleThenByShortTitle() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody(fixture("lrclib_search_get_lucky.json")))
        val l = client().get(LrcQuery("Get Lucky (feat. Pharrell Williams)", "Daft Punk"))!!
        assertEquals(113, l.synced.size) // the first hit with timed lines
        server.takeRequest()
        assertEquals("Get Lucky (feat. Pharrell Williams)", server.takeRequest().requestUrl!!.queryParameter("track_name"))
        val third = server.takeRequest().requestUrl!!
        assertEquals("/api/search", third.encodedPath)
        assertEquals("Get Lucky", third.queryParameter("track_name"))
    }

    @Test fun searchPrefersTimedLinesElseTheFirstPlain() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""[{"plainLyrics":null,"syncedLyrics":null},{"plainLyrics":"first plain","syncedLyrics":null},{"plainLyrics":"second","syncedLyrics":""}]"""))
        val l = client().get(LrcQuery("X", "Y"))!!
        assertEquals("first plain", l.plain)
        assertTrue(l.synced.isEmpty())
    }

    @Test fun instrumentalEndsTheLookup() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"instrumental":true,"plainLyrics":null,"syncedLyrics":null}"""))
        val l = client().get(LrcQuery("Tron Legacy (End Titles)", "Daft Punk"))!!
        assertTrue(l.instrumental && l.empty())
        assertEquals(1, server.requestCount)
    }

    @Test fun serverErrorsAreTriedThreeTimes() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody(fixture("lrclib_get_get_lucky.json")))
        assertEquals(114, client().get(LrcQuery("Get Lucky", "Daft Punk"))!!.synced.size)
        assertEquals(3, server.requestCount)
    }

    @Test fun nothingFoundIsNullAndNothingButFailuresThrows() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("[]"))
        assertNull(client().get(LrcQuery("Nothing", "Nobody")))
        val failing = MockWebServer()
        repeat(9) { failing.enqueue(MockResponse().setResponseCode(500)) }
        try {
            LrclibClient(base = failing.url("/").toString().trimEnd('/'), retryDelayMs = 1).get(LrcQuery("A", "B"))
            fail("no error")
        } catch (e: LrclibException) {
            assertTrue(e.message!!.contains("500"))
        } finally { failing.shutdown() }
        // get, then the search for the title (no short title): 3 tries each.
        assertEquals(6, failing.requestCount)
    }

    @Test fun noTitleAsksNothing() = runBlocking {
        assertNull(client().get(LrcQuery("", "Daft Punk")))
        assertEquals(0, server.requestCount)
    }
}
