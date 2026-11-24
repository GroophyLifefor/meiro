package com.muratkirazkaya.meiro.youtube

import com.muratkirazkaya.meiro.sync.InMemoryTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Every value here is made up: no real account, cookie or channel.
private const val FAKE_SAPISID = "fakeSapisid0123"
private const val FAKE_COOKIE = "PREF=hl=en; SAPISID=$FAKE_SAPISID; __Secure-3PAPISID=fake3p; SID=fakeSid; LOGIN_INFO=fakeLogin"

/** A synthetic `account/account_menu` answer in the shape YouTube gives (desktop `harness_test.go` `signedInAccountMenu`). */
private fun accountMenu(name: String, email: String = "test-user@example.com", brandSelected: Boolean = false) = """
{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{
 "header":{"activeAccountHeaderRenderer":{"accountName":{"runs":[{"text":"$name"}]},"email":{"simpleText":"$email"},
   "accountPhoto":{"thumbnails":[{"url":"https://example.com/small.jpg"},{"url":"https://example.com/big.jpg"}]}}},
 "sections":[{"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[
   {"accountItemRenderer":{"accountName":{"simpleText":"$name"},"isSelected":${!brandSelected},"hasChannel":true}},
   {"accountItemRenderer":{"accountName":{"simpleText":"Test Brand"},"isSelected":$brandSelected,"hasChannel":true,
     "serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[{"accountSigninToken":{"signinUrl":"/signin?authuser=1"}},{"pageIdToken":{"pageId":"UC-fake-brand"}}]}}}}
 ]}}]}}]}}}}]}
""".trimIndent()

/** What YouTube answers a retired session: signed-out content and HTTP 200. */
private const val SIGNED_OUT_MENU = """{"actions":[{"openPopupAction":{"popup":{"multiPageMenuRenderer":{"sections":[]}}}}]}"""

/** An OkHttp client answered by [answer] (no network), recording each request. */
private class FakeYtm(var answer: (Request) -> Pair<Int, String>) {
    val requests = CopyOnWriteArrayList<Request>()
    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val req = chain.request(); requests += req
        val (code, body) = answer(req)
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody("application/json".toMediaType())).build()
    }).build()
    fun client(auth: YtmCookieAuth) = YtmClient(http = http, auth = auth)
}

private fun bodyOf(r: Request): JSONObject = JSONObject(Buffer().also { r.body!!.writeTo(it) }.readUtf8())

/** M6f: the cookie, its SAPISIDHASH and where it may go (`youtube/cookie.go`, `client.go` `setIdentity`). */
class YtmCookieAuthTest {
    @Test fun sapisidHashMatchesTheDefinition() {
        // sha1("1700000000 fakeSapisid0123 https://music.youtube.com"), computed outside the app (python hashlib).
        val a = YtmCookieAuth.create(FAKE_COOKIE)
        assertEquals("SAPISIDHASH 1700000000_60edec8374f7572b6f6515530db4b889e52dc2a0", a.authorization(1_700_000_000, "https://music.youtube.com"))
        assertEquals("SAPISIDHASH 1700000000_6c6e3282419135a6e3ebfc17fb568b4e1d90be9c", a.authorization(1_700_000_000, "https://www.youtube.com"))
    }

    @Test fun cookieValueTrimsQuotesAndNeedsTheExactName() {
        assertEquals("abc", YtmCookieAuth.cookieValue("A=1; SAPISID=\"abc\" ; B=2", "SAPISID"))
        assertEquals("", YtmCookieAuth.cookieValue("__Secure-3PAPISID=x; XSAPISID=y", "SAPISID"))
        assertEquals("v=w", YtmCookieAuth.cookieValue("PREF=v=w", "PREF"))
    }

    @Test fun createRefusesWhatIsNotASessionWithoutEchoingIt() {
        for (bad in listOf("", "   ", "SID=fakeSid", "SAPISID=x\r\nX: y")) {
            try { YtmCookieAuth.create(bad); fail("accepted $bad") } catch (e: IllegalArgumentException) {
                assertFalse(e.message!!.contains("fakeSid"))
            }
        }
    }

