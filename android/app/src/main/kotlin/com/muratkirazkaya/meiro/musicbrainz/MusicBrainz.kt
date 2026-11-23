package com.muratkirazkaya.meiro.musicbrainz

import com.muratkirazkaya.meiro.youtube.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Port of `musicbrainz/musicbrainz.go`, `ratelimit.go` and `cache.go`: the MusicBrainz and ListenBrainz calls the link
 * sheet and auto-linking need (recording search, artist search, artist popularity), one MusicBrainz request at a time
 * and at least a second after the end of the one before, with the desktop's User-Agent and its cache lifetimes.
 */

/** `UserAgent` (musicbrainz.go:31): the desktop's, sent as it is. */
const val MB_USER_AGENT = "Playlist Manager SideProject/1.0.0 (muratkirazkaya0@gmail.com)"

/** MusicBrainz or ListenBrainz refused a request for coming too fast (HTTP 429 or 503), `ErrRateLimited`. */
class RateLimitedException(val status: Int, val retryAfterMs: Long) : IOException("musicbrainz: answered $status")

/** A MusicBrainz answer other than 2xx, 404, 429 and 503. */
class MusicBrainzException(message: String) : IOException(message)

/** `RecordingMatch`: a recording a search found; [artist] is the credit as MusicBrainz joins it, [artistId] its first. */
data class RecordingMatch(
    val id: String, val title: String, val artist: String = "", val artistId: String = "", val lengthMs: Int = 0,
    val score: Int = 0,
    /** The year of the recording's first release ("" when MusicBrainz gives none); shown only, as the mock's "· 2019". */
    val year: String = "",
)

/** `Artist`: [listeners] is filled from ListenBrainz when asked for. */
data class MbArtist(
    val id: String, val name: String, val country: String = "", val disambiguation: String = "", val score: Int = 0,
    val listeners: Long = 0,
)

/** `Popularity`: what ListenBrainz knows of one artist. */
data class Popularity(val mbid: String, val listens: Long, val listeners: Long)

/** What linking asks of MusicBrainz (`metadataClient`), so the rules can be tested without a network. */
interface MetadataClient {
    suspend fun searchRecordings(title: String, artist: String): List<RecordingMatch>
    suspend fun searchArtists(query: String): List<MbArtist>
    suspend fun artistPopularity(mbids: List<String>): Map<String, Popularity>
}

/**
 * `limiter`: runs requests one at a time; each waits until [gapMs] has passed since the end of the one before, and
 * after a 429/503 until the pause the service asked for (Retry-After), else 10 s doubling to 5 min. A success clears
 * the count of refusals.
 */
class Limiter(
    private val gapMs: Long = 1000,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var last = 0L
    private var until = 0L
    private var strikes = 0

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        if (last != 0L) {
            val gap = gapMs - (now() - last)
            if (gap > 0) sleep(gap)
        }
        val wait = until - now()
        if (wait > 0) sleep(wait)
        try {
            val r = block()
            last = now(); strikes = 0
            r
        } catch (e: RateLimitedException) {
            last = now(); strikes++
            val pause = if (e.retryAfterMs > 0) e.retryAfterMs else minOf(FIRST_PAUSE_MS shl (strikes - 1).coerceAtMost(20), MAX_PAUSE_MS)
            until = last + minOf(pause, MAX_PAUSE_MS)
            throw e
        } catch (e: Throwable) {
            last = now()
            throw e
        }
    }

    companion object {
        const val FIRST_PAUSE_MS = 10_000L
        const val MAX_PAUSE_MS = 5 * 60_000L
    }
}

/**
 * `cache`: one file per answer, named by the first 16 bytes of sha256(key) in hex, holding `{storedAt, key, body}`.
 * An answer older than its kind's lifetime is not used. [dir] null keeps nothing. The phone keeps the raw answer.
 */
class MbCache(private val dir: File?, private val now: () -> Long = System::currentTimeMillis) {
    fun get(key: String, ttlMs: Long): String? {
        val f = file(key) ?: return null
        return try {
            val o = JSONObject(f.readText())
            if (now() - o.optLong("storedAt") >= ttlMs) null else o.optString("body").takeIf { o.has("body") }
        } catch (_: Exception) { null }
    }

    fun put(key: String, body: String) {
        val f = file(key) ?: return
        try {
            f.parentFile?.mkdirs()
            f.writeText(JSONObject().put("storedAt", now()).put("key", key).put("body", body).toString())
        } catch (_: Exception) { /* the cache is an optimisation */ }
    }

    private fun file(key: String): File? {
        val d = dir ?: return null
        val sum = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        return File(d, "$sum.json")
    }
}

