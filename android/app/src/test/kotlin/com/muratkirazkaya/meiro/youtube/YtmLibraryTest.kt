package com.muratkirazkaya.meiro.youtube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

// Every value here is made up: no real account, cookie, list or channel.
private const val FAKE_COOKIE = "PREF=hl=en; SAPISID=fakeSapisid0123; SID=fakeSid"

private fun fixture(name: String) = YtmLibraryTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

/**
 * A fake YouTube Music answering the M6g requests from the hand-written fixtures (`fixtures/ytm_library_*`,
 * `ytm_playlist_liked*`, `ytm_new_releases`, made by `build/check/m6g/gen_fixtures.py` in YouTube's renderer shapes).
 */
private class FakeLibraryYtm(var status: Int = 200) {
    val requests = CopyOnWriteArrayList<Request>()
    val bodies = CopyOnWriteArrayList<JSONObject>()
    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request(); requests += req
        val body = JSONObject(Buffer().also { req.body!!.writeTo(it) }.readUtf8()); bodies += body
        val name = when (body.optString("browseId").ifEmpty { body.optString("continuation") }) {
            "FEmusic_library_landing" -> "ytm_library_landing.json"
            "LIBCONT1" -> "ytm_library_continuation.json"
            "VLLM" -> "ytm_playlist_liked.json"
            "LMCONT1" -> "ytm_playlist_liked_continuation1.json"
            "LMCONT2" -> "ytm_playlist_liked_continuation2.json"
            "FEmusic_new_releases_albums" -> "ytm_new_releases.json"
            else -> null
        }
        val (code, text) = if (status != 200) status to "{}" else if (name == null) 404 to "{}" else 200 to fixture(name)
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x").body(text.toResponseBody("application/json".toMediaType())).build()
    }).build()
    fun client(auth: YtmCookieAuth) = YtmClient(http = http, auth = auth, epochSeconds = { 1_700_000_000 })
}

/** M6g: the account's library, its liked songs and the new releases (`browse.go`, `playlist.go`, `navigation.go`). */
class YtmLibraryTest {
    private val auth = YtmCookieAuth.create(FAKE_COOKIE)

    @Test fun trackCountReadsYouTubesWords() {
        assertEquals(1204, YtmLibraryParse.trackCount("1,204 songs • 80+ hours"))
        assertEquals(52, YtmLibraryParse.trackCount("Playlist • Test User • 52 songs"))
        assertEquals(1, YtmLibraryParse.trackCount("1 song"))
        assertEquals(12, YtmLibraryParse.trackCount("12 tracks"))
        assertNull(YtmLibraryParse.trackCount("Auto playlist"))
        assertNull(YtmLibraryParse.trackCount("Album • 2013"))
    }

    @Test fun libraryFoldsItsContinuationAndKeepsEachListOnce() = runBlocking {
        val fake = FakeLibraryYtm()
        val page = fake.client(auth).library()
        // The landing page and its one continuation page, nothing else.
        assertEquals(listOf("FEmusic_library_landing", "LIBCONT1"), fake.bodies.map { it.optString("browseId").ifEmpty { it.optString("continuation") } })
        assertEquals("", page.continuation)
        assertTrue(page.sections.all { it.continuationToken.isEmpty() })
        val lib = YtmLibraryParse.library(page, likedCount = 1204)
        assertEquals(YtmListEntry("LM", "Liked Music", "https://lh3.googleusercontent.com/fake-VLLM=w226", 1204), lib.liked)
        // Playlists in the library's order, each once (the landing lists one twice), the liked songs apart; an artist is no list.
        assertEquals(listOf("PLfakeRoad" to 52, "PLfakeNight" to 1250, "SE" to null), lib.playlists.map { it.id to it.count })
        assertEquals(listOf("Hareketli Yol Şarkıları", "Gece Kuşu", "Episodes for Later"), lib.playlists.map { it.title })
        assertEquals(listOf("MPREb_fakeSim" to "Hidra", "MPREb_fakeDisc" to "Daft Punk"), lib.albums.map { it.id to it.artist })
        assertTrue(lib.albums.all { it.album })
    }

