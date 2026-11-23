package com.muratkirazkaya.meiro.lyrics

import com.muratkirazkaya.meiro.youtube.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One sung line and when it starts (`lrclib.Line`). */
data class LyricLine(val atMs: Long, val text: String)

/** What LRCLIB knows of a track (`lrclib.Lyrics`): plain or synced can be empty; a track that is only music has neither. */
data class LrcLyrics(val plain: String, val synced: List<LyricLine>, val instrumental: Boolean = false) {
    fun empty() = plain.isEmpty() && synced.isEmpty()
}

/** The track to look for (`lrclib.Query`); [durationMs] is optional and tells versions of a song apart. */
data class LrcQuery(val title: String, val artist: String, val album: String = "", val durationMs: Long = 0)

/** The pure parts of `internal/lrclib/lrclib.go`: the LRC format, the short title, the line being sung. */
object Lrc {
    /** How far back the first stamp of a line must jump, from the one before it, to start another copy of the song. */
    private const val RESTART_MS = 10_000L

    /**
     * `ParseLRC`: lines such as "[01:23.45] text", which may carry several stamps for a repeated line; lines without a
     * stamp and tags such as "[ar: name]" are skipped. A file that holds the song twice (a romanised copy after the
     * original, starting again from the top) is split where its time jumps back, and the copy with the most non-ASCII
     * letters is kept, ties to the first. The result is in time order.
     */
    fun parse(text: String): List<LyricLine> {
        val blocks = ArrayList<List<LyricLine>>()
        var current = ArrayList<LyricLine>()
        var previous = 0L
        for (line in text.split('\n')) {
            var raw = line.trim()
            val stamps = ArrayList<Long>()
            while (raw.startsWith("[")) {
                val end = raw.indexOf(']')
                if (end < 0) break
                val at = stamp(raw.substring(1, end)) ?: break
                stamps += at
                raw = raw.substring(end + 1)
            }
            if (stamps.isEmpty()) continue
            val first = stamps.min()
            if (current.isNotEmpty() && first < previous - RESTART_MS) { blocks += current; current = ArrayList() }
            previous = first
            val content = raw.trim()
            for (at in stamps) current += LyricLine(at, content)
        }
        if (current.isNotEmpty()) blocks += current
        var best: List<LyricLine> = emptyList()
        var bestScore = -1
        for (b in blocks) {
            val score = b.sumOf { l -> l.text.count { it.code > 127 && it.isLetter() } }
            if (score > bestScore) { best = b; bestScore = score }
        }
        return best.sortedBy { it.atMs } // stable
    }

    /** `parseStamp`: "mm:ss" or "mm:ss.xx". */
    private fun stamp(s: String): Long? {
        val colon = s.indexOf(':')
        if (colon < 0) return null
        val m = s.substring(0, colon).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val rest = s.substring(colon + 1)
        if (rest.isEmpty() || !rest.all { it.isDigit() || it == '.' }) return null
        val secs = rest.toDoubleOrNull()?.takeIf { it >= 0 && it < 60 } ?: return null
        return m * 60_000L + (secs * 1000).toLong()
    }

    /** `ShortTitle`: the title cut at the first " - " (after its start), without trailing "(feat …)", "(ft. …)", "(with …)" groups. */
    fun shortTitle(title: String): String {
        var t = title
        val dash = t.indexOf(" - ")
        if (dash > 0) t = t.substring(0, dash)
        while (true) {
            val open = maxOf(t.lastIndexOf('('), t.lastIndexOf('（'))
            val close = maxOf(t.lastIndexOf(')'), t.lastIndexOf('）'))
            if (open < 0 || close < open) break
            val inner = t.substring(open + 1, close).lowercase()
            if (!inner.startsWith("feat") && !inner.startsWith("ft.") && !inner.startsWith("with ")) break
            t = (t.substring(0, open) + t.substring(close + 1)).trim()
        }
        return t.trim()
    }