    @Test fun neverPrintsTheCookie() {
        val a = YtmCookieAuth.create(FAKE_COOKIE)
        assertEquals("YtmCookieAuth(<redacted>)", a.toString())
        val s = YtmSession(FAKE_COOKIE, YtmAccountDetails("Test Kullanıcı"))
        assertFalse(s.toString().contains(FAKE_SAPISID))
        assertFalse("$s".contains("fakeSid"))
    }

    @Test fun onlyHttpsYouTubeUrls() {
        fun ok(u: String) = YtmCookieAuth.isYouTubeUrl(u.toHttpUrl())
        assertTrue(ok("https://music.youtube.com/youtubei/v1/browse"))
        assertTrue(ok("https://youtube.com/x"))
        assertFalse(ok("http://music.youtube.com/youtubei/v1/browse"))
        assertFalse(ok("https://music.youtube.com.evil.example/x"))
        assertFalse(ok("https://notyoutube.com/x"))
        assertFalse(ok("https://user:pw@music.youtube.com/x"))
    }
}

/** M6f: a signed-in request carries the session and nothing anonymous; an anonymous one carries no session. */
class YtmSignedInRequestTest {
    @Test fun signedInRequestCarriesCookieHashAuthuserAndNoVisitor() = runBlocking {
        val fake = FakeYtm { 200 to accountMenu("Test Kullanıcı") }
        val client = YtmClient(http = fake.http, auth = YtmCookieAuth.create(FAKE_COOKIE), epochSeconds = { 1_700_000_000 })
        val d = client.accountDetails()
        assertEquals(YtmAccountDetails("Test Kullanıcı", "test-user@example.com", "", "https://example.com/big.jpg"), d)
        val r = fake.requests.single()
        assertEquals("https://music.youtube.com/youtubei/v1/account/account_menu", r.url.toString())
        assertEquals(FAKE_COOKIE, r.header("Cookie"))
        assertEquals("SAPISIDHASH 1700000000_60edec8374f7572b6f6515530db4b889e52dc2a0", r.header("Authorization"))
        assertEquals("0", r.header("X-Goog-Authuser"))
        assertNull(r.header("X-Goog-PageId"))
        assertNull(r.header("X-Goog-Visitor-Id"))
        val ctx = bodyOf(r).getJSONObject("context")
        assertTrue(ctx.has("user"))
        assertFalse(ctx.getJSONObject("user").has("onBehalfOfUser"))
        assertFalse(ctx.getJSONObject("client").has("visitorData"))
    }

    @Test fun brandChannelAddsPageIdAndOnBehalfOfUser() = runBlocking {
        val fake = FakeYtm { 200 to accountMenu("Test Kullanıcı", brandSelected = true) }
        val d = YtmClient(http = fake.http, auth = YtmCookieAuth.create(FAKE_COOKIE, onBehalfOfUser = "UC-fake-brand")).accountDetails()
        assertEquals("UC-fake-brand", d.channelId)
        val r = fake.requests.single()
        assertEquals("UC-fake-brand", r.header("X-Goog-PageId"))
        assertEquals("UC-fake-brand", bodyOf(r).getJSONObject("context").getJSONObject("user").getString("onBehalfOfUser"))
    }

    @Test fun anonymousRequestsCarryNoSessionAndKeepTheVisitorId() = runBlocking {
        val fake = FakeYtm { 200 to """{"responseContext":{"visitorData":"fakeVisitor"},"contents":{}}""" }
        val client = YtmClient(http = fake.http)
        runCatching { client.search("x") }
        runCatching { client.search("y") }
        for (r in fake.requests) {
            assertNull(r.header("Cookie")); assertNull(r.header("Authorization")); assertNull(r.header("X-Goog-Authuser"))
            assertFalse(bodyOf(r).getJSONObject("context").has("user"))
        }
        assertEquals("fakeVisitor", fake.requests[1].header("X-Goog-Visitor-Id"))
    }

