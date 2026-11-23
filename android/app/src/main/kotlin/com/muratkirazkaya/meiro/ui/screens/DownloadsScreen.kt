package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.Cover
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.IconButton
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.OfflineBanner
import com.muratkirazkaya.meiro.ui.components.SectionHead
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** The storage summary (`.meter`): the line, the bar's three parts (0..1 of its width) and the legend's three labels. */
data class MeterUi(
    val used: String,
    val sub: String,
    val downloaded: Float,
    val ytCache: Float,
    val scCache: Float,
    val legend: List<String>,
)

/** One row of "İniyor": a list or a track on its way. */
data class PendingRowUi(val id: String, val title: String, val meta: String, val progress: Float, val cover: CoverSpec)

/** What the downloads tab shows. */
data class DownloadsUi(
    val meter: MeterUi,
    val pending: List<PendingRowUi> = emptyList(),
    val rows: List<SongRowData> = emptyList(),
    val offline: Boolean = false,
)

class DownloadsActions(
    val onStopAll: () -> Unit = {},
    val onStop: (String) -> Unit = {},
    val onSort: () -> Unit = {},
    val onRow: (Int) -> Unit = {},
    val onMore: (Int) -> Unit = {},
    val onLink: (Int) -> Unit = {},
)

/** The note under the downloads (mock's text). */
const val DOWNLOADS_NOTE = "İndirilenler önbellek temizliğinden etkilenmez. Çevrimdışıyken çalma önce buradan başlar."

/** The legend's dot colours: primary for downloads, YouTube red and SoundCloud orange for the two caches (the mock's). */
private val YtRed = Color(0xFFFF0033)
private val ScOrange = Color(0xFFFF5500)

/**
 * Mock screen `downloads` (`V.downloads`): top bar "İndirilenler" with the avatar, the offline banner while offline, the
 * storage summary (used and free space, a bar of downloads and both caches, the legend), "İniyor" with "Hepsini
 * durdur" and a row per list or track on its way (progress, ×), "Şarkılar" with "Sırala" and the downloaded tracks
 * ("artist · size"), and the note. A section with nothing in it is left out.
 */
@Composable
fun DownloadsScreen(ui: DownloadsUi, playing: Boolean, actions: DownloadsActions = DownloadsActions(), state: LazyListState = rememberLazyListState()) {
    val c = Meiro.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("İndirilenler")
        LazyColumn(Modifier.fillMaxSize(), state) {
            if (ui.offline) item(key = "banner") { OfflineBanner(bottom = 4.dp) }
            item(key = "meter") { Meter(ui.meter) }
            if (ui.pending.isNotEmpty()) {
                item(key = "pending.head") { SectionHead("İniyor", "pending.title", "Hepsini durdur", "pending.stopBtn", actions.onStopAll) }
                itemsIndexed(ui.pending, key = { _, r -> "p/" + r.id }) { i, r -> PendingRow("batch${i + 1}", r) { actions.onStop(r.id) } }
            }
            if (ui.rows.isNotEmpty()) {
                item(key = "songs.head") { SectionHead("Şarkılar", "songs.title", "Sırala", "songs.sortBtn", actions.onSort) }
                itemsIndexed(ui.rows, key = { _, r -> "s/" + r.key }) { i, r ->
                    SongRow("song${i + 1}", r, playing, { actions.onRow(i) }, onMore = { actions.onMore(i) }, onLink = { actions.onLink(i) })
                }
            }
            item(key = "note") {
                MText(DOWNLOADS_NOTE, MeiroType.Note, c.onSurfaceVariant, "note", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp))
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The mock's `.meter`: 4/16/8dp margins, 16dp padding, 20dp radius, surface-container-low. */
@Composable
private fun Meter(m: MeterUi) {
    val c = Meiro.colors
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).fillMaxWidth()
            .inv("meter", bg = c.surfaceContainerLow, radiusDp = 20f).background(c.surfaceContainerLow, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        // `<b>2,1 GB</b> <span class="sub">…</span>` on one line; the space between them is 4.5dp in the mock's font. The
        // two sizes on one baseline make Chrome's line box 21px, not 20.
        Row(Modifier.heightIn(min = 21.dp), verticalAlignment = Alignment.CenterVertically) {
            MText(m.used, MeiroType.MeterUsed, c.onSurface, "meter.used", maxLines = 1)
            MText(m.sub, MeiroType.MeterSub, c.onSurfaceVariant, "meter.sub", Modifier.padding(start = 4.5.dp).weight(1f, fill = false), maxLines = 1)
        }
        // `.bar`: 8dp, 4dp radius, surface-container-highest, 10dp above and 8dp below; each part is its share of the
        // whole bar's width.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(top = 10.dp, bottom = 8.dp).fillMaxWidth()) {
            val whole = maxWidth
            Row(
                Modifier.fillMaxWidth().height(8.dp)
                    .inv("meter.bar", bg = c.surfaceContainerHighest, radiusDp = 4f).clip(RoundedCornerShape(4.dp)).background(c.surfaceContainerHighest),
            ) {
                BarPart("meter.bar.dl", whole * m.downloaded.coerceIn(0f, 1f), c.primary)
                BarPart("meter.bar.yt", whole * m.ytCache.coerceIn(0f, 1f), YtRed)
                BarPart("meter.bar.sc", whole * m.scCache.coerceIn(0f, 1f), ScOrange)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val dots = listOf(c.primary, YtRed, ScOrange)
            m.legend.forEachIndexed { i, text ->
                val k = "legend${i + 1}"
                Row(Modifier.inv(k), verticalAlignment = Alignment.CenterVertically) {
                    // `::before`: an 8dp dot and 6dp before the text, sitting on the text's baseline.
                    Box(Modifier.padding(top = 1.dp, end = 6.dp).size(8.dp).inv("$k.dot", bg = dots.getOrElse(i) { c.primary }, radiusDp = 4f).clip(CircleShape).background(dots.getOrElse(i) { c.primary }))
                    MText(text, MeiroType.Legend, c.onSurfaceVariant, "$k.label", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BarPart(key: String, width: androidx.compose.ui.unit.Dp, color: Color) {
    if (width <= 0.dp) return
    Box(Modifier.width(width).fillMaxHeight().inv(key, bg = color, radiusDp = 0f).background(color))
}

/**
 * A row of "İniyor" (the mock's `.row` with `.prog`): cover, the list's or track's name, "12 / 30 şarkı" or the track's
 * state, the progress bar (4dp, 2dp radius) and × that stops it.
 */
@Composable
private fun PendingRow(p: String, r: PendingRowUi, onStop: () -> Unit) {
    val c = Meiro.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        Cover(r.cover, 8.dp, "$p.cover", Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            MText(r.title, MeiroType.ListTitle, c.onSurface, "$p.title", Modifier.fillMaxWidth(), maxLines = 1)
            MText(r.meta, MeiroType.ListMeta, c.onSurfaceVariant, "$p.meta", maxLines = 1)
            Box(
                Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp)
                    .inv("$p.prog", bg = c.surfaceContainerHighest, radiusDp = 2f).clip(RoundedCornerShape(2.dp)).background(c.surfaceContainerHighest),
            ) {
                val f = r.progress.coerceIn(0f, 1f)
                if (f > 0f) Box(Modifier.fillMaxWidth(f).fillMaxHeight().inv("$p.prog.fill", bg = c.primary, radiusDp = 0f).background(c.primary))
            }
        }
        IconButton("$p.closeBtn", "$p.close.icon", MeiroIcons.Close, c.onSurfaceVariant, onStop)
    }
}
