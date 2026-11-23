package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.MIcon
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.clickableNoIndication
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/**
 * What the lyrics tab (`SHEET.qsheet`, tab `lyrics`) shows. [lines] are the timed lines when [times] is given (the
 * "Senkron" view, one time per line), else the plain text line by line ("Düz", or lyrics that have no times). [timed]
 * says whether the track has timed lines at all (then the two chips are shown). [source] is "Kaynak: LRCLIB" and the
 * like; [message] stands in for the lyrics when there are none, or they could not be loaded. Everything empty: loading.
 */
data class LyricsUi(
    val lines: List<String> = emptyList(),
    val times: List<Long>? = null,
    val timed: Boolean = false,
    val source: String = "",
    val message: String? = null,
)

/** What the related tab (`SHEET.qsheet`, tab `related`) shows: the note, then the tracks, or a [message] in their place. */
data class RelatedUi(val rows: List<SongRowData> = emptyList(), val message: String? = null)

/** The mock's `.note` over the related tracks, word for word. */
const val RELATED_NOTE = "Çalan şarkıya benzer. YouTube Music ve SoundCloud'dan, sonra MusicBrainz ile eşleştirilir."

/** The line being sung at [positionMs] (`lrclib.IndexAt`): the last one that has started, -1 before the first. */
fun lyricIndexAt(times: List<Long>, positionMs: Long): Int {
    var lo = 0; var hi = times.size
    while (lo < hi) { val mid = (lo + hi) ushr 1; if (times[mid] > positionMs) hi = mid else lo = mid + 1 }
    return lo - 1
}

/**
 * The lyrics tab: `.lyrsrc` (the "Senkron"/"Düz" chips when the lines are timed, "Kaynak: …" at the end) and `.lyrics`.
 * Synced, the line being sung has the primary colour, the ones before it 60% and the ones after 35% (the mock's
 * `.on`, `.past` and plain `p`), the list keeps two sung lines above it as it moves on, and a tap on a line seeks
 * there (the desktop's `lyricLine`). The chips row stays put while the lines scroll.
 */
@Composable
fun LyricsTab(ui: LyricsUi, positionMs: Long, actions: UpNextActions, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    val times = ui.times
    // Other lyrics, or the other view of them, start from the top: their lines are not the ones scrolled past.
    val state = remember(ui.lines, times == null) { LazyListState() }
    val now = if (times != null) lyricIndexAt(times, positionMs) else -1
    Column(modifier.fillMaxWidth()) {
        if (ui.timed || ui.source.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                if (ui.timed) {
                    SmallChip("q.lyr.synced", "Senkron", times != null) { actions.onLyricsSynced(true) }
                    SmallChip("q.lyr.plain", "Düz", times == null) { actions.onLyricsSynced(false) }
                }
                Box(Modifier.weight(1f), Alignment.CenterEnd) {
                    if (ui.source.isNotEmpty()) MText(ui.source, MeiroType.LyricSource, c.onSurfaceVariant, "q.lyr.source", maxLines = 1)
                }
            }
        }
        ui.message?.let { MText(it, MeiroType.Note, c.onSurfaceVariant, "q.lyr.message", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) }
        // The first placement jumps, the rest glide (the desktop's `settled`).
        val placed = remember(times) { booleanArrayOf(false) }
        LaunchedEffect(now, times) {
            if (times == null || now < 0) return@LaunchedEffect
            val target = maxOf(0, now - 2)
            if (placed[0]) state.animateScrollToItem(target) else state.scrollToItem(target)
            placed[0] = true
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = state, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 40.dp)) {
            var n = 0
            val keys = ui.lines.map { l -> if (times == null && l.isBlank()) null else "q.lyr.line${++n}" }
            itemsIndexed(ui.lines) { i, line ->
                val key = keys[i]
                if (key == null) { Spacer(Modifier.height(14.dp)); return@itemsIndexed }
                val color = if (times != null && i == now) c.primary else c.onSurface
                val alpha = when { times == null || i == now -> 1f; i < now -> 0.6f; else -> 0.35f }
                MText(
                    line.ifBlank { "♪" }, MeiroType.Lyric, color, key,
                    Modifier.fillMaxWidth().padding(bottom = 14.dp).alpha(alpha)
                        .then(if (times != null) Modifier.clickableNoIndication { actions.onLyricSeek(times[i]) } else Modifier),
                )
            }
        }
    }
}

/** The mock's small `.chip` (24dp, 8dp radius, 12px): selected, the secondary container with the check glyph; else a 1dp outline. */
@Composable
private fun SmallChip(key: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.height(24.dp).inv(key, bg = if (selected) c.secondaryContainer else null, radiusDp = 8f).clip(shape)
            .then(if (selected) Modifier.background(c.secondaryContainer) else Modifier.border(1.dp, c.outlineVariant, shape))
            // 10dp padding plus the 1dp border, which CSS adds outside the padding (also on the selected chip, transparent).
            .clickableNoIndication(onClick).padding(horizontal = 11.dp),
        Arrangement.spacedBy(4.dp), Alignment.CenterVertically,
    ) {
        val fg = if (selected) c.onSecondaryContainer else c.onSurfaceVariant
        if (selected) MIcon(MeiroIcons.Check, fg, "$key.icon", size = 14)
        MText(label, MeiroType.LyricChip, fg, "$key.label", maxLines = 1)
    }
}

/** The related tab: the mock's note, then the related tracks as the mock's song rows (a tap plays from that row). */
@Composable
fun RelatedTab(ui: RelatedUi, playing: Boolean, actions: UpNextActions, modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState()) {
    val c = Meiro.colors
    LazyColumn(modifier.fillMaxWidth(), state = state) {
        item("note") { MText(RELATED_NOTE, MeiroType.Note, c.onSurfaceVariant, "q.rel.note", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) }
        ui.message?.let { m -> item("message") { MText(m, MeiroType.Note, c.onSurfaceVariant, "q.rel.message", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) } }
        itemsIndexed(ui.rows, key = { i, r -> "$i:${r.key}" }) { i, r -> SongRow("q.rel${i + 1}", r, playing, { actions.onRelated(i) }, onMore = { actions.onRelatedMore(i) }, onLink = { actions.onRelatedLink(i) }) }
    }
}