    @Test fun libraryRequestsCarryTheSessionAndNeedIt() = runBlocking {
        val fake = FakeLibraryYtm()
        fake.client(auth).library()
        for (r in fake.requests) {
            assertEquals("https://music.youtube.com/youtubei/v1/browse", r.url.toString().substringBefore("?"))
            assertEquals(FAKE_COOKIE, r.header("Cookie"))
            // sha1("1700000000 fakeSapisid0123 https://music.youtube.com"), as in YtmCookieAuthTest.
            assertEquals("SAPISIDHASH 1700000000_60edec8374f7572b6f6515530db4b889e52dc2a0", r.header("Authorization"))
            assertEquals("0", r.header("X-Goog-Authuser"))
            assertNull(r.header("X-Goog-Visitor-Id"))
        }
        for (b in fake.bodies) {
            val ctx = b.getJSONObject("context")
            assertTrue(ctx.has("user"))
            assertFalse(ctx.getJSONObject("client").has("visitorData"))
        }
        // Anonymous, the library is not asked for at all.
        val anon = FakeLibraryYtm()
        try { YtmClient(http = anon.http).library(); fail("an anonymous client read the library") } catch (e: YtmException) { assertTrue(e.message!!.contains("cookie")) }
        assertTrue(anon.requests.isEmpty())
    }

    @Test fun likedSongsFollowTheirOwnShelfToTheEnd() = runBlocking {
        val fake = FakeLibraryYtm()
        val page = fake.client(auth).playlist("LM")
        // VLLM, then the shelf's continuation, then the token inside the appended items; never the suggestions.
        assertEquals(listOf("VLLM", "LMCONT1", "LMCONT2"), fake.bodies.map { it.optString("browseId").ifEmpty { it.optString("continuation") } })
        assertEquals("1,204 songs • 80+ hours", page.secondSubtitle)
        assertEquals(1204, YtmLibraryParse.trackCount(page.secondSubtitle))
        val t = page.tracks()
        assertEquals(listOf("vidfake0001", "vidfake0002", "vidfake0003", "vidfake0004", "vidfake0002"), t.map { it.videoId })
        assertEquals(listOf("Fikret Kızılok", "Kavinsky", "Mr.Kitty", "Nilüfer", "Kavinsky"), t.map { it.artists })
        assertEquals("4:22", t[0].duration)
    }

    @Test fun firstPageOnlyAsksOnce() = runBlocking {
        val fake = FakeLibraryYtm()
        val page = fake.client(auth).playlist("VLLM", all = false)
        assertEquals(1, fake.requests.size)
        assertEquals(2, page.tracks().size)
    }

    @Test fun playlistRestStopsAtSuggestionsAndRepeatedTokens() = runBlocking {
        val suggestions = BrowsePage("", "", sections = listOf(Renderers.Section("More", "musicCarouselShelfRenderer", listOf(MusicItem(videoId = "x", title = "x")), "")))
        val rest = YtmLibraryParse.playlistRest("A") { suggestions }
        assertTrue(rest.isEmpty())
        var asked = 0
        val loop = YtmLibraryParse.playlistRest("A") { asked++; BrowsePage("", "", items = listOf(MusicItem(videoId = "v$asked", title = "t")), continuation = "A") }
        assertEquals(1, asked)
        assertEquals(1, loop.size)
    }

    @Test fun drainGivesUpOnARunawayLibrary() = runBlocking {
        var n = 0
        try {
            YtmLibraryParse.drain(BrowsePage("", "", continuation = "t0")) { n++; BrowsePage("", "", continuation = "t$n") }
            fail("a library without end was read to the end")
        } catch (e: YtmException) { assertTrue(e.message!!.contains("1000")) }
        assertEquals(YtmLibraryParse.MAX_PAGES, n)
    }

