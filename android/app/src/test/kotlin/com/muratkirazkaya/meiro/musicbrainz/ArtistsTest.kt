package com.muratkirazkaya.meiro.musicbrainz

import com.muratkirazkaya.meiro.data.Source
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(name: String) = LinkerTest.fixture(name)

/** A metadata client for the artist page's calls, recording what it was asked. */
private class FakeArtistMb(
    private val artists: List<MbArtist> = emptyList(),
    private val popularity: Map<String, Popularity>? = emptyMap(),
    private val searchError: Exception? = null,
    private val recordings: Map<String, List<MbRecording>> = emptyMap(),
    private val links: Map<String, List<MbLink>> = emptyMap(),
    private val similar: List<SimilarArtist> = emptyList(),
    private val lookup: MbArtist? = null,
    private val recordingsError: Exception? = null,
) : MetadataClient {
    val asked = mutableListOf<String>()
    override suspend fun searchRecordings(title: String, artist: String) = emptyList<RecordingMatch>()
    override suspend fun searchArtists(query: String): List<MbArtist> { asked += "search:$query"; searchError?.let { throw it }; return artists }
    override suspend fun artistPopularity(mbids: List<String>): Map<String, Popularity> { asked += "popularity:${mbids.joinToString(",")}"; return popularity ?: throw java.io.IOException("listenbrainz down") }
    override suspend fun topRecordings(artistMbid: String): List<MbRecording> { asked += "top:$artistMbid"; recordingsError?.let { throw it }; return recordings[artistMbid].orEmpty() }
    /** Busy answers left before the first link lookup answers (a lookup with no entry in [links] is always busy). */
    var busyLinks = 0
    override suspend fun recordingLinks(recordingMbid: String): List<MbLink> {
        asked += "links:$recordingMbid"
        if (busyLinks > 0) { busyLinks--; throw RateLimitedException(503, 1000) }
        return links[recordingMbid] ?: throw RateLimitedException(503, 0)
    }
    override suspend fun similarArtists(artistMbid: String): List<SimilarArtist> { asked += "similar:$artistMbid"; return similar }
    override suspend fun lookupArtist(artistMbid: String): MbArtist? { asked += "lookup:$artistMbid"; return lookup }
}

/** Ports of `artists_test.go` (the search's artists, the artist page) and `playableRecording`'s rules. */
class ArtistsTest {
    /** `TestSearchShowsArtistsSection`: most listened first, the popularity applied. */
    @Test fun searchPutsTheMostListenedFirstWithTheirPopularity() = runBlocking {
        val mb = FakeArtistMb(
            artists = listOf(MbArtist("a2", "Second Artist", "US"), MbArtist("a1", "Nova Artist", "JP", "the band")),
            popularity = mapOf("a1" to Popularity("a1", 9000, 5000), "a2" to Popularity("a2", 20, 10)),
        )
        val found = Artists.search(mb, " nova ")
        assertEquals(listOf("Nova Artist", "Second Artist"), found.map { it.name })
        assertEquals(5000L, found[0].listeners)
        assertEquals(listOf("search:nova", "popularity:a2,a1"), mb.asked)
    }

    /** A failure to get the popularity is not a failure to find the artists: MusicBrainz's own order stays. */
    @Test fun aPopularityFailureKeepsMusicBrainzsOrder() = runBlocking {
        val mb = FakeArtistMb(artists = listOf(MbArtist("b", "B"), MbArtist("a", "A")), popularity = null)
        assertEquals(listOf("B", "A"), Artists.search(mb, "x").map { it.name })
    }

    /** `TestArtistPageSurvivesAMusicBrainzFailure`: the search's error reaches the caller (who keeps the provider's rows). */
    @Test fun aSearchFailureIsTheCallersError() = runBlocking {
        val mb = FakeArtistMb(searchError = java.io.IOException("musicbrainz is unreachable"))
        val e = runCatching { Artists.search(mb, "nova") }.exceptionOrNull()
        assertEquals("musicbrainz is unreachable", e?.message)
        assertEquals(emptyList<MbArtist>(), Artists.search(mb, "  "))
    }