    /** `IndexAt`: the line being sung at [positionMs], the last one that has started; -1 before the first. */
    fun indexAt(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0; var hi = lines.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (lines[mid].atMs > positionMs) hi = mid else lo = mid + 1 }
        return lo - 1
    }

    /** One `/api/get` answer, or one hit of `/api/search`. */
    fun fromJson(o: JSONObject) = LrcLyrics(
        plain = o.optString("plainLyrics").takeUnless { o.isNull("plainLyrics") }.orEmpty().trim(),
        synced = parse(o.optString("syncedLyrics").takeUnless { o.isNull("syncedLyrics") }.orEmpty()),
        instrumental = o.optBoolean("instrumental"),
    )
}

/** LRCLIB answered something other than 200 or 404, or its answer could not be read. */
class LrclibException(message: String, val retry: Boolean) : IOException(message)

/**
 * The LRCLIB client (`lrclib.Client.Get`): the exact match first, then a search by title and artist, then by the short
 * title. Each request is tried 3 times, waiting `attempt * retryDelayMs` after a network error, 5xx or 429; 404 is no
 * lyrics; 15 s timeout. A failing step does not end the lookup; the first error is thrown only when nothing was found.
 */
class LrclibClient(
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val base: String = "https://lrclib.net",
    private val retryDelayMs: Long = 700,
    private val log: (String) -> Unit = {},
) {
    suspend fun get(q: LrcQuery): LrcLyrics? {
        if (q.title.isEmpty()) return null
        var firstErr: Exception? = null
        val get = url("/api/get").addQueryParameter("track_name", q.title).addQueryParameter("artist_name", q.artist)
        if (q.album.isNotEmpty()) get.addQueryParameter("album_name", q.album)
        if (q.durationMs > 0) get.addQueryParameter("duration", ((q.durationMs + 500) / 1000).toString())
        try {
            fetch(get.build())?.let { body ->
                val l = Lrc.fromJson(JSONObject(body))
                if (!l.empty() || l.instrumental) return l
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { firstErr = e }
        val titles = listOf(q.title) + listOfNotNull(Lrc.shortTitle(q.title).takeIf { it != q.title })
        for (title in titles) {
            val many = try {
                fetch(url("/api/search").addQueryParameter("track_name", title).addQueryParameter("artist_name", q.artist).build())
                    ?.let { JSONArray(it) } ?: JSONArray()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { if (firstErr == null) firstErr = e; continue }
            var plain: LrcLyrics? = null
            for (i in 0 until many.length()) {
                val l = Lrc.fromJson(many.optJSONObject(i) ?: continue)
                if (l.synced.isNotEmpty()) return l // the best there is: it has both forms
                if (plain == null && !l.empty()) plain = l
            }
            if (plain != null) return plain
        }
        firstErr?.let { throw it }
        return null
    }

    private fun url(path: String): HttpUrl.Builder = (base + path).toHttpUrl().newBuilder()

    /** The body of a 200, null for a 404, with the retries. */
    private suspend fun fetch(url: HttpUrl): String? {
        var attempt = 1
        while (true) {
            try {
                return fetchOnce(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val retry = (e as? LrclibException)?.retry ?: (e is IOException)
                if (!retry || attempt == 3) throw e
            }
            delay(attempt * retryDelayMs)
            attempt++
        }
    }

    private suspend fun fetchOnce(url: HttpUrl): String? {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val resp = try { http.newCall(req).await() } catch (e: IOException) { log("lrclib GET $url failed: $e"); throw e }
        resp.use { r ->
            log("lrclib GET $url -> ${r.code}")
            when {
                r.code == 404 -> return null
                r.code >= 500 || r.code == 429 -> throw LrclibException("lrclib: HTTP ${r.code}", retry = true)
                r.code != 200 -> throw LrclibException("lrclib: HTTP ${r.code}", retry = false)
            }
            val body = r.body?.string().orEmpty()
            try { JSONObject(body) } catch (_: JSONException) {
                try { JSONArray(body) } catch (_: JSONException) { throw LrclibException("lrclib: unreadable answer", retry = false) }
            }
            return body
        }
    }

    companion object {
        /** LRCLIB asks its clients to name themselves. */
        const val USER_AGENT = "Meiro (https://github.com/elianiva/meiro)"
    }
}
