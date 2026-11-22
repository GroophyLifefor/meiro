package com.muratkirazkaya.meiro.youtube

import com.muratkirazkaya.meiro.CHROME_UA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A category a search can be narrowed to, as YouTube Music offers it for the query (`media.SearchFilter`). */
data class SearchFilter(val label: String, val params: String)

/** `media.SearchResult`: the one list a search shows, its lead card, its filters and the next page's token. */
data class SearchResult(
    val items: List<MusicItem>,
    val top: MusicItem? = null,
    val filters: List<SearchFilter> = emptyList(),
    val continuationToken: String = "",
)

data class SearchSuggestions(val queries: List<String>, val items: List<MusicItem>)

class YtmException(message: String) : IOException(message)

/** Reading search responses (`youtube/search.go`); pure, so the recorded fixtures test it on the JVM. */
object YtmSearch {
    fun parseSearch(text: String): SearchResult {
        val root = OrderedJson.parse(text)
        val parts = Renderers.browseParts(root, true)
        return SearchResult(parts.items, parts.top, filters(root), Renderers.continuationToken(root))
    }

    fun parseSuggestions(text: String): SearchSuggestions {
        val root = OrderedJson.parse(text)
        return SearchSuggestions(suggestionQueries(root), Renderers.browseParts(root, false).items)
    }

    /** `searchFilters`: the chips in YouTube Music's order; the one that clears them has no params. */
    fun filters(root: Any?): List<SearchFilter> {
        val cloud = Renderers.findRenderer(root, "chipCloudRenderer") ?: return emptyList()
        return cloud.arr("chips").orEmpty().mapNotNull { v ->
            val r = (v as? JObj)?.obj("chipCloudChipRenderer") ?: return@mapNotNull null
            SearchFilter(Renderers.rendererText(r["text"]), r.obj("navigationEndpoint")?.obj("searchEndpoint")?.str("params").orEmpty())
        }
    }

    private fun suggestionQueries(root: Any?): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(v: Any?) {
            when (v) {
                is List<*> -> v.forEach { walk(it) }
                is JObj -> {
                    v.obj("searchSuggestionRenderer")?.let { Renderers.rendererText(it["suggestion"]).takeIf { t -> t.isNotEmpty() }?.let(out::add) }
                    v.members.forEach { walk(it.second) }
                }
            }
        }
        walk(root)
        return out.toList()
    }
}

/**
 * The anonymous YouTube Music InnerTube client (`youtube/client.go`, PORTING.md 2.1): WEB_REMIX, `hl=en`, `gl=US`, no
 * cookie. The visitor id is learned from the first response and sent from then on, kept only in memory.
 */
class YtmClient(
    private val http: OkHttpClient = defaultHttp(),
    private val base: String = "https://music.youtube.com/youtubei/v1/",
) {
    @Volatile private var visitorData: String = ""

    suspend fun search(query: String, params: String = ""): SearchResult {
        require(query.isNotBlank()) { "youtube: search query is required" }
        val payload = JSONObject().put("query", query)
        if (params.isNotEmpty()) payload.put("params", params)
        return YtmSearch.parseSearch(execute("search", payload))
    }

    suspend fun continueSearch(token: String): SearchResult {
        require(token.isNotBlank()) { "youtube: search continuation token is required" }
        return YtmSearch.parseSearch(execute("search", JSONObject().put("continuation", token)))
    }

    suspend fun suggestions(input: String): SearchSuggestions {
        require(input.isNotBlank()) { "youtube: suggestion input is required" }
        return YtmSearch.parseSuggestions(execute("music/get_search_suggestions", JSONObject().put("input", input)))
    }

    /** `GetAlbum` (`browse.go:196`): an album page, `MPR...` or a privately owned release. */
    suspend fun album(id: String): BrowsePage {
        require(isAlbumId(id)) { "youtube: invalid album ID \"$id\"" }
        return YtmBrowse.parse(execute("browse", JSONObject().put("browseId", id)))
    }

    /** `GetArtist` (`browse.go:189`): an artist page, `UC...` or a privately owned artist. */
    suspend fun artist(id: String): BrowsePage {
        require(id.startsWith("UC") || id.startsWith("FEmusic_library_privately_owned_artist")) { "youtube: invalid artist ID \"$id\"" }
        return YtmBrowse.parse(execute("browse", JSONObject().put("browseId", id)))
    }

    private suspend fun execute(endpoint: String, payload: JSONObject): String = withContext(Dispatchers.IO) {
        val client = JSONObject().put("hl", "en").put("gl", "US").put("clientName", CLIENT_NAME).put("clientVersion", CLIENT_VERSION)
        val vd = visitorData
        if (vd.isNotEmpty()) client.put("visitorData", vd)
        payload.put("context", JSONObject().put("client", client)).put("isAudioOnly", true)
        val req = Request.Builder().url(base + endpoint)
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .header("User-Agent", CHROME_UA).header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", "67").header("X-YouTube-Client-Version", CLIENT_VERSION)
            .apply { if (vd.isNotEmpty()) header("X-Goog-Visitor-Id", vd) }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val body = http.newCall(req).await().use { r ->
            if (!r.isSuccessful) throw YtmException("youtube: $endpoint answered HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        if (visitorData.isEmpty()) {
            val root = OrderedJson.parse(body) as? JObj
            root?.obj("responseContext")?.str("visitorData")?.takeIf { it.isNotEmpty() }?.let { visitorData = it }
        }
        body
    }

    companion object {
        const val CLIENT_NAME = "WEB_REMIX"
        const val CLIENT_VERSION = "1.20250219.01.00"

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }
}

/** An OkHttp call that a coroutine cancellation (the 7 s search timeout) cancels. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}