    /** `sortArtists` is stable: artists ListenBrainz knows nothing of keep their place among themselves. */
    @Test fun sortIsStable() {
        val sorted = Artists.sortArtists(listOf(MbArtist("1", "x"), MbArtist("2", "y", listeners = 3), MbArtist("3", "z")))
        assertEquals(listOf("2", "1", "3"), sorted.map { it.id })
    }

    /** `playableRecording`: free streaming first; YouTube and YouTube Music watch links play as YouTube Music, SoundCloud as SoundCloud. */
    @Test fun playableLinks() {
        val buy = MbLink("purchase for download", "https://www.youtube.com/watch?v=paid")
        val free = MbLink("free streaming", "https://music.youtube.com/watch?v=free&list=x")
        assertEquals(Artists.Playable(Source.YTM, "free", free), Artists.playable(listOf(buy, free)))
        assertEquals("abc", Artists.playable(listOf(MbLink("streaming", "https://youtu.be/abc")))?.key)
        val sc = MbLink("streaming", "https://soundcloud.com/a/b")
        assertEquals(Artists.Playable(Source.SOUNDCLOUD, "https://soundcloud.com/a/b", sc), Artists.playable(listOf(MbLink("free streaming", "https://open.spotify.com/track/1"), sc)))
        assertNull(Artists.playable(listOf(MbLink("free streaming", "https://www.deezer.com/track/1"), MbLink("free streaming", "https://notyoutube.com/watch?v=x"))))
        assertNull(Artists.youtubeWatchId("https://www.youtube.com/channel/UC1"))
        assertNull(Artists.youtubeWatchId("not a url"))
        assertTrue(Artists.isSoundCloudUrl("https://m.soundcloud.com/x")); assertFalse(Artists.isSoundCloudUrl("https://soundcloud.com.evil.example/x"))
    }

    /**
     * `TestArtistPageShowsRecordings`: the recordings show at once, a linked one becomes playable, an unlinked one stays
     * greyed; only the first [Artists.MB_LINK_LIMIT] recordings are looked up; the artist is looked up when the search did
     * not give it; a refused lookup leaves its row greyed.
     */
    @Test fun pageShowsRecordingsThenLightsThemUp() = runBlocking {
        val recs = (1..14).map { MbRecording("r$it", "Song $it", "Nova Artist", 200_000) }
        val links = (1..14).filter { it != 2 && it != 3 }.associate { "r$it" to listOf(MbLink("free streaming", "https://music.youtube.com/watch?v=v$it")) } +
            ("r2" to listOf(MbLink("free streaming", "https://open.spotify.com/track/2")))
        // r3 has no entry: its lookup is refused (503).
        val mb = FakeArtistMb(
            recordings = mapOf("a1" to recs), links = links,
            similar = listOf(SimilarArtist("s1", "Like Nova", 1.0)), lookup = MbArtist("a1", "Nova Artist", "JP", begin = "2001"),
        )
        val pages = Artists.load(mb, "a1", known = null).toList()
        assertTrue(pages.first().loading)
        val shown = pages.first { !it.loading }
        assertEquals(Artists.RECORDINGS_SHOWN, shown.rows.size)
        assertTrue(shown.rows.all { it.play == null })
        val last = pages.last()
        assertEquals("Nova Artist", last.artist?.name)
        assertEquals(listOf("s1"), last.similar.map { it.mbid })
        assertEquals((1..12).map { it !in setOf(2, 3) }, last.rows.map { it.play != null })
        assertEquals("v1", last.rows[0].play?.key)
        // r3 is asked three times in all (busy each time), the others once.
        assertEquals(listOf("top:a1", "lookup:a1", "similar:a1") + (1..12).flatMap { n -> List(if (n == 3) Artists.BUSY_TRIES else 1) { "links:r$n" } }, mb.asked)
    }

    /** A busy answer is asked again: two refusals, then the link, and the row plays. */
    @Test fun aBusyLinkIsAskedAgain() = runBlocking {
        val mb = FakeArtistMb(
            recordings = mapOf("a1" to listOf(MbRecording("r1", "Song"))),
            links = mapOf("r1" to listOf(MbLink("free streaming", "https://youtu.be/v1"))),
        ).also { it.busyLinks = 2 }
        val last = Artists.load(mb, "a1", MbArtist("a1", "Nova")).toList().last()
        assertEquals("v1", last.rows.single().play?.key)
        assertEquals(3, mb.asked.count { it == "links:r1" })
    }

