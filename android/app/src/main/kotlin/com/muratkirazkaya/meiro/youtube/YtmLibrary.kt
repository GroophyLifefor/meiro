package com.muratkirazkaya.meiro.youtube

/**
 * One list of the account's YouTube Music library (M6g): a playlist ([album] false; [id] without the "VL" a browse id
 * carries) or a saved album ([album] true; [id] its `MPRE...` browse id). [count] is the number of tracks the page
 * names ("52 songs"), null when it names none; [artist] is an album's artist.
 */
data class YtmListEntry(
    val id: String,
    val title: String,
    val thumbnail: String = "",
    val count: Int? = null,
    val album: Boolean = false,
    val artist: String = "",
)

/**
 * The library the Library's "YouTube Music" tab and Home's quick picks show: the liked songs ([liked], the "LM"
 * playlist; its [YtmListEntry.count] comes from the liked page's own header), the account's playlists and its saved
 * albums, in the library's order.
 */
data class YtmLibrary(val liked: YtmListEntry?, val playlists: List<YtmListEntry>, val albums: List<YtmListEntry>)

/**
 * Reading the account's library (`youtube/browse.go` `StreamLibrary`/`drainBrowse`, `playlist.go` `libraryPlaylists`,
 * `navigation.go` `readPlaylistRest`). Pure apart from the [continueBrowse] function it is handed, so the fixtures
 * test it without a network.
 */
object YtmLibraryParse {
    /** `likedMusicID`: the playlist a library holds thumbs-up songs in. */
    const val LIKED_ID = "LM"

    /** `FEmusic_library_landing` (`GetLibrary`). */
    const val LIBRARY_ID = "FEmusic_library_landing"

    /** YouTube Music's "New albums & singles" page (the mock's "YouTube Music: Yeni çıkanlar" shelf). */
    const val NEW_RELEASES_ID = "FEmusic_new_releases_albums"

    /** `drainBrowse`'s guard: a result that names more than this many continuation pages is a runaway. */
    const val MAX_PAGES = 1000

    private val countRe = Regex("""(\d[\d,.]*)\s+(songs?|tracks?|episodes?|videos?)\b""", RegexOption.IGNORE_CASE)
    private val yearRe = Regex("""\d{4}""")
    private val kindWords = setOf("Album", "Single", "EP", "Playlist", "Audiobook")

    /** "1,204 songs • 80+ hours" → 1204; null when the text names no number of tracks. */
    fun trackCount(text: String): Int? = countRe.find(text)?.groupValues?.get(1)?.replace(",", "")?.replace(".", "")?.toIntOrNull()