    @Test fun theSessionNeverGoesToAnotherHostOrOverHttp() = runBlocking {
        for (base in listOf("http://music.youtube.com/youtubei/v1/", "https://evil.example/youtubei/v1/")) {
            val fake = FakeYtm { 200 to "{}" }
            try { YtmClient(http = fake.http, base = base, auth = YtmCookieAuth.create(FAKE_COOKIE)).accountDetails(); fail("sent to $base") } catch (e: YtmException) {
                assertTrue(e.message!!.contains("refusing"))
            }
            assertTrue("no request may leave for $base", fake.requests.isEmpty())
        }
    }

    @Test fun rejectedSessionIsUnauthorizedAndTheErrorHasNoSecret() = runBlocking {
        for (code in listOf(401, 403)) {
            val fake = FakeYtm { code to "SAPISID=$FAKE_SAPISID" }
            try { YtmClient(http = fake.http, auth = YtmCookieAuth.create(FAKE_COOKIE)).accountDetails(); fail() } catch (e: YtmUnauthorized) {
                assertFalse(e.message!!.contains(FAKE_SAPISID))
            }
        }
    }

    /** The same request over a real socket (MockWebServer), the URL rewritten after the guard ran: headers survive the wire. */
    @Test fun headersOnTheWire() = runBlocking {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(accountMenu("Test Kullanıcı"))); start() }
        try {
            val http = OkHttpClient.Builder().addInterceptor { ch ->
                val u = server.url(ch.request().url.encodedPath)
                ch.proceed(ch.request().newBuilder().url(u).build())
            }.build()
            YtmClient(http = http, auth = YtmCookieAuth.create(FAKE_COOKIE), epochSeconds = { 1_700_000_000 }).accountDetails()
            val r = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/youtubei/v1/account/account_menu", r.path)
            assertEquals(FAKE_COOKIE, r.getHeader("Cookie"))
            assertTrue(r.getHeader("Authorization")!!.startsWith("SAPISIDHASH 1700000000_"))
        } finally { server.shutdown() }
    }
}

/** M6f: the account parser (`accounts.go`), on synthetic answers. */
class YtmAccountParseTest {
    @Test fun headerAndSelectedChannel() {
        val d = YtmAccountParse.details(accountMenu("Test Kullanıcı", brandSelected = true))
        assertEquals("Test Kullanıcı", d.name)
        assertEquals("test-user@example.com", d.email)
        assertEquals("UC-fake-brand", d.channelId)
        assertEquals("https://example.com/big.jpg", d.thumbnail)
    }

    /** Desktop `TestAccountChannelsReadBrandIDsFromIdentityTokens`. */
    @Test fun brandIdsFromIdentityTokens() {
        val response = """{"accountSectionListRenderer":{"contents":[{"accountItemSectionRenderer":{"contents":[
            {"accountItemRenderer":{"accountName":{"simpleText":"Main channel"},"isSelected":true,"hasChannel":true}},
            {"accountItemRenderer":{"accountName":{"simpleText":"Brand channel"},"hasChannel":true,"serviceEndpoint":{"selectActiveIdentityEndpoint":{"supportedTokens":[
                {"accountSigninToken":{"signinUrl":"/signin?authuser=1"}},
                {"pageIdToken":{"pageId":"UC-brand"}}
            ]}}}}
        ]}}]}}"""
        val ch = YtmAccountParse.channels(OrderedJson.parse(response))
        assertEquals(2, ch.size)
        assertTrue(ch[0].name == "Main channel" && ch[0].selected && ch[0].channelId == "")
        assertTrue(ch[1].name == "Brand channel" && !ch[1].selected && ch[1].channelId == "UC-brand")
    }

    @Test fun signedOutContentHasNoAccount() {
        val d = YtmAccountParse.details(SIGNED_OUT_MENU)
        assertEquals("", d.name); assertEquals("", d.channelId)
    }
}

/** M6f: signing in, restoring, checking and signing out (`account.go`, `signin.go`), with fake answers and a fake store. */
class YtmAccountTest {
    private val logs = CopyOnWriteArrayList<String>()
    private fun account(store: InMemoryTokenStore, fake: FakeYtm) = YtmAccount(store, { fake.client(it) }, log = { logs += it })
    private fun assertLogsClean() {
        for (l in logs) {
            assertFalse("log line holds the cookie: $l", l.contains(FAKE_SAPISID) || l.contains("fakeSid"))
            assertFalse("log line holds the name: $l", l.contains("Test Kullanıcı") || l.contains("example.com"))
        }
    }

