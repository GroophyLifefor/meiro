package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.musicbrainz.Artists
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.screens.ArtistUi

/** A MusicBrainz artist page: what it shows, the recordings a tap plays and which row each one is, the similar artists' ids. */
class MbArtistData(val ui: ArtistUi, val queue: List<QItem>, private val rowToQueue: List<Int>, val similarIds: List<String>) {
    /** The place in [queue] of popular row [row], or -1 for a recording with nothing to play. */
    fun queueIndex(row: Int): Int = rowToQueue.getOrElse(row) { -1 }
}

/** What the mock's `artist` screen shows for a MusicBrainz artist (M6d, `mbArtistPage`). */
object MbArtistMapping {
    /** The kind line's start: the page is MusicBrainz's ("MusicBrainz ile eşleşti · Fransa · 1993–2021"). */
    const val KIND = "MusicBrainz ile eşleşti"

    /** The meta line of a recording with nothing to play, after its artist and length (the desktop's "No link"). */
    const val NO_LINK = "çalma bağlantısı yok"

    /** The meta line of a similar artist: where the suggestion comes from (the mock's). */
    const val SIMILAR_META = "ListenBrainz"

    private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)

    /** `formatMilliseconds`: "3:42", "1:02:03", "" for an unknown length. */
    fun clock(ms: Int): String {
        if (ms <= 0) return ""
        val t = ms / 1000
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** The kind line: the country's Turkish name and the years, when MusicBrainz names them. */
    fun kind(page: Artists.Page): String {
        val a = page.artist
        return (listOf(KIND) + listOfNotNull(a?.let { Artists.countryName(it.country) }, a?.let { Artists.years(it) }).filter { it.isNotEmpty() }).joinToString(" · ")
    }

    /** `playableRecording`'s item: what plays a recording that has a playable link. */
    fun toQItem(row: Artists.Row): QItem? {
        val p = row.play ?: return null
        val r = row.recording
        return QItem(source = p.source, key = p.key, title = r.title, artist = r.artist, duration = clock(r.lengthMs), sources = listOf(p.source), refs = mapOf(p.source to p.key))
    }

    /**
     * The page: hero name (the row's when the page has none yet; "Sanatçı" otherwise), kind line, "Popüler" = the top
     * recordings ("artist · length", the provider a playable one plays from as its logo and its link state; one with
     * nothing to play faded, "… · çalma bağlantısı yok", no link button, no menu), no albums (MusicBrainz's page has
     * none), "Benzer sanatçılar" = ListenBrainz's. [name] is what the search row showed, for the heading while loading.
     */
    fun page(page: Artists.Page, name: String = "", linkState: (String, String) -> String? = { _, _ -> null }, playingKey: String? = null): MbArtistData {
        val queue = mutableListOf<QItem>()
        val map = mutableListOf<Int>()
        val rows = page.rows.map { row ->
            val r = row.recording
            val q = toQItem(row)
            val sub = listOf(r.artist, clock(r.lengthMs)).filter { it.isNotEmpty() }
            val cover = CoverSpec(null, hue(r.id))
            if (q == null) {
                map += -1
                SongRowData("mb:${r.id}", r.title.ifEmpty { r.id }, (sub + NO_LINK).joinToString(" · "), ytm = false, sc = false, activeYtm = true, cover = cover, dim = true, menu = false)
            } else {
                map += queue.size; queue += q
                val ytm = q.source == Source.YTM
                val state = linkState(q.source, q.key)?.takeIf { it.isNotEmpty() } ?: LinkState.UNKNOWN
                SongRowData(
                    q.key, r.title.ifEmpty { r.id }, sub.joinToString(" · "), ytm = ytm, sc = !ytm, activeYtm = ytm, cover = cover,
                    link = when (state) { LinkState.MATCHED -> LinkBadge.None; LinkState.OTHER -> LinkBadge.Other; else -> LinkBadge.Unknown },
                    now = playingKey != null && playingKey == q.key,
                )
            }
        }
        val ui = ArtistUi(
            name = page.artist?.name?.takeIf { it.isNotBlank() } ?: name.ifBlank { "Sanatçı" },
            cover = CoverSpec(null, hue(page.mbid), 80),
            kind = kind(page),
            popular = rows,
            albums = emptyList(),
            similar = page.similar.map { TileData(it.mbid, it.name, SIMILAR_META, CoverSpec(null, hue(it.mbid), 80), Provider.Meiro) },
        )
        return MbArtistData(ui, queue, map, page.similar.map { it.mbid })
    }
}
