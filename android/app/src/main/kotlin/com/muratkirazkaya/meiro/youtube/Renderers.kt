package com.muratkirazkaya.meiro.youtube

import com.muratkirazkaya.meiro.data.Source

/** `youtube.ItemKind`: what kind of renderer an item came from (the kind of a two-row item is decided by its target). */
enum class ItemKind { Track, Video, MusicItem, Playlist, Album, Artist, Episode }

/** What an item opens (`media.TargetKind`). */
enum class TargetKind { None, Track, Album, Playlist, Podcast, Artist }

/** A provider's copy of a track that another provider's row also plays (`media.SourceRef`). */
data class Ref(val source: String, val id: String = "", val url: String = "")

/**
 * One row or card of a YouTube Music response, or a SoundCloud track (`media.Item`): the fields the search page reads.
 * [artist] is the first artist the byline links, [artists] every one joined ", "; both are empty when the byline links
 * none. [subtitle] is the flattened byline as YouTube Music shows it ("Song • Daft Punk", the length trimmed off).
 */
data class MusicItem(
    val kind: ItemKind = ItemKind.Track,
    val source: String = Source.YTM,
    val id: String = "",
    val videoId: String = "",
    val browseId: String = "",
    val playlistId: String = "",
    val playlistSetId: String = "",
    val url: String = "",
    val title: String = "",
    val subtitle: String = "",
    val duration: String = "",
    val artist: String = "",
    val artists: String = "",
    val album: String = "",
    val thumbnail: String = "",
    /** The providers a merged track plays from (`Sources`); empty means only [source]. */
    val sources: List<Ref> = emptyList(),
    /** The track's number on an album page (the renderer's `index`), "" elsewhere. */
    val index: String = "",
) {
    /** `Item.Target`: a video ID always wins over the artist link beside it; album and playlist IDs route items without. */
    fun target(): Pair<TargetKind, String> {
        val browse = browseId
        val playlist = playlistId.ifEmpty { browse }
        val album = isAlbumId(browse); val list = isBrowsablePlaylist(playlist)
        return when {
            videoId.isNotEmpty() && !album && !list -> TargetKind.Track to videoId
            album -> TargetKind.Album to browse
            list -> TargetKind.Playlist to playlist
            isPodcastId(browse) -> TargetKind.Podcast to browse
            isArtistId(browse) -> TargetKind.Artist to browse
            videoId.isNotEmpty() -> TargetKind.Track to videoId
            url.isNotEmpty() && kind == ItemKind.Track -> TargetKind.Track to url
            else -> TargetKind.None to ""
        }
    }

    fun isTrack() = target().first == TargetKind.Track

    /** The playback key (`Key`): the video ID, else the page URL (SoundCloud). */
    fun key() = videoId.ifEmpty { url }

    /** The provider's own reference (`FirstRef`). */
    fun firstRef(): Ref? = when {
        sources.isNotEmpty() -> sources[0]
        source == Source.SOUNDCLOUD && url.isNotEmpty() -> Ref(Source.SOUNDCLOUD, id, url)
        videoId.isNotEmpty() -> Ref(source.ifEmpty { Source.YTM }, videoId)
        else -> null
    }

    fun refFor(src: String): Ref? = sources.firstOrNull { it.source == src } ?: firstRef()?.takeIf { it.source == src }
}

fun isAlbumId(id: String) = id.startsWith("MPR") || id.contains("privately_owned_release")
fun isPodcastId(id: String) = id.startsWith("MPSPP")
fun isArtistId(id: String) = id.startsWith("UC") || id.contains("privately_owned_artist")
fun isBrowsablePlaylist(id: String) = id.startsWith("VL") || id.startsWith("PL")
fun episodeVideoIdFromBrowseId(id: String): String = if (id.startsWith("MPED") && id.length == 15) id.substring(4) else ""

/**
 * Port of `youtube/renderers.go` and `credits.go`: the generic walk over a response. A port that follows fixed paths
 * drifts with YouTube; this walks every member in document order. The like and library states of a row are not read
 * here (nothing on the phone shows them yet).
 */
object Renderers {
    class Parts(val items: List<MusicItem>, val sections: List<Section>, val top: MusicItem?)
    class Section(val title: String, val kind: String, val items: List<MusicItem>, val continuationToken: String)