    @Test fun newReleasesAreItsAlbums() = runBlocking {
        val fake = FakeLibraryYtm()
        val r = YtmLibraryParse.releases(fake.client(auth).newReleases())
        assertEquals(listOf("MPREb_fakeRAM", "MPREb_fakeOut", "MPREb_fakeTime"), r.map { it.browseId })
        assertEquals(listOf("Daft Punk", "Kavinsky", "Mr.Kitty"), r.map { YtmLibraryParse.albumArtist(it) })
        assertEquals(listOf("Album", "Album", "Single"), r.map { YtmLibraryParse.albumKind(it) })
    }

    @Test fun unauthorizedNamesNoSecret() = runBlocking {
        val fake = FakeLibraryYtm(status = 401)
        try { fake.client(auth).library(); fail() } catch (e: YtmUnauthorized) {
            assertFalse(e.message!!.contains("fakeSapisid"))
            assertFalse(e.message!!.contains("SID="))
        }
    }
}

/** M6g: the feed follows the session: signed out nothing, a sign-in reads, a sign-out drops at once. */
class YtmLibraryFeedTest {
    private fun session() = YtmSession(FAKE_COOKIE, YtmAccountDetails("Test User", "test-user@example.com", "UCfake", ""))

    @Test fun signedOutAsksNothingAndHoldsNothing() = runBlocking {
        val fake = FakeLibraryYtm()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = MutableStateFlow<YtmSession?>(null)
        val feed = YtmLibraryFeed(s, { fake.client(it) }, scope)
        feed.start()
        feed.refresh(force = true)
        Thread.sleep(200)
        assertNull(feed.state.value)
        assertTrue(fake.requests.isEmpty())
        scope.cancel()
    }

    @Test fun signInReadsLibraryLikedCountAndReleasesThenSignOutDrops() = runBlocking {
        val fake = FakeLibraryYtm()
        val logs = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = MutableStateFlow<YtmSession?>(null)
        val feed = YtmLibraryFeed(s, { fake.client(it) }, scope, log = { logs += it })
        feed.start()
        s.value = session()
        val got = withTimeout(5_000) { feed.state.first { it?.library != null } }!!
        assertEquals(1204, got.library!!.liked!!.count)
        assertEquals(3, got.library!!.playlists.size)
        assertEquals(3, got.releases.size)
        // The liked count needs only the liked page's first page.
        assertEquals(1, fake.bodies.count { it.optString("browseId") == "VLLM" })
        assertEquals(0, fake.bodies.count { it.optString("continuation") == "LMCONT1" })
        // A fresh read is not asked again; a forced one is.
        val n = fake.requests.size
        feed.refresh()
        Thread.sleep(200)
        assertEquals(n, fake.requests.size)
        // The playlist page reads every track, once, and is kept.
        assertEquals(5, feed.playlist("LM").tracks().size)
        val m = fake.requests.size
        feed.playlist("LM")
        assertEquals(m, fake.requests.size)
        s.value = null
        withTimeout(5_000) { while (feed.state.value != null) Thread.sleep(10) }
        try { feed.playlist("LM"); fail("a playlist was read signed out") } catch (e: YtmException) { }
        // Counts and outcomes only in the log: no cookie, name or title.
        for (l in logs) for (secret in listOf("fakeSapisid", "SID=", "Test User", "test-user@", "Hareketli", "Gece", "Liked Music")) assertFalse(l, l.contains(secret))
        assertTrue(logs.any { it.contains("playlists=3") })
        scope.cancel()
    }

    @Test fun aFailedReadKeepsWhatAnEarlierOneGave() = runBlocking {
        val fake = FakeLibraryYtm()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = MutableStateFlow<YtmSession?>(null)
        val feed = YtmLibraryFeed(s, { fake.client(it) }, scope)
        feed.start()
        s.value = session()
        withTimeout(5_000) { feed.state.first { it?.library != null } }
        fake.status = 500
        feed.load(s.value!!)
        assertEquals(3, feed.state.value!!.library!!.playlists.size)
        assertEquals(3, feed.state.value!!.releases.size)
        scope.cancel()
    }
}