    @Test fun signInKeepsTheSessionInTheStore() = runBlocking {
        val store = InMemoryTokenStore()
        val acc = account(store, FakeYtm { 200 to accountMenu("Test Kullanıcı") })
        val a = acc.beginSignIn()
        val s = acc.completeSignIn(FAKE_COOKIE, a)
        assertEquals("Test Kullanıcı", s.name)
        assertEquals(s, acc.session.value)
        val stored = YtmSession.fromStored(store.load()!!)!!
        assertEquals(FAKE_COOKIE, stored.cookie)
        assertEquals("Test Kullanıcı", stored.details.name)
        assertLogsClean()
    }

    @Test fun aLeftPageKeepsNothing() = runBlocking {
        val store = InMemoryTokenStore()
        val acc = account(store, FakeYtm { 200 to accountMenu("Test Kullanıcı") })
        val a = acc.beginSignIn()
        acc.dismissSignIn(a)
        try { acc.completeSignIn(FAKE_COOKIE, a); fail() } catch (e: CancellationException) { }
        assertNull(store.load()); assertNull(acc.session.value)
    }

    @Test fun aSessionYouTubeDoesNotTakeIsNotKept() = runBlocking {
        for (answer in listOf(200 to SIGNED_OUT_MENU, 401 to "{}", 403 to "{}")) {
            val store = InMemoryTokenStore()
            val acc = account(store, FakeYtm { answer })
            try { acc.completeSignIn(FAKE_COOKIE, acc.beginSignIn()); fail() } catch (e: YtmSessionGone) { }
            assertNull(store.load()); assertNull(acc.session.value)
        }
        // No SAPISID at all: not a session either, and no request is made.
        val fake = FakeYtm { 200 to accountMenu("x") }
        try { account(InMemoryTokenStore(), fake).completeSignIn("SID=fakeSid", 1); fail() } catch (e: YtmSessionGone) { }
        assertTrue(fake.requests.isEmpty())
    }

    @Test fun restoreShowsTheSavedSessionAndChecksIt() = runBlocking {
        val store = InMemoryTokenStore(YtmSession(FAKE_COOKIE, YtmAccountDetails("Eski Ad")).toStored())
        val acc = account(store, FakeYtm { 200 to accountMenu("Test Kullanıcı") })
        acc.restore()
        assertEquals("Test Kullanıcı", acc.session.value!!.name)
        assertEquals("Test Kullanıcı", YtmSession.fromStored(store.load()!!)!!.name)
        assertLogsClean()
    }

    @Test fun restoreOfflineKeepsTheSession() = runBlocking {
        val saved = YtmSession(FAKE_COOKIE, YtmAccountDetails("Test Kullanıcı")).toStored()
        val store = InMemoryTokenStore(saved)
        val acc = account(store, FakeYtm { throw IOException("no network") })
        acc.restore()
        assertEquals("Test Kullanıcı", acc.session.value!!.name)
        assertEquals(saved, store.load())
        assertLogsClean()
    }

    @Test fun anExpiredSessionEndsWithTheNoticeAndLeavesNoCookie() = runBlocking {
        val store = InMemoryTokenStore(YtmSession(FAKE_COOKIE, YtmAccountDetails("Test Kullanıcı")).toStored())
        val fake = FakeYtm { 200 to accountMenu("Test Kullanıcı") }
        val acc = account(store, fake)
        acc.restore()
        val notice = async(Dispatchers.Default) { withTimeout(5_000) { acc.notices.first() } }
        fake.answer = { 200 to SIGNED_OUT_MENU }
        // The notice collector must be subscribed before the check emits.
        while (acc.notices.let { (it as kotlinx.coroutines.flow.MutableSharedFlow<String>).subscriptionCount.value } == 0) kotlinx.coroutines.yield()
        acc.check()
        assertEquals(YtmAccount.EXPIRED_NOTICE, notice.await())
        assertNull(acc.session.value); assertNull(store.load())
    }

