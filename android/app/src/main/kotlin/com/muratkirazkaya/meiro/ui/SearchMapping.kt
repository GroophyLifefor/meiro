package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.parseDurationMs
import com.muratkirazkaya.meiro.search.SearchMerge
import com.muratkirazkaya.meiro.search.SearchState
import com.muratkirazkaya.meiro.ui.components.ArtistRowData
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.screens.SearchChip
import com.muratkirazkaya.meiro.ui.screens.SearchUi
import com.muratkirazkaya.meiro.youtube.ItemKind
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.TargetKind

/** What the search screen shows for a [SearchState]: the providers' answers sorted into the mock's sections. */
object SearchMapping {
    /** "Hepsi" shows this many artists and tracks; the "Sanatçılar" and "Şarkılar" chips show all of them. */
    const val ALL_ARTISTS = 3
    const val ALL_SONGS = 8

    /** The mock's offline note, over results that came from this device. */
    const val LOCAL_NOTE = "Ağ yok: sonuçlar bu cihazdan, indirilenlerden ve önbellekten."

    /** The mock's error line when YouTube Music did not answer; without its first sentence when SoundCloud was not asked (a filter). */
    const val YTM_ERROR = "SoundCloud sonuçları aşağıda. Biraz sonra tekrar denenecek."
    const val YTM_ERROR_NO_SC = "Biraz sonra tekrar denenecek."

    /** The kind words YouTube Music (hl=en) puts first in a result's subtitle. */
    private val kindWords = setOf("Song", "Video", "Album", "Single", "EP", "Artist", "Playlist", "Episode", "Podcast", "Profile")

