package com.muratkirazkaya.meiro.youtube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Every value here is made up: no real account, cookie, track or channel.
private const val COOKIE = "PREF=hl=en; SAPISID=fakeSapisid0123; SID=fakeSid"

private fun fixture(name: String) = YtmLikesTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

/**
 * A fake YouTube Music for the M6h like requests, answering from hand-written fixtures in the shapes the desktop's own
 * tests use (`harness_test.go` `likeResponse`, `rejectedResponse`, `nextResponse`): `like/like` and `like/removelike`
 * (refused with a 200 and an "error" object while [refuse]), and `next` with the panel's like buttons. [hold] keeps a
 * write unanswered until it is counted down.
 */
private class FakeLikeYtm {
    val requests = CopyOnWriteArrayList<Request>()
    val bodies = CopyOnWriteArrayList<JSONObject>()
    @Volatile var refuse = false
    @Volatile var hold: CountDownLatch? = null
    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request(); requests += req
        bodies += JSONObject(Buffer().also { req.body!!.writeTo(it) }.readUtf8())
        val path = req.url.encodedPath
        val text = when {
            path.endsWith("/like/like") || path.endsWith("/like/removelike") -> {
                hold?.await(5, TimeUnit.SECONDS)
                fixture(if (refuse) "ytm_like_rejected.json" else "ytm_like_ok.json")
            }
            path.endsWith("/next") -> fixture("ytm_next_like.json")
            else -> "{}"
        }
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody("application/json".toMediaType())).build()
    }).build()
    fun client(auth: YtmCookieAuth) = YtmClient(http = http, auth = auth, epochSeconds = { 1_700_000_000 })
    fun writes() = requests.filter { it.url.encodedPath.contains("/like/") }
}