    @Test fun aFailedCheckSaysNothing() = runBlocking {
        val store = InMemoryTokenStore()
        val fake = FakeYtm { 200 to accountMenu("Test Kullanıcı") }
        val acc = account(store, fake)
        acc.completeSignIn(FAKE_COOKIE, acc.beginSignIn())
        fake.answer = { 500 to "{}" }
        acc.check()
        assertEquals("Test Kullanıcı", acc.session.value!!.name)
        assertTrue(store.load() != null)
    }

    @Test fun signOutRemovesTheCookieAndACheckOnItsWayCannotBringItBack() = runBlocking {
        val store = InMemoryTokenStore()
        val inCheck = CountDownLatch(1); val release = CountDownLatch(1)
        var block = false
        val fake = FakeYtm {
            if (block) { inCheck.countDown(); release.await(5, TimeUnit.SECONDS) }
            200 to accountMenu("Test Kullanıcı")
        }
        val acc = account(store, fake)
        acc.completeSignIn(FAKE_COOKIE, acc.beginSignIn())
        block = true
        val check = async(Dispatchers.IO) { acc.check() }
        assertTrue(inCheck.await(5, TimeUnit.SECONDS))
        acc.signOut()
        release.countDown()
        check.await()
        assertNull(acc.session.value); assertNull(store.load())
        assertLogsClean()
    }

    @Test fun aGoneChannelFallsBackToTheAccountsOwn() = runBlocking {
        val fake = FakeYtm { r -> if (r.header("X-Goog-PageId") != null) 500 to "{}" else 200 to accountMenu("Test Kullanıcı") }
        val s = account(InMemoryTokenStore(), fake).openSession(FAKE_COOKIE, "UC-gone")
        assertEquals("", s.channel)
        assertEquals(2, fake.requests.size)
    }

    @Test fun storedFormRoundTrips() {
        val s = YtmSession(FAKE_COOKIE, YtmAccountDetails("Test Kullanıcı", "test-user@example.com", "UC-x", "https://example.com/a.jpg"), "UC-x")
        val back = YtmSession.fromStored(s.toStored())!!
        assertEquals(s.cookie, back.cookie); assertEquals(s.details, back.details); assertEquals("UC-x", back.channel)
        assertNull(YtmSession.fromStored("not json"))
        assertNull(YtmSession.fromStored("""{"cookie":""}"""))
    }
}

/** M6f: waiting for the page's session (`waitSignIn`) on a virtual clock: no real waits, so it cannot be timing-flaky. */
class YtmSignInWaitTest {
    @Test fun returnsTheHeaderOnceTheSessionHasStayedForTheSettleTime() = runBlocking {
        var t = 0L
        val reads = mutableListOf<Long>()
        // Signed out for 4 s, then a SAPISID appears; the page keeps adding cookies while it settles.
        val header = YtmSignIn.waitForSession(
            read = {
                reads += t
                when {
                    t < 4_000 -> "PREF=x"
                    t < 6_000 -> "PREF=x; SAPISID=$FAKE_SAPISID"
                    else -> FAKE_COOKIE
                }
            },
            now = { t }, sleep = { t += it },
        )
        assertEquals(FAKE_COOKIE, header)
        // Polled every second; the session showed at 4 s and was taken 3 s later.
        assertEquals(listOf(0L, 1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 7_000), reads)
    }

    @Test fun noCookiesKeepsWaiting() = runBlocking {
        var t = 0L
        var n = 0
        val r = runCatching {
            withTimeout(5_000) {
                YtmSignIn.waitForSession(read = { n++; if (n > 50) throw IllegalStateException("stop") else null }, now = { t }, sleep = { t += it })
            }
        }
        assertTrue(r.exceptionOrNull() is IllegalStateException)
        assertEquals(51, n)
    }

    @Test fun hostOfTheAddressStrip() {
        assertEquals("accounts.google.com", YtmSignIn.host(YtmSignIn.SIGN_IN_URL))
        assertEquals("music.youtube.com", YtmSignIn.host("https://music.youtube.com/"))
        assertEquals("", YtmSignIn.host(null))
        assertTrue(YtmSignIn.hasSession(FAKE_COOKIE)); assertFalse(YtmSignIn.hasSession("SID=x")); assertFalse(YtmSignIn.hasSession(null))
    }
}
