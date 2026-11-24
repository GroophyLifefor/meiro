package com.muratkirazkaya.meiro.youtube

import com.muratkirazkaya.meiro.CHROME_UA
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
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

open class YtmException(message: String) : IOException(message)

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

/** A track's tabs (`tracktabs.go`): finding a tab's page in a `next` response, and the tracks of the related page. */
object YtmRelated {
    const val RELATED_PAGE = "MUSIC_PAGE_TYPE_TRACK_RELATED"

    /** `musicTabBrowseID`: the browse id of the nearest ancestor (or the node itself) of the node whose pageType is [pageType]. */
    fun tabBrowseId(text: String, pageType: String): String {
        fun walk(v: Any?, parents: List<JObj>): String {
            when (v) {
                is List<*> -> for (c in v) walk(c, parents).takeIf { it.isNotEmpty() }?.let { return it }
                is JObj -> {
                    if (v["pageType"] == pageType) {
                        for (i in parents.indices.reversed()) Renderers.navigationId(parents[i]).takeIf { it.isNotEmpty() }?.let { return it }
                        return Renderers.navigationId(v)
                    }
                    val with = parents + v
                    for ((_, m) in v.members) walk(m, with).takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
            return ""
        }
        return walk(OrderedJson.parse(text), emptyList())
    }

    /** `relatedTracks` (recommendations.go:232): the playable tracks of the page's items and of every section, in page order. */
    fun tracks(page: BrowsePage): List<MusicItem> = page.items.filter { it.isTrack() } + page.sections.flatMap { s -> s.items.filter { it.isTrack() } }
}

/** `youtube.Lyrics`: the lyrics tab's plain text and its footer ("Source: …"), and the timed lines when there are any. */
data class YtmLyrics(val description: String, val footer: String, val timed: List<com.muratkirazkaya.meiro.lyrics.LyricLine>)

/** Reading a track's lyrics page (`tracktabs.go` `GetLyrics`, `parseTimedLyrics`). */
object YtmLyricsParse {
    const val LYRICS_PAGE = "MUSIC_PAGE_TYPE_TRACK_LYRICS"

    /** The web client's page: `musicDescriptionShelfRenderer` description and footer; a page without one gives "". */
    fun plain(text: String): Pair<String, String> {
        val r = Renderers.findRenderer(OrderedJson.parse(text), "musicDescriptionShelfRenderer") ?: return "" to ""
        return Renderers.rendererText(r["description"]) to Renderers.rendererText(r["footer"])
    }

    /** The ANDROID_MUSIC page: the first `timedLyricsData` array, `[{lyricLine, cueRange.startTimeMilliseconds}]` (ms as a number or a string). */
    fun timed(text: String): List<com.muratkirazkaya.meiro.lyrics.LyricLine> {
        fun walk(v: Any?): List<com.muratkirazkaya.meiro.lyrics.LyricLine>? {
            when (v) {
                is List<*> -> for (c in v) walk(c)?.let { return it }
                is JObj -> {
                    val data = v["timedLyricsData"]
                    if (data is List<*>) {
                        return data.mapNotNull { item ->
                            val e = item as? JObj ?: return@mapNotNull null
                            val start = when (val ms = e.obj("cueRange")?.get("startTimeMilliseconds")) {
                                is Double -> ms.toLong()
                                is String -> ms.toLongOrNull()
                                else -> null
                            } ?: return@mapNotNull null
                            com.muratkirazkaya.meiro.lyrics.LyricLine(start, (e["lyricLine"] as? String).orEmpty().trim())
                        }
                    }
                    for ((_, m) in v.members) walk(m)?.let { return it }
                }
            }
            return null
        }
        return walk(OrderedJson.parse(text)).orEmpty()
    }
}

/**
 * The YouTube Music InnerTube client (`youtube/client.go`, PORTING.md 2.1): WEB_REMIX, `hl=en`, `gl=US`. Without [auth]
 * it is anonymous: the visitor id is learned from the first response and sent from then on, kept only in memory. With
 * [auth] (M6f, the signed-in session) every request carries the session (`setIdentity`): `Cookie`, a fresh
 * `SAPISIDHASH` for the request's origin, `X-Goog-Authuser`, `X-Goog-PageId` for a brand channel and
 * `context.user`; no visitor id; the cookie only ever goes to an https youtube.com URL.
 */
class YtmClient(
    private val http: OkHttpClient = defaultHttp(),
    private val base: String = "https://music.youtube.com/youtubei/v1/",
    private val auth: YtmCookieAuth? = null,
    private val epochSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
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

    /**
     * `ContinueBrowse` (`browse.go:177`): the next page of a browse page or of one of its shelves. A continuation page
     * that holds no shelf names its next page only inside its trailing `continuationItemRenderer`
     * (`continuationCommand.token`); that token is taken as the page's own (the desktop's `pageContinuationToken`
     * reads only a bare `continuation` string and stops there).
     */
    suspend fun continueBrowse(token: String): BrowsePage {
        require(token.isNotBlank()) { "youtube: browse continuation token is required" }
        val text = execute("browse", JSONObject().put("continuation", token))
        val page = YtmBrowse.parse(text)
        if (page.sections.isEmpty() && page.continuation.isEmpty()) {
            val t = Renderers.continuationToken(OrderedJson.parse(text))
            if (t.isNotEmpty() && t != token) return page.copy(continuation = t)
        }
        return page
    }

    /**
     * M6g, `StreamLibrary` (`browse.go:58`): the account's library landing page with every continuation page folded
     * in. Needs [auth].
     */
    suspend fun library(): BrowsePage {
        if (auth == null) throw YtmException("youtube: GetLibrary requires cookie authentication")
        val first = YtmBrowse.parse(execute("browse", JSONObject().put("browseId", YtmLibraryParse.LIBRARY_ID)))
        return YtmLibraryParse.drain(first) { continueBrowse(it) }
    }

    /**
     * M6g, `GetPlaylist` (`browse.go:203`) and the rest of its tracks as `withTracksOf` reads them (`collections.go:150`,
     * `readPlaylistRest`): a playlist page, "VL" added when [id] lacks it. With [all] false only the first page.
     */
    suspend fun playlist(id: String, all: Boolean = true): BrowsePage {
        require(id.isNotBlank()) { "youtube: playlist ID is required" }
        val browseId = if (id.startsWith("VL")) id else "VL$id"
        val first = YtmBrowse.parse(execute("browse", JSONObject().put("browseId", browseId)))
        // The playlist's own shelf names the page after its tracks; the page's token (the suggestions) is the fallback.
        val shelf = first.sections.indexOfLast { YtmLibraryParse.isPlaylistShelf(it.kind) && it.continuationToken.isNotEmpty() }
        val token = if (shelf >= 0) first.sections[shelf].continuationToken else first.continuation
        if (!all || token.isEmpty()) return first
        val rest = YtmLibraryParse.playlistRest(token) { continueBrowse(it) }
        if (rest.isEmpty()) return first
        val at = if (shelf >= 0) shelf else first.sections.lastIndex
        return if (at >= 0) {
            val s = first.sections[at]
            first.copy(items = first.items + rest, sections = first.sections.toMutableList().also { it[at] = Renderers.Section(s.title, s.kind, s.items + rest, "") })
        } else first.copy(items = first.items + rest, continuation = "")
    }

    /** M6g: YouTube Music's "New albums & singles" page (the mock's "YouTube Music: Yeni çıkanlar" shelf). */
    suspend fun newReleases(): BrowsePage = YtmBrowse.parse(execute("browse", JSONObject().put("browseId", YtmLibraryParse.NEW_RELEASES_ID)))

    /**
     * `GetRelated` (`tracktabs.go:111`): the track's "Related" tab. `next` for the video names the tab's browse id
     * (`MUSIC_PAGE_TYPE_TRACK_RELATED`), and that page is read like any browse page.
     */
    suspend fun related(videoId: String): BrowsePage {
        require(videoId.isNotBlank()) { "youtube: video ID is required" }
        val next = execute("next", JSONObject().put("videoId", videoId))
        val browseId = YtmRelated.tabBrowseId(next, YtmRelated.RELATED_PAGE)
        if (browseId.isEmpty()) throw YtmException("youtube: track response has no ${YtmRelated.RELATED_PAGE} tab")
        return YtmBrowse.parse(execute("browse", JSONObject().put("browseId", browseId)))
    }

    /**
     * `GetLyrics` (`tracktabs.go:34`): `next` names the lyrics tab's browse id; the web client's page gives the plain
     * text, and the same page asked as ANDROID_MUSIC the timed lines. A failure of that second request leaves the plain
     * text alone. A track with no lyrics tab throws.
     */
    suspend fun lyrics(videoId: String, log: (String) -> Unit = {}): YtmLyrics {
        require(videoId.isNotBlank()) { "youtube: video ID is required" }
        val browseId = YtmRelated.tabBrowseId(execute("next", JSONObject().put("videoId", videoId)), YtmLyricsParse.LYRICS_PAGE)
        if (browseId.isEmpty()) throw YtmException("youtube: track response has no ${YtmLyricsParse.LYRICS_PAGE} tab")
        val (description, footer) = YtmLyricsParse.plain(execute("browse", JSONObject().put("browseId", browseId)))
        val timed = try {
            YtmLyricsParse.timed(execute("browse", JSONObject().put("browseId", browseId), android = true))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("lyrics: youtube video_id=$videoId browse_id=$browseId: android browse failed: $e")
            emptyList()
        }
        log("lyrics: youtube video_id=$videoId browse_id=$browseId plain_chars=${description.length} timed_lines=${timed.size}")
        return YtmLyrics(description, footer, timed)
    }

    /**
     * `GetUpNext` / `ContinueUpNext` (`upnext.go`): the queue YouTube Music generates after [videoId]. With no
     * [playlistId] the track is seeded with its own mix (`RDAMVM<id>`); a first page that names no queue playlist is
     * asked again through its automix link, as the desktop does.
     */
    suspend fun upNext(videoId: String, playlistId: String = "", continuation: String = ""): UpNextPage {
        require(videoId.isNotBlank()) { "youtube: video ID is required" }
        val payload = JSONObject().put("videoId", videoId).put("playlistId", playlistId.ifEmpty { "RDAMVM$videoId" })
        if (continuation.isNotEmpty()) payload.put("continuation", continuation)
        val root = OrderedJson.parse(execute("next", payload))
        val result = YtmUpNext.result(root)
        if (continuation.isNotEmpty() || result.playlistId.isNotEmpty()) return result
        val automix = YtmUpNext.automixPayload(root) ?: return result
        val again = JSONObject()
        for ((k, v) in automix) if (v is String || v is Number || v is Boolean) again.put(k, v)
        again.put("videoId", videoId)
        return YtmUpNext.parse(execute("next", again))
    }

    /**
     * `GetAccountDetails` (`accounts.go:35`): whom the session signs in as. Needs [auth]. YouTube answers a session it
     * has retired with signed-out content and HTTP 200, so the caller checks for a name or a channel id.
     */
    suspend fun accountDetails(): YtmAccountDetails {
        if (auth == null) throw YtmException("youtube: GetAccountDetails requires cookie authentication")
        return YtmAccountParse.details(execute("account/account_menu", JSONObject()))
    }

    /**
     * One InnerTube request as the web app, or with [android] as the YouTube Music Android app (`executeAs`
     * `androidMusicClient`: ANDROID_MUSIC 7.21.50, client id 21, its own user agent, not audio-only), the only client
     * given timed lyrics.
     */
    private suspend fun execute(endpoint: String, payload: JSONObject, android: Boolean = false): String = withContext(Dispatchers.IO) {
        val name = if (android) ANDROID_NAME else CLIENT_NAME
        val version = if (android) ANDROID_VERSION else CLIENT_VERSION
        val client = JSONObject().put("hl", "en").put("gl", "US").put("clientName", name).put("clientVersion", version)
        val a = auth
        // The visitor id is an anonymous visit's; a signed-in session is its own.
        val vd = if (a == null) visitorData else ""
        if (vd.isNotEmpty()) client.put("visitorData", vd)
        val context = JSONObject().put("client", client)
        if (a != null) context.put("user", JSONObject().apply { if (a.onBehalfOfUser.isNotEmpty()) put("onBehalfOfUser", a.onBehalfOfUser) })
        payload.put("context", context)
        if (!android) payload.put("isAudioOnly", true)
        val url = (base + endpoint).toHttpUrl()
        val req = Request.Builder().url(url)
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .header("User-Agent", if (android) ANDROID_UA else CHROME_UA).header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("X-YouTube-Client-Name", if (android) "21" else "67").header("X-YouTube-Client-Version", version)
            .apply { if (vd.isNotEmpty()) header("X-Goog-Visitor-Id", vd) }
            .apply {
                if (a != null) {
                    // `client.go:317`: the session never goes anywhere but YouTube over https.
                    if (!YtmCookieAuth.isYouTubeUrl(url)) throw YtmException("youtube: refusing to send the session to a non-YouTube URL")
                    header("Cookie", a.cookie)
                    header("Authorization", a.authorization(epochSeconds(), "${url.scheme}://${url.host}"))
                    header("X-Goog-Authuser", a.accountIndex.toString())
                    if (a.onBehalfOfUser.isNotEmpty()) header("X-Goog-PageId", a.onBehalfOfUser)
                }
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val body = http.newCall(req).await().use { r ->
            // The error names the endpoint and the status only: never the URL's query, a header or the cookie.
            if (r.code == 401 || r.code == 403) throw YtmUnauthorized("youtube: $endpoint answered HTTP ${r.code}: the session was rejected")
            if (!r.isSuccessful) throw YtmException("youtube: $endpoint answered HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        if (a == null && visitorData.isEmpty()) {
            val root = OrderedJson.parse(body) as? JObj
            root?.obj("responseContext")?.str("visitorData")?.takeIf { it.isNotEmpty() }?.let { visitorData = it }
        }
        body
    }

    companion object {
        const val CLIENT_NAME = "WEB_REMIX"
        const val CLIENT_VERSION = "1.20250219.01.00"
        const val ANDROID_NAME = "ANDROID_MUSIC"
        const val ANDROID_VERSION = "7.21.50"
        const val ANDROID_UA = "com.google.android.apps.youtube.music/$ANDROID_VERSION (Linux; U; Android 14) gzip"

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
