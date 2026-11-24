package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.parseDurationMs
import com.muratkirazkaya.meiro.sync.LinksStore
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.screens.PlaylistUi
import com.muratkirazkaya.meiro.youtube.BrowsePage
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.YtmLibrary
import com.muratkirazkaya.meiro.youtube.YtmLibraryParse
import com.muratkirazkaya.meiro.youtube.YtmListEntry
import java.util.Locale

/**
 * M6g: what the signed-in YouTube Music account shows (the mock with `st.ytm`): Home's "Beğenilenler" quick pick and
 * the "YouTube Music: Yeni çıkanlar" shelf, the Library's "YouTube Music" tab, and a list of the account opened on the
 * playlist screen.
 */
object YtmMapping {
    /** A row or tile key of a playlist of the account ("ytm:pl:<id>", the liked songs are "ytm:pl:LM"). */
    const val PLAYLIST_PREFIX = "ytm:pl:"
    /** A row key of a saved album ("ytm:al:<browse id>"). */
    const val ALBUM_PREFIX = "ytm:al:"
    const val LIKED_TITLE = "Beğenilenler"

    private val Turkish = Locale.forLanguageTag("tr")
    private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)
    private fun url(s: String) = s.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    private val kindWords = mapOf("Album" to "Albüm", "Single" to "Single", "EP" to "EP")

    /** 1204 → "1.204" (Turkish digit grouping, as the mock writes it). */
    fun count(n: Int): String = String.format(Turkish, "%,d", n)

    private fun YtmListEntry.cover() = CoverSpec(url(thumbnail), hue(id), 80)

    /** Home's quick pick of the liked songs: "Beğenilenler", "YouTube Music · 1.204". */
    fun likedTile(liked: YtmListEntry): TileData =
        TileData(PLAYLIST_PREFIX + liked.id, LIKED_TITLE, "YouTube Music" + (liked.count?.let { " · ${count(it)}" } ?: ""), liked.cover(), Provider.Ytm)

    /** The quick picks with the liked songs second, as the mock places them (first when there are no other picks). */
    fun picks(meiro: List<TileData>, liked: YtmListEntry?): List<TileData> {
        if (liked == null) return meiro
        val at = minOf(1, meiro.size)
        return meiro.take(at) + likedTile(liked) + meiro.drop(at)
    }

    /** A tile of the new releases shelf: the album, "Daft Punk · Albüm". */
    fun releaseTile(a: MusicItem): TileData {
        val kind = kindWords[YtmLibraryParse.albumKind(a)] ?: "Albüm"
        val who = YtmLibraryParse.albumArtist(a)
        return TileData(a.browseId, a.title, listOf(who, kind).filter { it.isNotEmpty() }.joinToString(" · "), CoverSpec(url(a.thumbnail), hue(a.browseId), 80), Provider.Ytm)
    }

    /**
     * The Library's "YouTube Music" tab: "Beğenilenler" ("YouTube Music · 1.204 parça"), each playlist ("YouTube Music
     * listesi · 52") and each saved album ("Hidra · Albüm").
     */
    fun libraryRows(lib: YtmLibrary?): List<ListRowData> {
        if (lib == null) return emptyList()
        val rows = ArrayList<ListRowData>()
        lib.liked?.let { l -> rows += ListRowData(PLAYLIST_PREFIX + l.id, LIKED_TITLE, "YouTube Music" + (l.count?.let { " · ${count(it)} parça" } ?: ""), l.cover(), Provider.Ytm) }
        for (p in lib.playlists) rows += ListRowData(PLAYLIST_PREFIX + p.id, p.title, "YouTube Music listesi" + (p.count?.let { " · ${count(it)}" } ?: ""), p.cover(), Provider.Ytm)
        for (a in lib.albums) rows += ListRowData(ALBUM_PREFIX + a.id, a.title, listOf(a.artist, "Albüm").filter { it.isNotEmpty() }.joinToString(" · "), a.cover(), Provider.Ytm)
        return rows
    }

    /** The library entry a row key names, or null. */
    fun entry(lib: YtmLibrary?, key: String): YtmListEntry? {
        lib ?: return null
        return when {
            key.startsWith(PLAYLIST_PREFIX) -> key.removePrefix(PLAYLIST_PREFIX).let { id -> (listOfNotNull(lib.liked) + lib.playlists).firstOrNull { it.id == id } }
            key.startsWith(ALBUM_PREFIX) -> key.removePrefix(ALBUM_PREFIX).let { id -> lib.albums.firstOrNull { it.id == id } }
            else -> null
        }
    }

    /** The name a list of the account goes by in the app ("Beğenilenler" for the liked songs). */
    fun listName(id: String, title: String) = if (id == YtmLibraryParse.LIKED_ID) LIKED_TITLE else title

    /**
     * A playlist of the account as a list the playlist screen, the menus and the downloads take (not stored): its
     * tracks as `unknown` YouTube Music items (`duplicateAsMeiro`), the page's picture as the cover.
     */
    fun asList(id: String, page: BrowsePage): Library {
        val cover = url(page.thumbnail).orEmpty()
        val items = page.tracks().filter { it.videoId.isNotEmpty() }.distinctBy { it.videoId }.map { t ->
            Item(
                source = Source.YTM, key = t.videoId, title = t.title, artist = t.artists.ifEmpty { t.artist },
                durationMs = parseDurationMs(t.duration), state = LinkState.UNKNOWN, thumbnail = t.thumbnail,
            )
        }
        return Library(id = PLAYLIST_PREFIX + id, name = listName(id, page.title), cover = cover, items = items)
    }

    /**
     * The playlist screen for a list of the account: the YouTube Music logo and "YouTube Music listesi · 52 parça ·
     * 3 sa 1 dk" ("YouTube Music · 1.204 parça · …" for the liked songs).
     */
    fun playlistUi(lib: Library, links: LinksStore?, playingKey: String?): PlaylistUi {
        val base = lib.toPlaylistUi(links, playingKey)
        val liked = lib.id == PLAYLIST_PREFIX + YtmLibraryParse.LIKED_ID
        val kind = (if (liked) "YouTube Music" else "YouTube Music listesi") + " · ${count(lib.items.size)} parça" +
            (durationText(lib.items.sumOf { it.durationMs })?.let { " · $it" } ?: "")
        return base.copy(kind = kind, provider = Provider.Ytm)
    }
}