    /**
     * `drainBrowse`: folds every continuation page reachable from [first] into it, a level at a time (the desktop asks
     * for a level's pages at once, four at a time; the phone asks one after another, the result is the same). A page
     * continuation adds its sections; a shelf's continuation adds its items to that shelf. Throws past [MAX_PAGES].
     */
    suspend fun drain(first: BrowsePage, continueBrowse: suspend (String) -> BrowsePage): BrowsePage {
        val items = first.items.toMutableList()
        val sections = first.sections.map { SectionAcc(it) }.toMutableList()
        data class Next(val token: String, val section: Int)
        var queue = buildList {
            if (first.continuation.isNotEmpty()) add(Next(first.continuation, -1))
            first.sections.forEachIndexed { i, s -> if (s.continuationToken.isNotEmpty()) add(Next(s.continuationToken, i)) }
        }
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val level = ArrayList<Next>()
            for (n in queue) {
                if (!seen.add(n.token)) continue
                if (seen.size > MAX_PAGES) throw YtmException("youtube: library exceeded $MAX_PAGES continuation pages")
                level += n
            }
            queue = ArrayList()
            for (n in level) {
                val page = continueBrowse(n.token)
                items += page.items
                if (n.section >= 0) sections[n.section].items += page.items
                else sections += page.sections.map { SectionAcc(it) }
                if (page.continuation.isNotEmpty()) queue += Next(page.continuation, -1)
                page.sections.forEachIndexed { i, s ->
                    if (s.continuationToken.isEmpty()) return@forEachIndexed
                    val at = if (n.section >= 0) n.section else sections.size - page.sections.size + i
                    queue += Next(s.continuationToken, at)
                }
            }
        }
        return first.copy(items = items, sections = sections.map { Renderers.Section(it.title, it.kind, it.items.toList(), "") }, continuation = "")
    }

    private class SectionAcc(s: Renderers.Section) {
        val title = s.title; val kind = s.kind
        val items = s.items.toMutableList()
    }

    /** A playlist page's own list of tracks (its first page). */
    fun isPlaylistShelf(kind: String) = kind == "musicPlaylistShelfRenderer" || kind == "playlistVideoListRenderer"

    /** `isPlaylistContinuation`: a section that continues the playlist's own list, not the suggestions beside it. */
    fun isPlaylistContinuation(kind: String) = kind == "playlistVideoListContinuation" || kind == "musicPlaylistShelfContinuation"

    /**
     * `readPlaylistRest`: the tracks after a playlist's first page, following the playlist's own continuation and
     * stopping at the suggestions shelf (which never ends) or at a token seen before.
     */
    suspend fun playlistRest(start: String, continueBrowse: suspend (String) -> BrowsePage): List<MusicItem> {
        val tracks = ArrayList<MusicItem>()
        val seen = HashSet<String>()
        var token = start
        while (token.isNotEmpty() && seen.add(token)) {
            val page = continueBrowse(token)
            var playlistPage = false
            var next = ""
            var suggestions = false
            for (s in page.sections) {
                if (isPlaylistContinuation(s.kind)) {
                    playlistPage = true
                    tracks += s.items
                    if (s.continuationToken.isNotEmpty()) next = s.continuationToken
                } else suggestions = true
            }
            if (suggestions) break
            if (playlistPage) { token = next.ifEmpty { page.continuation }; continue }
            tracks += page.items
            token = page.continuation
        }
        return tracks
    }

    /**
     * The library's lists (`libraryPlaylists`, widened to albums): the sections' items (the loose items only for a page
     * with none), each list once. The liked songs ("LM") are kept apart; artists, songs, podcasts and the like are not
     * lists and are left out.
     */
    fun library(page: BrowsePage, likedCount: Int? = null): YtmLibrary {
        val groups = if (page.sections.isNotEmpty()) page.sections.map { it.items } else listOf(page.items)
        var liked: YtmListEntry? = null
        val playlists = ArrayList<YtmListEntry>()
        val albums = ArrayList<YtmListEntry>()
        val seen = HashSet<String>()
        for (item in groups.flatten()) {
            val (kind, raw) = item.target()
            when (kind) {
                TargetKind.Playlist -> {
                    val id = raw.removePrefix("VL")
                    if (id.isEmpty() || !seen.add(id)) continue
                    val e = YtmListEntry(id, item.title, item.thumbnail, trackCount(item.subtitle))
                    if (id == LIKED_ID) liked = e.copy(count = likedCount ?: e.count) else playlists += e
                }
                TargetKind.Album -> {
                    if (!seen.add(raw)) continue
                    albums += YtmListEntry(raw, item.title, item.thumbnail, trackCount(item.subtitle), album = true, artist = albumArtist(item))
                }
                else -> {}
            }
        }
        return YtmLibrary(liked, playlists, albums)
    }

    /** An album card's artist: its byline without the kind word and the year ("Album • Hidra • 2020" → "Hidra"). */
    fun albumArtist(item: MusicItem): String {
        if (item.artists.isNotEmpty()) return item.artists
        return item.subtitle.split(" • ").map { it.trim() }.filter { it.isNotEmpty() && it !in kindWords && !yearRe.matches(it) }.joinToString(", ")
    }

    /** An album card's kind word ("Album", "Single", "EP"), "" when it names none. */
    fun albumKind(item: MusicItem): String = item.subtitle.split(" • ").map { it.trim() }.firstOrNull { it in kindWords }.orEmpty()

    /** The albums of the new releases page, in its order, each once. */
    fun releases(page: BrowsePage): List<MusicItem> {
        val all = if (page.sections.isNotEmpty()) page.sections.flatMap { it.items } else page.items
        return all.filter { it.target().first == TargetKind.Album }.distinctBy { it.browseId }
    }
}
