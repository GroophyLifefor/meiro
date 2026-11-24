package com.muratkirazkaya.meiro.youtube

import com.muratkirazkaya.meiro.sync.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * A signed-in YouTube Music account (`account.go` `session`): the cookie it signs in with, whom it signs in as and the
 * channel it acts as ("" = the account's own). [toString] never shows the cookie.
 */
class YtmSession(val cookie: String, val details: YtmAccountDetails, val channel: String = "") {
    /** `session.name`. */
    val name: String get() = details.name

    override fun toString(): String = "YtmSession(name=${details.name}, channel=${channel.ifEmpty { "-" }}, cookie=<redacted>)"

    /** What the encrypted store keeps: the cookie and the last details, so the row reads right before the first check. */
    fun toStored(): String = JSONObject().put("cookie", cookie).put("channel", channel)
        .put("name", details.name).put("email", details.email).put("channelId", details.channelId).put("thumbnail", details.thumbnail).toString()

    companion object {
        fun fromStored(text: String): YtmSession? = runCatching {
            val o = JSONObject(text)
            val cookie = o.optString("cookie")
            if (cookie.isBlank()) return null
            YtmSession(
                cookie, YtmAccountDetails(o.optString("name"), o.optString("email"), o.optString("channelId"), o.optString("thumbnail")),
                o.optString("channel"),
            )
        }.getOrNull()
    }
}

/**
 * `errSessionGone`: YouTube answered that the cookie signs in to nothing (401/403, or an account with neither a name nor
 * a channel id). The session that cookie held is over.
 */
class YtmSessionGone : YtmException("YouTube did not recognise the cookie as a signed-in session; it may have expired")

/**
 * The YouTube Music account in use and the work that signs in, restores, checks and signs out (`account.go`
 * `accountState`, `credentials`, `signin.go` `trySignIn`). The cookie lives only in [store] (the app's
 * EncryptedSharedPreferences) and in memory; it is never logged: [log] gets names and outcomes only.
 *
 * Every sign-in page, its dismissal and every sign-out advances [attempt]; a write checks under [mu] that the attempt
 * it was made for is still current, so a sign-in that finishes after the page was left, or a check that finishes
 * after a sign-out, can never put back an account the user has left.
 */
class YtmAccount(
    private val store: TokenStore,
    private val newClient: (YtmCookieAuth) -> YtmClient,
    private val log: (String) -> Unit = {},
) {
    private val _session = MutableStateFlow<YtmSession?>(null)
    val session: StateFlow<YtmSession?> = _session
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Messages for the snack: the session expired. */
    val notices: SharedFlow<String> = _notices

    private val mu = Mutex()
    private val attempt = AtomicLong()

    /** `openSignIn`: the sign-in page opened; its attempt number. */
    fun beginSignIn(): Long = attempt.incrementAndGet()

    /** `dismissSignIn`: the page was left; whatever its attempt still does is dropped. */
    fun dismissSignIn(of: Long) { attempt.compareAndSet(of, of + 1) }

    /**
     * `openSession`: asks YouTube whom [cookie] signs in as [channel]; a channel that is gone falls back to the account's
     * own. Throws [YtmSessionGone] when YouTube does not take the session.
     */
    suspend fun openSession(cookie: String, channel: String = ""): YtmSession {
        suspend fun open(ch: String): YtmSession {
            val auth = try { YtmCookieAuth.create(cookie, onBehalfOfUser = ch) } catch (e: IllegalArgumentException) { throw YtmSessionGone() }
            val details = try { newClient(auth).accountDetails() } catch (e: YtmUnauthorized) { throw YtmSessionGone() }
            // YouTube answers a retired session with signed-out content rather than an error.
            if (details.name.isEmpty() && details.channelId.isEmpty()) throw YtmSessionGone()
            return YtmSession(cookie, details, ch)
        }
        return try { open(channel) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (channel.isNotEmpty() && e !is YtmSessionGone) open("") else throw e
        }
    }

    /**
     * `trySignIn` + `saveForDialog`: the cookie read from the sign-in page is asked about and, when YouTube takes it and
     * the page's [of] attempt is still current, kept and put into use. Throws when YouTube does not take it or the page
     * was left meanwhile (`CancellationException`).
     */
    suspend fun completeSignIn(cookie: String, of: Long): YtmSession {
        val opened = openSession(cookie)
        mu.withLock {
            if (attempt.get() != of) throw CancellationException("the sign-in page was left")
            store.save(opened.toStored())
            _session.value = opened
        }
        // Outcomes only: the account's name, email and ids stay out of the log like the cookie.
        log("ytm: signed in (account named: ${opened.name.isNotEmpty()}, channel id: ${opened.details.channelId.isNotEmpty()})")
        return opened
    }

    /**
     * `restoreAccount`: the session an earlier run kept is in use at once (its last details), and YouTube is asked about
     * it right after ([check]).
     */
    suspend fun restore() {
        val stored = mu.withLock { store.load()?.let { YtmSession.fromStored(it) } } ?: return
        if (_session.value == null) _session.value = stored
        log("ytm: restored the saved session")
        check()
    }

    /**
     * `checkSession` (every minute on the desktop): asks YouTube whether the session is still taken. A taken session
     * refreshes its details; one YouTube no longer takes ends with the desktop's notice ([expire]); any other failure
     * says nothing about the session and the next check tries again.
     */
    suspend fun check() {
        val current = _session.value ?: return
        val from = attempt.get()
        val result = try { Result.success(openSession(current.cookie, current.channel)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        mu.withLock {
            if (attempt.get() != from || _session.value !== current) return
            result.onSuccess { s ->
                if (s.details != current.details || s.channel != current.channel) { store.save(s.toStored()); _session.value = s }
            }.onFailure { e ->
                if (e is YtmSessionGone) expireLocked() else log("ytm: session check failed (${e.javaClass.simpleName}); trying again later")
            }
        }
    }

    /** `signOut`: forgets the account and removes the saved cookie. */
    suspend fun signOut() {
        attempt.incrementAndGet()
        mu.withLock {
            _session.value = null
            store.delete()
        }
        log("ytm: signed out")
    }

    /** `expireSession`: the session is over; the phone also drops its cookie (nothing can renew it). */
    private fun expireLocked() {
        attempt.incrementAndGet()
        _session.value = null
        store.delete()
        log("ytm: the session expired")
        _notices.tryEmit(EXPIRED_NOTICE)
    }

    companion object {
        /** `sessionExpiredNotice`, in Turkish. */
        const val EXPIRED_NOTICE = "YouTube Music oturumun bitti. Yeniden giriş yap."
        /** `sessionCheckEvery`. */
        const val CHECK_EVERY_MS = 60_000L
    }
}

/**
 * The in-app sign-in page (`internal/cookies/window.go`, PORTING.md 2.2): Google's sign-in that continues to YouTube
 * Music, in a WebView whose cookies are cleared before it opens and as soon as the session is read, so the session is
 * read once from a page that is never used again (YouTube rotates the cookies of a session a browser keeps open).
 */
object YtmSignIn {
    /** Google's sign-in page that continues to YouTube Music (where YouTube Music's own "Sign in" button leads). */
    const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
    /** The URL whose cookies the requests carry (`musicURL`). */
    const val COOKIE_URL = "https://music.youtube.com"
    /** `WindowOptions.Poll` and `Settle`. */
    const val POLL_MS = 1_000L
    const val SETTLE_MS = 3_000L

    /** `hasSession`: a SAPISID on youtube.com means the page is signed in. */
    fun hasSession(header: String?): Boolean = header != null && YtmCookieAuth.cookieValue(header, "SAPISID").isNotEmpty()

    /**
     * `waitSignIn`: polls the page's `Cookie:` header every [poll] ms until a signed-in one has shown for [settle] ms (so
     * the page can set the rest of its cookies), then returns that header. Leaving the page cancels it. [now] and
     * [sleep] are the clock; tests pass a virtual one.
     */
    suspend fun waitForSession(
        read: () -> String?, now: () -> Long, sleep: suspend (Long) -> Unit, poll: Long = POLL_MS, settle: Long = SETTLE_MS,
    ): String {
        var signedAt = -1L
        while (true) {
            val header = read()
            if (hasSession(header)) {
                if (signedAt < 0) signedAt = now()
                if (now() - signedAt >= settle) return header!!
            }
            sleep(poll)
        }
    }

    /** The host the address strip shows for [url] (the mock's `.url`), "" for none. */
    fun host(url: String?): String = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }.orEmpty()
}
