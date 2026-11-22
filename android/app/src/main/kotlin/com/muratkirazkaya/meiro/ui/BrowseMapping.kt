package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.parseDurationMs
import com.muratkirazkaya.meiro.ui.SearchMapping.toQItem
import com.muratkirazkaya.meiro.ui.SearchMapping.toSongRow
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.screens.AlbumUi
import com.muratkirazkaya.meiro.ui.screens.ArtistUi
import com.muratkirazkaya.meiro.youtube.BrowsePage
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.TargetKind

/** An album page: what it shows, the tracks a tap plays (in page order) and the album's own picture. */
class AlbumData(val ui: AlbumUi, val tracks: List<MusicItem>, val cover: String) {
    val queue: List<QItem> get() = tracks.map { it.toQItem() }

    /** `duplicateAsMeiro` (`librarypage.go:164`): the tracks as list items, each `unknown`, with the album's picture. */
    fun listItems(): List<Item> = tracks.map { t ->
        Item(
            source = Source.YTM, key = t.videoId, title = t.title, artist = t.artists.ifEmpty { t.artist.ifEmpty { ui.artist() } },
            durationMs = parseDurationMs(t.duration), state = LinkState.UNKNOWN, thumbnail = t.thumbnail, albumThumbnail = cover,
        )
    }

    private fun AlbumUi.artist() = kind.split(" · ").getOrNull(1).orEmpty()
}

/** An artist page: what it shows, the popular tracks a tap plays, and the browse ids the shelves open. */
class ArtistData(val ui: ArtistUi, val tracks: List<MusicItem>, val albumIds: List<String>, val artistIds: List<String>) {
    val queue: List<QItem> get() = tracks.map { it.toQItem() }
}

/** What the album and artist pages show for a YouTube Music browse page (`V.album`, `V.artist`). */
object BrowseMapping {
    /** The text under an artist's name: the page comes from YouTube Music; a MusicBrainz link is M6. */
    const val ARTIST_KIND = "YouTube Music sanatçısı"

    /** The meta line of a similar artist: where the suggestion comes from (the mock's "ListenBrainz"). */
    const val SIMILAR_META = "YouTube Music"

    private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)
    private fun url(s: String) = s.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    /** YouTube Music's kind words (hl=en) as the mock writes them. */
    private val kindWords = mapOf("Album" to "Albüm", "Single" to "Single", "EP" to "EP", "Audiobook" to "Sesli kitap")

    /**
     * "Albüm · Daft Punk · 2013 · 13 parça" from the header's "Album • 2013" and its strapline "Daft Punk", and the
     * number of tracks the page lists.
     */
    fun albumKind(page: BrowsePage, tracks: Int): String {
        val parts = page.subtitle.split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
        val kind = parts.firstOrNull()?.let { kindWords[it] ?: it }
        val rest = if (kind != null) parts.drop(1) else parts
        return (listOfNotNull(kind, page.strapline.trim().ifEmpty { null }) + rest + listOfNotNull(if (tracks > 0) "$tracks parça" else null))
            .joinToString(" · ")
    }

    /**
     * The album page. Every row shows the album's artist and the track's length (the mock's "Daft Punk · 5:37", also
     * for a track that credits a guest), the album's picture (the tracks have none), and its number on the album.
     */
    fun album(id: String, page: BrowsePage, linkState: (String, String) -> String? = { _, _ -> null }, playingKey: String? = null): AlbumData {
        val art = url(page.thumbnail).orEmpty()
        val tracks = page.tracks(art)
        val byline = page.strapline.trim()
        val rows = tracks.map { t ->
            val r = t.toSongRow(linkState, playingKey)
            val who = byline.ifEmpty { t.artists.ifEmpty { SearchMapping.bylineText(t) } }
            r.copy(subtitle = listOf(who, t.duration).filter { it.isNotEmpty() }.joinToString(" · "), cover = CoverSpec(url(t.thumbnail), hue(id)))
        }
        val ui = AlbumUi(
            title = page.title, cover = CoverSpec(art.ifEmpty { null }, hue(id), 80), kind = albumKind(page, tracks.size),
            rows = rows, nums = tracks.mapIndexed { i, t -> t.index.ifEmpty { "${i + 1}" } },
        )
        return AlbumData(ui, tracks, art)
    }

    /**
     * The artist page: "Popüler" = YouTube Music's "Top songs", "Albümler" = its "Albums" (meta: the year), "Benzer
     * sanatçılar" = its "Fans might also like". The picture is the page's square one (its header's is a wide banner).
     */
    fun artist(id: String, page: BrowsePage, linkState: (String, String) -> String? = { _, _ -> null }, playingKey: String? = null): ArtistData {
        val top = page.section("Top songs")?.items.orEmpty().filter { SearchMapping.isSong(it) }
        // Songs of an artist's page name the artists they credit; one that names none is the page's own artist's.
        val tracks = top.map { if (it.artists.isEmpty()) it.copy(artist = page.title, artists = page.title) else it }
        val albums = page.section("Albums")?.items.orEmpty().filter { it.target().first == TargetKind.Album }
        val similar = page.section("Fans might also like")?.items.orEmpty().filter { it.target().first == TargetKind.Artist }
        val ui = ArtistUi(
            name = page.title,
            cover = CoverSpec(url(page.square.ifEmpty { page.thumbnail }), hue(id)),
            kind = ARTIST_KIND,
            popular = tracks.map { it.toSongRow(linkState, playingKey) },
            albums = albums.map { a -> TileData(a.browseId, a.title, SearchMapping.bylineText(a), CoverSpec(url(a.thumbnail), hue(a.browseId), 80), Provider.Ytm) },
            similar = similar.map { a -> TileData(a.browseId, a.title, SIMILAR_META, CoverSpec(url(a.thumbnail), hue(a.browseId), 80), Provider.Ytm) },
        )
        return ArtistData(ui, tracks, albums.map { it.browseId }, similar.map { it.browseId })
    }
}