    /** Known from the search, the artist is not looked up again. A failure to read the recordings is the page's error. */
    @Test fun knownArtistIsNotLookedUpAndARecordingsFailureIsTheError() = runBlocking {
        val mb = FakeArtistMb(recordings = mapOf("a1" to emptyList()))
        val last = Artists.load(mb, "a1", MbArtist("a1", "Nova")).toList().last()
        assertEquals(listOf("top:a1", "similar:a1"), mb.asked)
        assertEquals("Nova", last.artist?.name); assertTrue(last.rows.isEmpty()); assertEquals("", last.error)
        val failing = FakeArtistMb(recordingsError = java.io.IOException("listenbrainz: 500\nmore"))
        val err = Artists.load(failing, "a1", null).toList().last()
        assertEquals("listenbrainz: 500", err.error); assertFalse(err.loading)
    }

    @Test fun countryAndYears() {
        assertEquals("Fransa", Artists.countryName("FR"))
        assertEquals("Japonya", Artists.countryName("jp"))
        assertEquals("", Artists.countryName(""))
        assertEquals("1993–2021", Artists.years(MbArtist("x", "x", begin = "1993", end = "2021-02-22", ended = true)))
        assertEquals("2005–", Artists.years(MbArtist("x", "x", begin = "2005-03")))
        assertEquals("1997", Artists.years(MbArtist("x", "x", begin = "1997-12", ended = true)))
        assertEquals("", Artists.years(MbArtist("x", "x")))
    }

    /** The recorded answers: ListenBrainz's top recordings of Daft Punk and the links MusicBrainz has for the first 12. */
    @Test fun recordedTopRecordingsAndTheirLinks() {
        val top = MusicBrainzClient.parseTopRecordings(fixture("lb_top_recordings_daft_punk.json"))
        assertEquals(40, top.size)
        assertEquals(MbRecording("f1a6a40f-78f5-4918-968d-f64363bae94c", "Harder, Better, Faster, Stronger", "Daft Punk", 224293, 220546, 2536925), top[0])
        val play = top.take(12).map { r -> r.title to Artists.playable(MusicBrainzClient.parseLinks(fixture("mb_links_${r.id}.json")))?.let { "${it.source}:${it.key}" } }
        assertEquals(
            listOf(
                "Harder, Better, Faster, Stronger" to "ytm:JhulBGMA7G4", "Digital Love" to null, "Aerodynamic" to "ytm:52fNscjkST4",
                "Something About Us" to "ytm:Om4L8mlTiAA", "One More Time" to "ytm:fa5IWHDbftI", "Around the World" to "ytm:Jb6gcoR266U",
                "Derezzed" to null, "Technologic" to "ytm:D8K90hX4PrE", "Face to Face" to "ytm:qXI87eMP-bs", "Crescendolls" to "ytm:AuTRLEWSfTo",
                "Voyager" to "ytm:OWiVJMgms9E", "Robot Rock" to "ytm:HdeYwObD-j4",
            ),
            play,
        )
        // Pages about a recording (not a place to play it) are left out.
        assertTrue(MusicBrainzClient.parseLinks(fixture("mb_links_f1a6a40f-78f5-4918-968d-f64363bae94c.json")).all { it.type in setOf("free streaming", "streaming", "purchase for download") })
    }

    @Test fun recordedLookupSimilarAndFallback() {
        val a = MusicBrainzClient.parseArtist(org.json.JSONObject(fixture("mb_artist_lookup_daft_punk.json")))
        assertEquals(MbArtist("056e4f3e-d505-4dad-8ec1-d04f521cbb56", "Daft Punk", "FR", "French electronic duo", begin = "1993", end = "2021-02-22", ended = true), a)
        val fromSearch = MusicBrainzClient.parseArtists(fixture("mb_artist_daft_punk.json"))
        assertEquals("1993", fromSearch[0].begin); assertEquals("", fromSearch[1].country)
        val similar = MusicBrainzClient.parseSimilar(fixture("lb_similar_daft_punk.json"))
        assertEquals(100, similar.size)
        assertEquals("Nile Rodgers", similar[0].name); assertEquals(1.0, similar[0].similarity, 1e-9)
        assertTrue(similar.all { it.similarity in 0.0..1.0 })
        val fallback = MusicBrainzClient.parseArtistRecordings(fixture("mb_artist_recordings_daft_punk.json"))
        assertTrue(fallback.isNotEmpty() && fallback.all { it.id.length == 36 && it.artist.isEmpty() })
    }
}

