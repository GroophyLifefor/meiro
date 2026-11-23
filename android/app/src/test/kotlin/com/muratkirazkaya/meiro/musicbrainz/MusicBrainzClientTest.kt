package com.muratkirazkaya.meiro.musicbrainz

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Port of `musicbrainz_test.go`, `ratelimit_test.go` and `cache_test.go`, against a local server and recorded answers. */
class MusicBrainzClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    @After fun stop() = server.shutdown()

    private fun client(cache: MbCache = MbCache(null), limiter: Limiter = Limiter()) =
        MusicBrainzClient(mb = server.url("/").toString().trimEnd('/'), lb = server.url("/").toString().trimEnd('/'), cache = cache, limiter = limiter)

    @Test fun recordingSearchSendsTheDesktopsRequestAndReadsTheRecordedAnswer() = runBlocking {
        server.enqueue(MockResponse().setBody(LinkerTest.fixture("mb_recording_get_lucky.json")))
        val found = client().searchRecordings(" Get Lucky ", "Daft Punk")
        val req = server.takeRequest()
        assertEquals(MB_USER_AGENT, req.getHeader("User-Agent"))
        assertEquals("application/json", req.getHeader("Accept"))
        assertEquals("/ws/2/recording", req.requestUrl!!.encodedPath)
        assertEquals("json", req.requestUrl!!.queryParameter("fmt"))
        assertEquals("10", req.requestUrl!!.queryParameter("limit"))
        assertEquals("recording:\"Get Lucky\" AND artist:\"Daft Punk\"", req.requestUrl!!.queryParameter("query"))
        assertTrue(found.isNotEmpty())
        val first = found[0]
        assertTrue(first.title, first.title.contains("Get Lucky", ignoreCase = true))
        assertTrue(first.artistId.length == 36)
        assertTrue(first.artist, first.artist.startsWith("Daft Punk"))
        assertTrue(found.all { it.score in 0..100 })
    }

    @Test fun quotesAndEmptyArtist() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"recordings":[]}"""))
        assertEquals(emptyList<RecordingMatch>(), client().searchRecordings("A \"b\" \\c", ""))
        assertEquals("recording:\"A \\\"b\\\" \\\\c\"", server.takeRequest().requestUrl!!.queryParameter("query"))
        assertEquals(emptyList<RecordingMatch>(), client().searchRecordings("  ", "x"))
        assertEquals(1, server.requestCount)
    }

    @Test fun creditsJoinNamesAndPhrases() {
        val body = """{"recordings":[{"id":"r","title":"T","length":1000,"score":90,"first-release-date":"2013-05-17","artist-credit":[
            {"name":"Daft Punk","joinphrase":" feat. ","artist":{"id":"a1","name":"Daft Punk"}},
            {"name":"","joinphrase":"","artist":{"id":"a2","name":"Pharrell Williams"}}]}]}"""
        val r = MusicBrainzClient.parseRecordings(body).single()
        assertEquals("Daft Punk feat. Pharrell Williams", r.artist); assertEquals("a1", r.artistId); assertEquals("2013", r.year)
    }

    @Test fun artistSearchAndPopularity() = runBlocking {
        server.enqueue(MockResponse().setBody(LinkerTest.fixture("mb_artist_daft_punk.json")))
        server.enqueue(MockResponse().setBody(LinkerTest.fixture("lb_popularity_daft_punk.json")))
        val c = client()
        val artists = c.searchArtists("Daft Punk")
        assertEquals("25", server.takeRequest().requestUrl!!.queryParameter("limit"))
        val pop = c.artistPopularity(artists.map { it.id } + artists[0].id + "")
        val post = server.takeRequest()
        assertEquals("POST", post.method); assertEquals("/1/popularity/artist", post.requestUrl!!.encodedPath)
        assertEquals(MB_USER_AGENT, post.getHeader("User-Agent"))
        val sent = org.json.JSONObject(post.body.readUtf8()).getJSONArray("artist_mbids")
        val ids = (0 until sent.length()).map { sent.getString(it) }
        assertEquals(ids.sorted().distinct(), ids)
        assertTrue((pop["056e4f3e-d505-4dad-8ec1-d04f521cbb56"]?.listeners ?: 0) > 0)
    }

    @Test fun aRefusalIsRateLimitedAndPausesTheQueue() = runBlocking {
        var now = 1_000_000L
        val slept = mutableListOf<Long>()
        val limiter = Limiter(1000, { now }, { slept += it; now += it })
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "3"))
        server.enqueue(MockResponse().setBody("""{"recordings":[]}"""))
        server.enqueue(MockResponse().setBody("""{"recordings":[]}"""))
        val c = client(limiter = limiter)
        try { c.searchRecordings("a", ""); fail() } catch (e: RateLimitedException) { assertEquals(503, e.status) }
        try { c.searchRecordings("b", ""); fail() } catch (e: RateLimitedException) { assertEquals(3000, e.retryAfterMs) }
        c.searchRecordings("c", "")
        c.searchRecordings("d", "")
        // 1 s gap, then the 10 s pause after the first refusal (less the gap already waited), then the 3 s asked for.
        assertEquals(listOf(1000L, 9000L, 1000L, 2000L, 1000L), slept)
    }

    @Test fun limiterKeepsOneSecondAfterTheEndOfTheLastRequest() = runBlocking {
        var now = 0L
        val slept = mutableListOf<Long>()
        val l = Limiter(1000, { now }, { slept += it; now += it })
        now = 5000
        l.run { now += 300 }    // first: no wait; ends at 5300
        now += 200              // 5500
        l.run { }               // waits 800
        assertEquals(listOf(800L), slept)
    }

    @Test fun answersAreKeptForTheirLifetimeAndErrorsAreNot() = runBlocking {
        var now = 0L
        val cache = MbCache(tmp.newFolder("mb"), { now })
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("""{"recordings":[{"id":"r","title":"T"}]}"""))
        server.enqueue(MockResponse().setBody("""{"recordings":[]}"""))
        val c = client(cache, Limiter(0))
        try { c.searchRecordings("T", ""); fail() } catch (e: MusicBrainzException) { }
        assertEquals(1, c.searchRecordings("T", "").size)
        now += MusicBrainzClient.RECORDING_SEARCH_TTL_MS - 1
        assertEquals(1, c.searchRecordings("T", "").size)
        assertEquals(2, server.requestCount)
        now += 1
        assertEquals(0, c.searchRecordings("T", "").size)
        assertEquals(3, server.requestCount)
    }
}