/** `Client`: MusicBrainz paced through [limiter], ListenBrainz read directly; answers kept in [cache]. */
class MusicBrainzClient(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
    private val mb: String = "https://musicbrainz.org",
    private val lb: String = "https://api.listenbrainz.org",
    private val cache: MbCache = MbCache(null),
    private val limiter: Limiter = Limiter(),
    private val log: (String) -> Unit = {},
) : MetadataClient {

    override suspend fun searchRecordings(title: String, artist: String): List<RecordingMatch> {
        val t = title.trim(); val a = artist.trim()
        if (t.isEmpty()) return emptyList()
        var query = "recording:" + quoteTerm(t)
        if (a.isNotEmpty()) query += " AND artist:" + quoteTerm(a)
        val body = cached("mb:recording-search:$query", RECORDING_SEARCH_TTL_MS) { mbGet("recording", query, 10) }
        return parseRecordings(body)
    }

    override suspend fun searchArtists(query: String): List<MbArtist> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val body = cached("mb:artist-search:$q", ARTIST_SEARCH_TTL_MS) { mbGet("artist", q, 25) }
        return parseArtists(body)
    }

    override suspend fun artistPopularity(mbids: List<String>): Map<String, Popularity> {
        val ids = mbids.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (ids.isEmpty()) return emptyMap()
        val body = cached("lb:artist-popularity:" + ids.joinToString(","), POPULARITY_TTL_MS) {
            val payload = JSONObject().put("artist_mbids", JSONArray(ids)).toString()
            send(Request.Builder().url("$lb/1/popularity/artist").post(payload.toRequestBody("application/json".toMediaType())), paced = false)
        }
        return parsePopularity(body).associateBy { it.mbid }
    }

    /** `fetchCached`: a fresh kept answer, else the request; only successes are kept. */
    private suspend fun cached(key: String, ttlMs: Long, fetch: suspend () -> String): String {
        cache.get(key, ttlMs)?.let { log("musicbrainz: cache hit $key"); return it }
        val body = fetch()
        cache.put(key, body)
        return body
    }

    private suspend fun mbGet(resource: String, query: String, limit: Int): String {
        // url.Values.Encode sorts the keys: fmt, limit, query.
        val url = "$mb/ws/2/$resource".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "json").addQueryParameter("limit", limit.toString()).addQueryParameter("query", query).build()
        return send(Request.Builder().url(url).get(), paced = true)
    }

    private suspend fun send(b: Request.Builder, paced: Boolean): String {
        val req = b.header("User-Agent", MB_USER_AGENT).header("Accept", "application/json").build()
        val call: suspend () -> String = {
            val t0 = System.nanoTime()
            http.newCall(req).await().use { r ->
                val text = r.body?.string().orEmpty()
                log("musicbrainz: ${req.method} ${req.url.encodedPath} -> ${r.code} in ${(System.nanoTime() - t0) / 1_000_000} ms")
                when {
                    r.code == 429 || r.code == 503 -> throw RateLimitedException(r.code, (r.header("Retry-After")?.trim()?.toLongOrNull() ?: 0L).coerceAtLeast(0) * 1000)
                    !r.isSuccessful -> throw MusicBrainzException("musicbrainz: ${req.url.encodedPath} answered ${r.code}")
                    else -> text
                }
            }
        }
        return if (paced) limiter.run(call) else call()
    }

    companion object {
        const val ARTIST_SEARCH_TTL_MS = 30L * 24 * 3600 * 1000
        const val RECORDING_SEARCH_TTL_MS = 24L * 3600 * 1000
        const val POPULARITY_TTL_MS = 7L * 24 * 3600 * 1000

        /** `quoteTerm`: `\` -> `\\`, `"` -> `\"`, in quotes. */
        fun quoteTerm(term: String) = "\"" + term.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** The recording search's answer as `RecordingMatch`es (credit names plus join phrases, trimmed). */
        fun parseRecordings(body: String): List<RecordingMatch> {
            val arr = JSONObject(body).optJSONArray("recordings") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val r = arr.optJSONObject(i) ?: return@mapNotNull null
                val credit = StringBuilder()
                var artistId = ""
                val ac = r.optJSONArray("artist-credit") ?: JSONArray()
                for (j in 0 until ac.length()) {
                    val part = ac.optJSONObject(j) ?: continue
                    val artist = part.optJSONObject("artist")
                    credit.append(part.optString("name").ifEmpty { artist?.optString("name").orEmpty() })
                    credit.append(part.optString("joinphrase"))
                    if (j == 0) artistId = artist?.optString("id").orEmpty()
                }
                RecordingMatch(
                    id = r.optString("id"), title = r.optString("title"), artist = credit.toString().trim(), artistId = artistId,
                    lengthMs = r.optInt("length", 0), score = r.optInt("score", 0),
                    year = r.optString("first-release-date").take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(),
                )
            }
        }

        fun parseArtists(body: String): List<MbArtist> {
            val arr = JSONObject(body).optJSONArray("artists") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                MbArtist(a.optString("id"), a.optString("name"), a.optString("country"), a.optString("disambiguation"), a.optInt("score", 0))
            }
        }

        fun parsePopularity(body: String): List<Popularity> {
            val t = body.trim()
            if (!t.startsWith("[")) return emptyList()
            val arr = JSONArray(t)
            return (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                Popularity(p.optString("artist_mbid"), p.optLong("total_listen_count", 0), p.optLong("total_user_count", 0))
            }
        }
    }
}