    private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)
    private fun url(s: String) = s.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    private fun firstWord(item: MusicItem) = item.subtitle.substringBefore(" • ").trim()

    fun isArtist(i: MusicItem) = i.target().first == TargetKind.Artist && firstWord(i) != "Profile"
    fun isAlbum(i: MusicItem) = i.target().first == TargetKind.Album
    fun isList(i: MusicItem) = i.target().first == TargetKind.Playlist
    fun isSong(i: MusicItem) = i.isTrack() && i.kind != ItemKind.Episode && firstWord(i) != "Episode"

    /** The YouTube Music rows of the answer, the lead card first. */
    private fun ytmRows(s: SearchState) = if (!s.ytmOn) emptyList() else listOfNotNull(s.top) + s.ytm.items

    /** The tracks the Şarkılar section lists, in its order (also the queue a tap plays). */
    fun songs(s: SearchState): List<MusicItem> {
        s.local?.let { return it }
        val ytm = ytmRows(s).filter { isSong(it) }.distinctBy { it.key() }
        val sc = if (s.scOn) s.sc.items.filter { it.isTrack() } else emptyList()
        val all = SearchMerge.mergeProviders(ytm, sc)
        return if (s.chip == SearchChip.All) all.take(ALL_SONGS) else all
    }

    fun toUi(
        s: SearchState, linkState: (String, String) -> String? = { _, _ -> null }, playingKey: String? = null,
        /** An artist's MusicBrainz state by name ("" or null: not linked): a linked artist has no link button. */
        artistState: (String) -> String? = { null },
    ): SearchUi {
        val base = SearchUi(query = s.field, results = s.submitted.isNotEmpty(), recent = s.recent, chip = s.chip, ytmOn = s.ytmOn, scOn = s.scOn)
        if (s.submitted.isEmpty()) return base
        if (s.local != null) {
            return base.copy(note = LOCAL_NOTE, songs = s.local.map { it.toSongRow(linkState, playingKey) })
        }
        if (s.ytm.failed && s.ytmOn) {
            // The mock's `error`: YouTube Music did not answer, SoundCloud's tracks follow (artists, albums and lists
            // come from YouTube Music only, so there are none).
            return base.copy(ytmError = if (s.scAsked) YTM_ERROR else YTM_ERROR_NO_SC, songs = songs(s).map { it.toSongRow(linkState, playingKey) })
        }
        val rows = ytmRows(s)
        val chip = s.chip
        fun shows(c: SearchChip) = chip == SearchChip.All || chip == c
        val artists = if (shows(SearchChip.Artists)) rows.filter { isArtist(it) }.distinctBy { it.browseId }.let { if (chip == SearchChip.All) it.take(ALL_ARTISTS) else it } else emptyList()
        val albums = if (shows(SearchChip.Albums)) rows.filter { isAlbum(it) }.distinctBy { it.browseId } else emptyList()
        val lists = if (shows(SearchChip.Lists)) rows.filter { isList(it) }.distinctBy { it.target().second } else emptyList()
        val songs = if (shows(SearchChip.Songs)) songs(s) else emptyList()
        return base.copy(
            artists = artists.map { a ->
                ArtistRowData(a.browseId, a.title, "YouTube Music sanatçısı", CoverSpec(url(a.thumbnail), hue(a.browseId)), Provider.Ytm, link = artistState(a.title) != LinkState.MATCHED)
            },
            songs = songs.map { it.toSongRow(linkState, playingKey) },
            albums = albums.map { a -> TileData(a.browseId, a.title, bylineText(a), CoverSpec(url(a.thumbnail), hue(a.browseId), 80)) },
            lists = lists.map { l ->
                val id = l.target().second
                ListRowData(id, l.title, "YouTube Music listesi", CoverSpec(url(l.thumbnail), hue(id), 80), Provider.Ytm)
            },
        )
    }

    /** "Daft Punk · 2013" from "Album • Daft Punk • 2013": the kind word dropped, the parts joined as the mock joins them. */
    fun bylineText(i: MusicItem): String {
        val parts = i.subtitle.split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
        return (if (parts.firstOrNull()?.let { it in kindWords } == true) parts.drop(1) else parts).joinToString(" · ")
    }

    /** The artists a track credits, else its byline without the kind word; then the length: "Daft Punk, Pharrell Williams · 4:08". */
    fun songSubtitle(i: MusicItem): String {
        val who = i.artists.ifEmpty { bylineText(i) }
        return listOf(who, i.duration).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    private fun MusicItem.hasYtm() = videoId.isNotEmpty() && (source == Source.YTM || sources.any { it.source == Source.YTM } || source.isEmpty())
    private fun MusicItem.hasSc() = source == Source.SOUNDCLOUD || sources.any { it.source == Source.SOUNDCLOUD }

    /** The provider a track plays from: YouTube Music when it has it (the default), else SoundCloud. */
    private fun MusicItem.playsYtm() = hasYtm()

    private fun MusicItem.scUrl() = if (source == Source.SOUNDCLOUD) url else sources.firstOrNull { it.source == Source.SOUNDCLOUD }?.url.orEmpty()

    fun MusicItem.toSongRow(linkState: (String, String) -> String?, playingKey: String?): SongRowData {
        val ytm = hasYtm(); val sc = hasSc()
        val active = if (playsYtm()) Source.YTM to videoId else Source.SOUNDCLOUD to scUrl()
        val state = linkState(active.first, active.second)?.takeIf { it.isNotEmpty() } ?: LinkState.UNKNOWN
        val ms = parseDurationMs(duration)
        return SongRowData(
            key = key(),
            title = title.ifEmpty { key() },
            subtitle = songSubtitle(this),
            ytm = ytm, sc = sc, activeYtm = playsYtm(),
            cover = CoverSpec(url(thumbnail), hue(key())),
            link = when (state) { LinkState.MATCHED -> LinkBadge.None; LinkState.OTHER -> LinkBadge.Other; else -> LinkBadge.Unknown },
            now = playingKey != null && (playingKey == videoId || playingKey == scUrl()),
            preview = !ytm && sc && ms in 29_000..31_000,
        )
    }

    fun MusicItem.toQItem(): QItem {
        val ytm = playsYtm()
        val providers = buildList { if (hasYtm()) add(Source.YTM); if (hasSc()) add(Source.SOUNDCLOUD) }
        return QItem(
            source = if (ytm) Source.YTM else Source.SOUNDCLOUD,
            key = if (ytm) videoId else scUrl(),
            title = title, artist = artists.ifEmpty { artist.ifEmpty { bylineText(this) } }, duration = duration,
            thumbnail = url(thumbnail).orEmpty(), sources = providers, album = album,
            refs = buildMap { if (hasYtm()) put(Source.YTM, videoId); if (hasSc() && scUrl().isNotEmpty()) put(Source.SOUNDCLOUD, scUrl()) },
        )
    }
}
