package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.clockText
import com.muratkirazkaya.meiro.sync.LinksStore
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.screens.PlaylistUi

/** What the stored lists and tracks look like on the library and playlist screens. */

private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)

private fun isUrl(s: String) = s.startsWith("https://") || s.startsWith("http://")

private fun Item.picture() = thumbnail.ifEmpty { albumThumbnail }.takeIf { isUrl(it) }

/** The list's own cover if it has a picture, else its first track's, else the gradient (hue from the list id). */
fun Library.coverSpec() = CoverSpec(
    url = cover.takeIf { isUrl(it) } ?: items.firstNotNullOfOrNull { it.picture() },
    hue = hue(id), spread = 80,
)

private fun Item.isSc() = activeRef().first == Source.SOUNDCLOUD

/** "Meiro · N parça", with ", YT a, SC b" after it when the list holds tracks from both. */
fun Library.metaText(): String {
    val sc = items.count { it.isSc() }
    val yt = items.size - sc
    return "Meiro · ${items.size} parça" + if (yt > 0 && sc > 0) " · YT $yt, SC $sc" else ""
}

fun Library.toRow() = ListRowData(id, name, metaText(), coverSpec())

/** "1 sa 52 dk", "47 dk", "30 sn"; null when no track has a length. */
fun durationText(totalMs: Long): String? {
    val s = totalMs / 1000
    if (s <= 0) return null
    val h = s / 3600; val m = (s % 3600) / 60
    return when {
        h > 0 -> "$h sa $m dk"
        m > 0 -> "$m dk"
        else -> "$s sn"
    }
}

fun Item.toQItem(): QItem {
    val (src, id) = activeRef()
    return QItem(src, id, title, artist, if (durationMs > 0) clockText(durationMs) else "", picture().orEmpty())
}

fun Item.toSongRow(links: LinksStore?, playingKey: String?): SongRowData {
    val (src, id) = activeRef()
    val providers = (sources.map { it.source } + source.ifEmpty { Source.YTM }).toSet()
    val state = links?.song(src, id)?.state?.takeIf { it.isNotEmpty() } ?: state.ifEmpty { LinkState.UNKNOWN }
    return SongRowData(
        key = id,
        title = title.ifEmpty { id },
        subtitle = listOf(artist, if (durationMs > 0) clockText(durationMs) else "").filter { it.isNotEmpty() }.joinToString(" · "),
        ytm = Source.YTM in providers,
        sc = Source.SOUNDCLOUD in providers,
        activeYtm = src != Source.SOUNDCLOUD,
        cover = CoverSpec(picture(), hue(id)),
        link = when (state) { LinkState.MATCHED -> LinkBadge.None; LinkState.OTHER -> LinkBadge.Other; else -> LinkBadge.Unknown },
        now = playingKey != null && id == playingKey,
    )
}

fun Library.toPlaylistUi(links: LinksStore?, playingKey: String?): PlaylistUi {
    val sc = items.count { it.isSc() }
    val rows = items.map { it.toSongRow(links, playingKey) }
    val matched = items.count { item ->
        val (src, id) = item.activeRef()
        (links?.song(src, id)?.state?.takeIf { it.isNotEmpty() } ?: item.state) == LinkState.MATCHED
    }
    return PlaylistUi(
        title = name,
        cover = coverSpec(),
        kind = "Meiro listesi · ${items.size} parça" + (durationText(items.sumOf { it.durationMs })?.let { " · $it" } ?: ""),
        summary = "YT ${items.size - sc} · SC $sc · eşleşen $matched",
        rows = rows,
    )
}