    private data class Identity(val videoId: String, val browseId: String, val playlistId: String, val title: String, val setVideoId: String)

    private class SectionItems { val items = ArrayList<MusicItem>(); val seen = HashSet<Identity>() }

    fun findRenderer(root: Any?, name: String): JObj? {
        when (root) {
            is List<*> -> for (c in root) findRenderer(c, name)?.let { return it }
            is JObj -> {
                (root[name] as? JObj)?.let { return it }
                for ((_, v) in root.members) findRenderer(v, name)?.let { return it }
            }
        }
        return null
    }

    /**
     * `browseParts`: the items a response holds, the sections of a browse page, and the card a search leads with. With
     * [search] there are no sections and the top card is kept apart from the list. One addition to the Go walk: a song
     * listed inside a search's artist card names no artist of its own (the card is the artist), so it is credited with
     * the card's artist.
     */
    fun browseParts(root: Any?, search: Boolean): Parts {
        val items = ArrayList<MusicItem>()
        val sections = ArrayList<Section>()
        var top: MusicItem? = null
        val seen = HashSet<Identity>()
        fun walk(value: Any?, section: SectionItems?, cardArtist: String?) {
            when (value) {
                is List<*> -> for (c in value) walk(c, section, cardArtist)
                is JObj -> for ((key, v) in value.members) {
                    if (section == null && !search && isSectionRenderer(key) && v is JObj) {
                        val current = SectionItems()
                        walk(v, current, cardArtist)
                        sections += Section(rendererTitle(v), key, current.items, continuationToken(v))
                        continue
                    }
                    var nextCard = cardArtist
                    val parsed = parseMusicRenderer(key, v)
                    if (parsed != null) {
                        var (item, renderer) = parsed
                        if (search && key == "musicCardShelfRenderer") {
                            if (top == null) top = item
                            if (isArtistId(item.browseId) && item.videoId.isEmpty()) nextCard = item.title
                        } else {
                            if (cardArtist != null && item.artists.isEmpty() && item.isTrack()) item = item.copy(artist = cardArtist, artists = cardArtist)
                            append(items, seen, item, renderer)
                            if (section != null) append(section.items, section.seen, item, renderer)
                        }
                    }
                    walk(v, section, nextCard)
                }
            }
        }
        walk(root, null, null)
        return Parts(items, sections, top)
    }

    internal fun isSectionRenderer(key: String) = key in setOf(
        "musicShelfRenderer", "musicShelfContinuation", "musicPlaylistShelfRenderer", "musicPlaylistShelfContinuation",
        "playlistVideoListRenderer", "playlistVideoListContinuation", "gridRenderer", "gridContinuation", "musicCarouselShelfRenderer",
    )

    private fun append(items: MutableList<MusicItem>, seen: MutableSet<Identity>, item: MusicItem, renderer: JObj) {
        val id = Identity(item.videoId, item.browseId, item.playlistId, item.title, nestedString(renderer["playlistItemData"], "playlistSetVideoId"))
        if (id == Identity("", "", "", "", "")) return
        if (!seen.add(id)) return
        items += item
    }

    private fun parseMusicRenderer(key: String, value: Any?): Pair<MusicItem, JObj>? {
        val renderer = value as? JObj ?: return null
        if (key == "musicCardShelfRenderer") return parseMusicCardShelf(renderer)?.let { it to renderer }
        val kind = rendererKind(key) ?: return null
        var item = parseMusicItem(kind, renderer)
        if (key == "playlistPanelVideoRenderer") {
            val t = nestedString(renderer["navigationEndpoint"], "musicVideoType")
            if (t.isEmpty() || t == "MUSIC_VIDEO_TYPE_ATV") item = item.copy(kind = ItemKind.Track)
        }
        return item to renderer
    }

    private fun rendererKind(key: String): ItemKind? = when (key) {
        "musicResponsiveListItemRenderer" -> ItemKind.Track
        "musicTwoRowItemRenderer" -> ItemKind.MusicItem
        "musicVideoRenderer", "videoRenderer", "playlistVideoRenderer", "playlistPanelVideoRenderer" -> ItemKind.Video
        "gridPlaylistRenderer" -> ItemKind.Playlist
        "gridAlbumRenderer" -> ItemKind.Album
        "gridArtistRenderer" -> ItemKind.Artist
        "musicMultiRowListItemRenderer" -> ItemKind.Episode
        else -> null
    }

