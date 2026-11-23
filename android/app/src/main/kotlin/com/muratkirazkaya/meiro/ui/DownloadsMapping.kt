package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.downloads.AudioCache
import com.muratkirazkaya.meiro.downloads.DownloadBatch
import com.muratkirazkaya.meiro.downloads.DownloadItem
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.sizeText
import com.muratkirazkaya.meiro.ui.screens.DownloadsUi
import com.muratkirazkaya.meiro.ui.screens.MeterUi
import com.muratkirazkaya.meiro.ui.screens.PendingRowUi

/** How the downloads are sorted ("Sırala" goes round them). */
enum class DownloadSort(val label: String) { Newest("en yeni"), Title("ada göre"), Size("boyuta göre") }

/** The downloads tab built from the store, the caches and the queue. */
object DownloadsMapping {
    /** The space the bar stands for: the downloads plus what both playback caches may hold, or what is used if more. */
    fun meter(downloadBytes: Long, downloadCount: Int, ytBytes: Long, scBytes: Long, freeBytes: Long, cacheLimit: Long = AudioCache.DEFAULT_LIMIT_BYTES): MeterUi {
        val used = downloadBytes + ytBytes + scBytes
        val scale = maxOf(downloadBytes + 2 * cacheLimit, used, 1L).toFloat()
        return MeterUi(
            used = sizeText(used),
            sub = "kullanılıyor · telefonda ${sizeText(freeBytes)} boş",
            downloaded = downloadBytes / scale, ytCache = ytBytes / scale, scCache = scBytes / scale,
            legend = listOf("İndirilen ${sizeText(downloadBytes)} · $downloadCount", "YT önbellek ${sizeText(ytBytes)}", "SC önbellek ${sizeText(scBytes)}"),
        )
    }

    /** A row of "İniyor": a list says how many of its tracks are kept, a single track its percent, a waiting one "sırada". */
    fun pending(b: DownloadBatch): PendingRowUi {
        val meta = if (b.single) {
            val state = if (b.running > 0) "%${(b.progress * 100).toInt()}" else "sırada"
            listOf(b.artist, state).filter { it.isNotEmpty() }.joinToString(" · ")
        } else {
            listOfNotNull(
                "${b.done} / ${b.total} şarkı",
                if (b.failed > 0) "${b.failed} indirilemedi" else null,
                if (b.running == 0 && b.done + b.failed < b.total) "sırada" else null,
            ).joinToString(" · ")
        }
        return PendingRowUi(b.id, b.name, meta, b.progress, CoverSpec(b.cover.takeIf { it.startsWith("http") }, hue(b.id + b.name)))
    }

    fun sorted(items: List<DownloadItem>, sort: DownloadSort): List<DownloadItem> = when (sort) {
        DownloadSort.Newest -> items.sortedByDescending { it.downloadedAt }
        DownloadSort.Title -> items.sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale.forLanguageTag("tr"))) { it.title })
        DownloadSort.Size -> items.sortedByDescending { it.sizeBytes }
    }

    /** A downloaded track's row: its providers, the downloaded mark, "artist · size", the link button unless linked. */
    fun row(d: DownloadItem, linkState: String?, playingKey: String?): SongRowData {
        val provs = d.refs.keys + d.source
        return SongRowData(
            key = d.key,
            title = d.title.ifEmpty { d.key },
            subtitle = listOf(d.artist, sizeText(d.sizeBytes)).filter { it.isNotEmpty() }.joinToString(" · "),
            ytm = Source.YTM in provs, sc = Source.SOUNDCLOUD in provs, activeYtm = d.source != Source.SOUNDCLOUD,
            cover = CoverSpec(d.thumbnail.takeIf { it.startsWith("http") }, hue(d.key)),
            downloaded = true,
            link = when (linkState) { LinkState.MATCHED -> LinkBadge.None; LinkState.OTHER -> LinkBadge.Other; else -> LinkBadge.Unknown },
            now = d.key == playingKey,
        )
    }

    fun build(
        items: List<DownloadItem>, batches: List<DownloadBatch>, meter: MeterUi, linkState: (String, String) -> String?,
        playingKey: String?, offline: Boolean,
    ) = DownloadsUi(meter, batches.map(::pending), items.map { row(it, linkState(it.source, it.key), playingKey) }, offline)

    private fun hue(s: String) = (s.hashCode() and 0x7fffffff) % 360
}