/** M6h: liking a track on YouTube Music (`rating.go`, `like_test.go`, `accountwrites.go`). */
class YtmLikesTest {
    private fun session(cookie: String = COOKIE) = YtmSession(cookie, YtmAccountDetails("Test User", "test-user@example.com", "UCfake", ""))

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) { if (System.currentTimeMillis() > end) fail("timed out: $what"); Thread.sleep(10) }
    }

    @Test fun statusNamesAreYouTubesOwn() {
        assertEquals(LikeStatus.Liked, YtmRating.parse("LIKE"))
        assertEquals(LikeStatus.Indifferent, YtmRating.parse("INDIFFERENT"))
        assertEquals(LikeStatus.Disliked, YtmRating.parse("DISLIKE"))
        assertEquals(LikeStatus.Unknown, YtmRating.parse("SOMETHING_NEW"))
        assertEquals(LikeStatus.Unknown, YtmRating.parse(null))
    }

    @Test fun nextGivesTheStatusOfThatVideoOnly() {
        val next = fixture("ytm_next_like.json")
        assertEquals(LikeStatus.Liked, YtmRating.statusIn(next, "vidTrack01"))
        assertEquals(LikeStatus.Indifferent, YtmRating.statusIn(next, "vidOther01"))
        assertEquals(LikeStatus.Unknown, YtmRating.statusIn(next, "vidMissing1"))
    }

    @Test fun aWriteRefusedInItsBodyThrowsWithYouTubesReason() {
        YtmRating.checkWrite("like/like", fixture("ytm_like_ok.json"))
        try { YtmRating.checkWrite("like/like", fixture("ytm_like_rejected.json")); fail("a refused write passed") } catch (e: YtmException) {
            assertEquals("youtube: like/like was refused: Request contains an invalid argument.", e.message)
        }
        try { YtmRating.checkWrite("like/removelike", """{"error":{"status":"PERMISSION_DENIED"}}"""); fail() } catch (e: YtmException) {
            assertEquals("youtube: like/removelike was refused: PERMISSION_DENIED", e.message)
        }
    }

    @Test fun rateTrackWritesTheVideoWithTheSessionAndNeedsOne() = runBlocking {
        val fake = FakeLikeYtm()
        val client = fake.client(YtmCookieAuth.create(COOKIE))
        client.rateTrack(" vidTrack01 ", true)
        client.rateTrack("vidTrack01", false)
        val (like, unlike) = fake.writes()
        assertEquals("/youtubei/v1/like/like", like.url.encodedPath)
        assertEquals("/youtubei/v1/like/removelike", unlike.url.encodedPath)
        assertEquals("vidTrack01", fake.bodies[0].getJSONObject("target").getString("videoId"))
        assertEquals("vidTrack01", fake.bodies[1].getJSONObject("target").getString("videoId"))
        // The session's own headers, as every signed-in request (M6f): cookie, SAPISIDHASH for the origin, the account.
        assertEquals(COOKIE, like.header("Cookie"))
        assertTrue(like.header("Authorization")!!.startsWith("SAPISIDHASH 1700000000_"))
        assertEquals("0", like.header("X-Goog-Authuser"))
        assertTrue(fake.bodies[0].getJSONObject("context").has("user"))
        try { YtmClient(http = fake.http).rateTrack("vidTrack01", true); fail("an anonymous client liked") } catch (e: YtmException) { }
        try { client.rateTrack("  ", true); fail("no video was written") } catch (e: IllegalArgumentException) { }
        assertEquals(2, fake.writes().size)
    }

    @Test fun signedOutHasNoLikeAndAsksNothing() = runBlocking {
        val fake = FakeLikeYtm()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val likes = YtmLikes(MutableStateFlow(null), { fake.client(it) }, scope)
        likes.start()
        assertFalse(likes.canLike("vidTrack01"))
        assertFalse(likes.toggle("vidTrack01"))
        likes.learn("vidTrack01")
        likes.learnLiked(listOf("vidTrack01"))
        Thread.sleep(200)
        assertTrue(fake.requests.isEmpty())
        assertTrue(likes.state.value.isEmpty())
        scope.cancel()
    }

    @Test fun theHeartFillsAtOnceIsWrittenBehindAndGoesBackEmpty() = runBlocking {
        val fake = FakeLikeYtm()
        val logs = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val likes = YtmLikes(MutableStateFlow(session()), { fake.client(it) }, scope, log = { logs += it })
        likes.start()
        assertTrue(likes.canLike("vidTrack01"))
        assertFalse(likes.canLike(null))
        fake.hold = CountDownLatch(1)
        assertTrue(likes.toggle("vidTrack01"))
        assertTrue("the heart did not fill at once", likes.liked("vidTrack01"))
        // A press while the write is out writes nothing more.
        assertFalse(likes.toggle("vidTrack01"))
        fake.hold!!.countDown()
        waitFor("the like answered") { logs.any { it.contains("liked") } }
        assertEquals(1, fake.writes().size)
        assertTrue(fake.writes()[0].url.encodedPath.endsWith("/like/like"))
        // Unliking writes removelike and empties the heart.
        fake.hold = null
        assertTrue(likes.toggle("vidTrack01"))
        assertFalse(likes.liked("vidTrack01"))
        waitFor("the unlike answered") { logs.any { it.contains("unliked") } }
        assertTrue(fake.writes()[1].url.encodedPath.endsWith("/like/removelike"))
        assertEquals(LikeStatus.Indifferent, likes.state.value["vidTrack01"])
        for (l in logs) for (secret in listOf("fakeSapisid", "SID=", "Test User", "test-user@", "vidTrack01", "Album Track")) assertFalse(l, l.contains(secret))
        scope.cancel()
    }

    @Test fun aRejectedLikeIsTakenBackAndTold() = runBlocking {
        val fake = FakeLikeYtm().apply { refuse = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val likes = YtmLikes(MutableStateFlow(session()), { fake.client(it) }, scope)
        likes.start()
        val told = CopyOnWriteArrayList<Pair<Boolean, String>>()
        assertTrue(likes.toggle("vidTrack01") { like, msg -> told += like to msg })
        waitFor("the refusal") { told.isNotEmpty() }
        assertFalse("the heart stayed filled after a rejection", likes.liked("vidTrack01"))
        assertEquals(LikeStatus.Unknown, likes.state.value["vidTrack01"])
        assertEquals(true, told[0].first)
        assertTrue(told[0].second, told[0].second.contains("refused"))
        // The next press may write again.
        fake.refuse = false
        assertTrue(likes.toggle("vidTrack01"))
        waitFor("the second like") { fake.writes().size == 2 }
        scope.cancel()
    }

    @Test fun theStatusIsLearnedOncePerSessionAndForgottenAtSignOut() = runBlocking {
        val fake = FakeLikeYtm()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val s = MutableStateFlow<YtmSession?>(session())
        val likes = YtmLikes(s, { fake.client(it) }, scope)
        likes.start()
        likes.learn("vidTrack01")
        waitFor("the status") { likes.liked("vidTrack01") }
        likes.learn("vidTrack01")
        Thread.sleep(150)
        assertEquals(1, fake.requests.count { it.url.encodedPath.endsWith("/next") })
        assertEquals(COOKIE, fake.requests.first { it.url.encodedPath.endsWith("/next") }.header("Cookie"))
        // The liked songs' tracks are liked; a status already said is kept.
        likes.learnLiked(listOf("vidLiked01", "vidLiked02"))
        assertTrue(likes.liked("vidLiked01") && likes.liked("vidLiked02"))
        s.value = null
        waitFor("sign-out forgets") { likes.state.value.isEmpty() }
        assertFalse(likes.liked("vidTrack01"))
        // Another account asks again.
        s.value = session("PREF=hl=en; SAPISID=otherSapisid99; SID=otherSid")
        Thread.sleep(100)
        likes.learn("vidTrack01")
        waitFor("asked again") { fake.requests.count { it.url.encodedPath.endsWith("/next") } == 2 }
        scope.cancel()
    }
}