    /** `parseMusicCardShelf`: the card a search leads with. */
    private fun parseMusicCardShelf(r: JObj): MusicItem? {
        val title = rendererText(r["title"])
        if (title.isEmpty()) return null
        var subtitle = rendererText(r["subtitle"])
        var duration = ""
        trailingDuration(subtitle).takeIf { it.isNotEmpty() }?.let { duration = it; subtitle = subtitle.removeSuffix(" • $it") }
        var (browseId, videoId) = cardTarget(r)
        var kind = ItemKind.MusicItem
        if (videoId.isEmpty()) {
            videoId = episodeVideoIdFromBrowseId(browseId)
            if (videoId.isNotEmpty()) kind = ItemKind.Episode
        }
        val (artists, album) = bylineCredits(r["subtitle"])
        if (videoId.isNotEmpty() && kind != ItemKind.Episode) kind = ItemKind.Video
        val thumb = rendererThumbnail(r["thumbnail"]).ifEmpty { rendererThumbnail(r) }
        return MusicItem(
            kind = kind, id = videoId.ifEmpty { browseId }, videoId = videoId, browseId = browseId,
            title = title, subtitle = subtitle, duration = duration,
            artist = artists.firstOrNull().orEmpty(), artists = artists.joinToString(", "), album = album, thumbnail = thumb,
        )
    }

    private fun cardTarget(r: JObj): Pair<String, String> {
        var browseId = ""; var videoId = ""
        for (v in listOf(r["title"], r["onTap"])) {
            if (browseId.isEmpty()) browseId = navigationId(v)
            if (videoId.isEmpty()) videoId = nestedString(v, "videoId")
        }
        if (browseId.isEmpty() && videoId.isEmpty()) videoId = rendererVideoId(r)
        if (browseId.isEmpty() && videoId.isEmpty()) browseId = cardArtistBrowseId(r)
        return browseId to videoId
    }

    private fun trailingDuration(subtitle: String): String =
        subtitle.split(" • ").map { it.trim() }.firstOrNull { isDurationText(it) }.orEmpty()

    /** `parseMusicItem`. */
    fun parseMusicItem(kind: ItemKind, r: JObj): MusicItem {
        var title = rendererText(r["title"])
        if (title.isEmpty()) title = rendererText(r["headline"])
        if (title.isEmpty()) title = rendererColumnText(r, "flexColumns", 0)
        var subtitle = rendererText(r["subtitle"])
        if (subtitle.isEmpty()) subtitle = rendererColumnText(r, "flexColumns", 1)
        if (subtitle.isEmpty()) subtitle = rendererText(r["longBylineText"]).ifEmpty { rendererText(r["shortBylineText"]) }
        var duration = rendererText(r["lengthText"])
        if (duration.isEmpty()) duration = rendererColumnText(r, "fixedColumns", 0)
        if (duration.isEmpty()) {
            duration = rendererFlexDuration(r)
            if (duration.isNotEmpty()) subtitle = subtitle.removeSuffix(" • $duration")
        }
        var videoId = r.str("videoId").orEmpty()
        if (videoId.isEmpty()) videoId = rendererVideoId(r)
        var browseId = navigationId(r["navigationEndpoint"])
        if (browseId.isEmpty()) browseId = navigationId(r["title"])
        val episodeVideoId = episodeVideoIdFromBrowseId(browseId)
        if (videoId.isEmpty()) videoId = episodeVideoId
        var k = kind
        if (videoId.isNotEmpty() && (isEpisode(r) || episodeVideoId.isNotEmpty())) k = ItemKind.Episode
        var artists = emptyList<String>(); var album = ""
        if (kind == ItemKind.Track || kind == ItemKind.Video || videoId.isNotEmpty()) {
            val c = rendererCredits(r); artists = c.first; album = c.second
        }
        val playlistId = r.str("playlistId").orEmpty()
        val setId = nestedString(r["playlistItemData"], "playlistSetVideoId")
        if (browseId.isEmpty() && (kind == ItemKind.Track || kind == ItemKind.Video)) browseId = rendererArtistBrowseId(r)
        val id = when { videoId.isNotEmpty() -> videoId; browseId.isNotEmpty() -> browseId; else -> playlistId }
        val thumb = rendererThumbnail(r["thumbnail"]).ifEmpty { rendererThumbnail(r) }
        return MusicItem(
            kind = k, id = id, videoId = videoId, browseId = browseId, playlistId = playlistId, playlistSetId = setId,
            title = title, subtitle = subtitle, duration = duration,
            artist = artists.firstOrNull().orEmpty(), artists = artists.joinToString(", "), album = album, thumbnail = thumb,
            index = rendererText(r["index"]).trim(),
        )
    }