/** The artist page's requests against a local server: paths, the desktop's User-Agent, the pacing, the fallback. */
class ArtistsClientTest {
    private val server = MockWebServer()

    @After fun stop() = server.shutdown()

    private fun client(limiter: Limiter = Limiter(gapMs = 0)): MusicBrainzClient {
        val base = server.url("/").toString().trimEnd('/')
        return MusicBrainzClient(mb = base, lb = base, labs = base, limiter = limiter)
    }

    @Test fun pageAgainstRecordedAnswers() = runBlocking {
        val top = MusicBrainzClient.parseTopRecordings(fixture("lb_top_recordings_daft_punk.json")).take(12).map { it.id }.toSet()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/1/popularity/top-recordings-for-artist/056e4f3e-d505-4dad-8ec1-d04f521cbb56" -> MockResponse().setBody(fixture("lb_top_recordings_daft_punk.json"))
                    path == "/ws/2/artist/056e4f3e-d505-4dad-8ec1-d04f521cbb56" -> MockResponse().setBody(fixture("mb_artist_lookup_daft_punk.json"))
                    path == "/similar-artists/json" -> MockResponse().setBody(fixture("lb_similar_daft_punk.json"))
                    path.startsWith("/ws/2/recording/") && path.removePrefix("/ws/2/recording/") in top ->
                        MockResponse().setBody(fixture("mb_links_${path.removePrefix("/ws/2/recording/")}.json"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val page = Artists.load(client(), "056e4f3e-d505-4dad-8ec1-d04f521cbb56", null).toList().last()
        assertEquals("Daft Punk", page.artist?.name)
        assertEquals(12, page.rows.size)
        assertEquals(10, page.rows.count { it.play != null })
        assertEquals(Artists.SIMILAR_SHOWN, page.similar.size)
        val reqs = (0 until server.requestCount).map { server.takeRequest() }
        assertTrue(reqs.all { it.getHeader("User-Agent") == MB_USER_AGENT })
        val links = reqs.filter { it.requestUrl!!.encodedPath.startsWith("/ws/2/recording/") }
        assertEquals(12, links.size)
        assertTrue(links.all { it.requestUrl!!.queryParameter("inc") == "url-rels" && it.requestUrl!!.queryParameter("fmt") == "json" })
        val sim = reqs.single { it.requestUrl!!.encodedPath == "/similar-artists/json" }.requestUrl!!
        assertEquals("056e4f3e-d505-4dad-8ec1-d04f521cbb56", sim.queryParameter("artist_mbids"))
        assertTrue(sim.queryParameter("algorithm")!!.startsWith("session_based_days_9000"))
    }

    /** No ListenBrainz answer: MusicBrainz's own recordings of the artist, through the paced queue. */
    @Test fun topRecordingsFallBackToMusicBrainz() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(fixture("mb_artist_recordings_daft_punk.json")))
        var paced = 0
        val limiter = Limiter(1000, { 0L }, { paced++ })
        val recs = client(limiter).topRecordings("056e4f3e-d505-4dad-8ec1-d04f521cbb56")
        assertTrue(recs.isNotEmpty())
        server.takeRequest()
        val mb = server.takeRequest().requestUrl!!
        assertEquals("/ws/2/recording", mb.encodedPath)
        assertEquals("056e4f3e-d505-4dad-8ec1-d04f521cbb56", mb.queryParameter("artist")); assertEquals("25", mb.queryParameter("limit"))
    }

    /** Links go through the 1 s queue: the second request waits for the gap after the first. */
    @Test fun linksArePaced() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"relations":[]}""")) }
        var now = 5_000L
        val slept = mutableListOf<Long>()
        val c = client(Limiter(1000, { now }, { slept += it; now += it }))
        c.recordingLinks("r1"); c.recordingLinks("r2")
        assertEquals(listOf(1000L), slept)
    }

    /** A lookup and a similar-artists answer of 404 are "nothing", not errors. */
    @Test fun notFoundIsNothing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client().lookupArtist("x"))
        assertEquals(emptyList<SimilarArtist>(), client().similarArtists("x"))
    }
}
