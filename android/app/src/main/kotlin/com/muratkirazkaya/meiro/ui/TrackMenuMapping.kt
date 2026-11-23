package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Match
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.data.SourceRef
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.screens.ListMenuUi
import com.muratkirazkaya.meiro.ui.screens.TrackMenuUi
import com.muratkirazkaya.meiro.youtube.SearchResult
import com.muratkirazkaya.meiro.youtube.TargetKind

/**
 * What a track ⋮ opened on: the track, the list it was opened from (a row of one of the user's lists, which can then
 * take it out), and the album page it was opened on (its id: "Albüme git" goes there without a search).
 */
data class MenuTarget(val item: QItem, val listId: String? = null, val albumId: String? = null, val albumArt: String = "")

/** The track menu, the add-to sheet and the list menu built from the store and the queue's tracks. */
object TrackMenuMapping {
    /** The mock's words right of "MusicBrainz'e bağla": "eşleşti", "diğer", else "unknown". */
    fun linkText(state: String?): String = when (state) {
        LinkState.MATCHED -> "eşleşti"
        LinkState.OTHER -> "diğer"
        else -> "unknown"
    }

    private fun provider(src: String) = if (src == Source.SOUNDCLOUD) Provider.Sc else Provider.Ytm

    private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)

    fun menu(t: MenuTarget, linkState: String?): TrackMenuUi {
        val i = t.item
        val sources = listOf(Source.YTM, Source.SOUNDCLOUD).filter { it in i.providers() }.map(::provider)
        return TrackMenuUi(
            title = i.title, artist = i.artist,
            cover = CoverSpec(i.thumbnail.takeIf { it.startsWith("http") }, hue(i.key)),
            link = linkText(linkState), sources = sources,
            album = t.albumId != null || i.album.isNotBlank(),
            inList = t.listId != null,
        )
    }

    /**
     * `libraryItem`: a queue track as a list keeps it: the provider it plays from and its key (a SoundCloud track's key
     * is its page URL, kept as the URL too), what names it, its length, `unknown` (nothing is linked yet), its picture,
     * the cover of the album page it was added from, and every provider it is held by when there are more than one.
     */
    fun QItem.toListItem(albumArt: String = ""): Item {
        val src = provider()
        val refs = providers().mapNotNull { p ->
            val k = refFor(p) ?: return@mapNotNull null
            if (p == Source.SOUNDCLOUD) SourceRef(p, url = k) else SourceRef(p, id = k)
        }
        return Item(
            source = src, key = key, url = if (src == Source.SOUNDCLOUD) key else "",
            title = title, artist = artist, durationMs = durationMs(), state = LinkState.UNKNOWN,
            thumbnail = thumbnail, albumThumbnail = albumArt,
            sources = if (refs.size > 1) refs.sortedBy { if (it.source == Source.YTM) 0 else 1 } else emptyList(),
        )
    }

    /**
     * The lists a track can be added to (the mock's add-to rows): the user's lists, not the Daily mix (the app makes it
     * again each day), with the cover rule of the library and "N parça".
     */
    fun addToRows(libs: List<Library>, dailyMixId: String): List<ListRowData> {
        val mix = HomeMapping.dailyMix(libs, dailyMixId)?.id
        return libs.filter { it.id != mix }.map { ListRowData(it.id, it.name, "${it.items.size} parça", it.coverSpec()) }
    }

    /** The list menu of one of the user's lists: how many of its tracks are not linked to MusicBrainz. */
    fun listMenu(lib: Library): ListMenuUi = ListMenuUi(lib.items.count { it.state != LinkState.MATCHED && it.state != LinkState.OTHER }, own = true)

    /** The first artist a credit names ("Daft Punk, Pharrell Williams" -> "Daft Punk"). */
    fun firstArtist(credit: String): String =
        credit.split(", ", " & ", " feat. ", " ft. ", " x ", " • ").firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * "Sanatçıya git" without the artist's page id: the YouTube Music search for the artist's name, its first artist
     * whose name is the same once folded, else its first artist at all; null when it names none.
     */
    fun pickArtist(r: SearchResult, name: String): String? {
        val artists = (listOfNotNull(r.top) + r.items).filter { it.target().first == TargetKind.Artist }
        val folded = Match.normalize(name)
        return (artists.firstOrNull { Match.normalize(it.title) == folded } ?: artists.firstOrNull())?.browseId
    }

    /** "Albüme git" without the album's page id: the search for "album artist", the album of that name first. */
    fun pickAlbum(r: SearchResult, album: String): String? {
        val albums = (listOfNotNull(r.top) + r.items).filter { it.target().first == TargetKind.Album }
        val folded = Match.normalize(album)
        return (albums.firstOrNull { Match.normalize(it.title) == folded } ?: albums.firstOrNull())?.browseId
    }

    /** `itemLink`: the SoundCloud page, else the YouTube Music watch page. */
    fun shareLink(i: QItem): String? = when {
        i.provider() == Source.SOUNDCLOUD && i.key.startsWith("http") -> i.key
        i.refFor(Source.YTM) != null -> "https://music.youtube.com/watch?v=${i.refFor(Source.YTM)}"
        else -> null
    }

    /** The provider "Kaynağı değiştir" moves a track to: the other one it is held by, null when it has only one. */
    fun otherSource(i: QItem): String? {
        val other = if (i.provider() == Source.YTM) Source.SOUNDCLOUD else Source.YTM
        return other.takeIf { i.refFor(it) != null }
    }

    /** The mock's name of a provider in a snack ("SoundCloud'da bu şarkı bulunamadı"). */
    fun sourceName(src: String) = if (src == Source.SOUNDCLOUD) "SoundCloud" else "YouTube Music"
}