    private fun isEpisode(r: JObj) = listOf("navigationEndpoint", "onTap", "overlay")
        .any { nestedString(r[it], "musicVideoType") == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" }

    fun rendererText(value: Any?): String {
        if (value is String) return value
        val o = value as? JObj ?: return ""
        o.str("simpleText")?.let { return it }
        val runs = o.arr("runs") ?: return ""
        return buildString { for (run in runs) ((run as? JObj)?.get("text") as? String)?.let { append(it) } }
    }

    private fun rendererColumnText(r: JObj, column: String, index: Int): String {
        val cols = r.arr(column) ?: return ""
        val c = cols.getOrNull(index) as? JObj ?: return ""
        for (k in listOf("musicResponsiveListItemFlexColumnRenderer", "musicResponsiveListItemFixedColumnRenderer")) {
            c.obj(k)?.let { return rendererText(it["text"]) }
        }
        return ""
    }

    private fun rendererVideoId(r: JObj): String {
        for (k in listOf("playlistItemData", "overlay", "flexColumns", "thumbnailOverlay", "navigationEndpoint", "title", "onTap")) {
            val v = r[k] ?: continue
            nestedString(v, "videoId").takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    private fun rendererTitle(r: JObj): String {
        rendererText(r["title"]).takeIf { it.isNotEmpty() }?.let { return it }
        val header = r.obj("header") ?: return ""
        for ((_, v) in header.members) {
            val hr = v as? JObj ?: continue
            rendererText(hr["title"]).takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    /** The first non-empty string member [name] anywhere in [value], in document order. */
    fun nestedString(value: Any?, name: String): String {
        when (value) {
            is List<*> -> for (c in value) nestedString(c, name).takeIf { it.isNotEmpty() }?.let { return it }
            is JObj -> {
                (value[name] as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
                for ((_, v) in value.members) nestedString(v, name).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return ""
    }

    private fun rendererFlexDuration(r: JObj): String {
        val cols = r.arr("flexColumns") ?: return ""
        for (ci in cols.indices.reversed()) {
            val cr = (cols[ci] as? JObj)?.obj("musicResponsiveListItemFlexColumnRenderer") ?: continue
            val runs = cr.obj("text")?.arr("runs") ?: continue
            for (ri in runs.indices.reversed()) {
                val run = runs[ri] as? JObj ?: continue
                if (run.has("navigationEndpoint")) continue
                val t = run.str("text").orEmpty()
                if (isDurationText(t)) return t
            }
        }
        return ""
    }

    fun isDurationText(text: String): Boolean {
        val parts = text.split(":")
        if (parts.size < 2 || parts.size > 3) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 2 && p.all { it in '0'..'9' } }
    }

    /** `navigationID`: the first browse ID (or `VL`/`PL` playlist ID) outside any watch endpoint. */
    fun navigationId(value: Any?): String {
        when (value) {
            is List<*> -> for (c in value) navigationId(c).takeIf { it.isNotEmpty() }?.let { return it }
            is JObj -> {
                if (value.has("watchEndpoint")) return ""
                for (name in listOf("browseId", "playlistId")) {
                    val v = value[name] as? String ?: continue
                    if (v.isEmpty()) continue
                    if (name == "playlistId" && !isBrowsablePlaylist(v)) continue
                    return v
                }
                for ((_, v) in value.members) navigationId(v).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return ""
    }

    /** The last (largest) entry of the first `thumbnails` array, in document order. */
    fun rendererThumbnail(value: Any?): String {
        when (value) {
            is List<*> -> for (c in value) rendererThumbnail(c).takeIf { it.isNotEmpty() }?.let { return it }
            is JObj -> {
                val t = value.arr("thumbnails")
                if (t != null && t.isNotEmpty()) {
                    val url = (t.last() as? JObj)?.str("url").orEmpty()
                    if (url.isNotEmpty()) return url
                }
                for ((_, v) in value.members) rendererThumbnail(v).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return ""
    }

    // ---- credits.go

    private fun cardArtistBrowseId(r: JObj): String {
        val runs = r.obj("subtitle")?.arr("runs") ?: return ""
        for (run in runs) {
            val id = ((run as? JObj)?.obj("navigationEndpoint")?.obj("browseEndpoint"))?.str("browseId").orEmpty()
            if (id.isNotEmpty()) return id
        }
        return ""
    }

    private fun rendererArtistBrowseId(r: JObj): String {
        for (b in listOf("subtitle", "longBylineText", "shortBylineText")) artistBrowseEndpoint(r[b]).takeIf { it.isNotEmpty() }?.let { return it }
        val cols = r.arr("flexColumns") ?: return ""
        if (cols.size < 2) return ""
        return artistBrowseEndpoint(cols[1])
    }

    private fun rendererCredits(r: JObj): Pair<List<String>, String> {
        val sources = mutableListOf(r["subtitle"], r["longBylineText"], r["shortBylineText"])
        r.arr("flexColumns")?.takeIf { it.size >= 2 }?.let { cols ->
            (cols[1] as? JObj)?.obj("musicResponsiveListItemFlexColumnRenderer")?.let { sources += it["text"] }
        }
        var album = ""
        for (s in sources) {
            val (found, a) = bylineCredits(s)
            if (found.isNotEmpty()) return found to a
            album = a
        }
        return emptyList<String>() to album
    }

    private fun bylineCredits(byline: Any?): Pair<List<String>, String> {
        val o = byline as? JObj ?: return emptyList<String>() to ""
        val artists = ArrayList<String>(); var album = ""
        for (run in o.arr("runs").orEmpty()) {
            val ro = run as? JObj ?: continue
            val text = ro.str("text").orEmpty().trim()
            if (text.isEmpty()) continue
            val id = ro.obj("navigationEndpoint")?.obj("browseEndpoint")?.str("browseId").orEmpty()
            when {
                isArtistId(id) -> artists += text
                isAlbumId(id) && album.isEmpty() -> album = text
            }
        }
        return artists to album
    }

    private fun artistBrowseEndpoint(value: Any?): String {
        when (value) {
            is List<*> -> for (c in value) artistBrowseEndpoint(c).takeIf { it.isNotEmpty() }?.let { return it }
            is JObj -> {
                value.obj("browseEndpoint")?.str("browseId")?.let { if (it.startsWith("UC") || it.contains("privately_owned_artist")) return it }
                for ((_, v) in value.members) artistBrowseEndpoint(v).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return ""
    }

    // ---- browse.go tokens

    /**
     * `pageContinuationToken`: a bare `continuation` string that extends the whole page. A shelf's own continuation
     * stays with its [Section] and is not taken here.
     */
    fun pageContinuationToken(root: Any?): String {
        when (root) {
            is List<*> -> for (c in root) pageContinuationToken(c).takeIf { it.isNotEmpty() }?.let { return it }
            is JObj -> {
                root.str("continuation")?.takeIf { it.isNotEmpty() }?.let { return it }
                for ((k, v) in root.members) {
                    if (isSectionRenderer(k)) continue
                    pageContinuationToken(v).takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }
        return ""
    }

    /** `continuationToken`: the first `continuationCommand.token`, `nextContinuationData.continuation` or bare `continuation` string. */
    fun continuationToken(root: Any?): String {
        when (root) {
            is List<*> -> for (c in root) continuationToken(c).takeIf { it.isNotEmpty() }?.let { return it }
            is JObj -> {
                root.obj("continuationCommand")?.str("token")?.takeIf { it.isNotEmpty() }?.let { return it }
                root.obj("nextContinuationData")?.str("continuation")?.takeIf { it.isNotEmpty() }?.let { return it }
                root.str("continuation")?.takeIf { it.isNotEmpty() }?.let { return it }
                for ((_, v) in root.members) continuationToken(v).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return ""
    }
}
