package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.clickableNoIndication
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** Where a generated track of the queue came from (the mock's `.qlabel` tags). */
enum class SuggestionKind { Meiro, Ytm }

/** One line of the up-next list: a track at [index] of the queue, or the heading over a run of generated tracks. */
sealed class QueueRow {
    data class Song(val index: Int, val row: SongRowData) : QueueRow()
    data class Label(val kind: SuggestionKind) : QueueRow()
}

/** What the up-next tab (`SHEET.qsheet`, tab `upnext`) shows: the auto-play switch, the source line and the queue. */
data class UpNextUi(val autoPlay: Boolean, val sourceLine: String, val rows: List<QueueRow>)

class UpNextActions(
    val onClose: () -> Unit = {},
    val onAutoPlay: (Boolean) -> Unit = {},
    val onClear: () -> Unit = {},
    /** A tap on the track at that place of the queue. */
    val onRow: (Int) -> Unit = {},
    /** A drag moved the track at the first place of the queue to the second. */
    val onMove: (Int, Int) -> Unit = { _, _ -> },
    val onTab: (PlayerTab) -> Unit = {},
    /** "Senkron" (true) or "Düz" (false) chosen for timed lyrics. */
    val onLyricsSynced: (Boolean) -> Unit = {},
    /** A tap on a timed lyric line: seek to when it is sung. */
    val onLyricSeek: (Long) -> Unit = {},
    /** A tap on the related track at that place of the related list. */
    val onRelated: (Int) -> Unit = {},
    /** ⋮ of the track at that place of the queue: the track menu. */
    val onRowMore: (Int) -> Unit = {},
    /** ⋮ of the related track at that place: the track menu. */
    val onRelatedMore: (Int) -> Unit = {},
)

/**
 * The mock's queue sheet (`.sheet.tall`, 88% of the screen) over the full player: the handle, the three tabs and the
 * chosen tab's content. "Sıradakiler": "Otomatik devam" with its switch, "<list>'nden çalıyor · N sırada" with
 * "Temizle", and the queue's rows with drag handles; generated tracks sit under "Önerilenler" with the tag of where
 * they came from. "Sözler": [lyrics] ([LyricsTab]). "Benzerler": [related] ([RelatedTab]).
 */
@Composable
fun UpNextSheet(
    ui: UpNextUi, playing: Boolean, bars: SystemBars, actions: UpNextActions = UpNextActions(), state: LazyListState = rememberLazyListState(),
    tab: PlayerTab = PlayerTab.UpNext, lyrics: LyricsUi = LyricsUi(), related: RelatedUi = RelatedUi(), positionMs: Long = 0,
) {
    val c = Meiro.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(c.scrim).clickableNoIndication(actions.onClose))
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight * 0.88f)
                .inv("q.sheet", bg = c.surfaceContainerLow, radiusDp = 28f).clip(shape).background(c.surfaceContainerLow)
                .clickableNoIndication {}.padding(bottom = maxOf(20.dp, bars.bottom)),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 8.dp).size(width = 32.dp, height = 4.dp)
                    .inv("q.handle", bg = c.outline, radiusDp = 2f).clip(RoundedCornerShape(2.dp)).background(c.outline),
            )
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth()
                    .inv("q.tabs", bg = c.surfaceContainerHigh, radiusDp = 22f).clip(RoundedCornerShape(22.dp)).background(c.surfaceContainerHigh).padding(4.dp),
            ) {
                QTab("q.tab.upnext", "Sıradakiler", tab == PlayerTab.UpNext, Modifier.weight(1f)) { actions.onTab(PlayerTab.UpNext) }
                QTab("q.tab.lyrics", "Sözler", tab == PlayerTab.Lyrics, Modifier.weight(1f)) { actions.onTab(PlayerTab.Lyrics) }
                QTab("q.tab.related", "Benzerler", tab == PlayerTab.Related, Modifier.weight(1f)) { actions.onTab(PlayerTab.Related) }
            }
            when (tab) {
                PlayerTab.UpNext -> QueueList(ui, playing, actions, state, Modifier.weight(1f))
                PlayerTab.Lyrics -> LyricsTab(lyrics, positionMs, actions, Modifier.weight(1f))
                PlayerTab.Related -> RelatedTab(related, playing, actions, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun QTab(key: String, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.heightIn(min = 36.dp).inv(key, bg = if (on) c.secondaryContainer else null, radiusDp = 18f).clip(shape)
            .then(if (on) Modifier.background(c.secondaryContainer) else Modifier).clickableNoIndication(onClick),
        Alignment.Center,
    ) { MText(label, MeiroType.QTab, if (on) c.onSecondaryContainer else c.onSurfaceVariant, "$key.label", maxLines = 1) }
}

@Composable
private fun QueueList(ui: UpNextUi, playing: Boolean, actions: UpNextActions, state: LazyListState, modifier: Modifier) {
    val c = Meiro.colors
    // The row a drag holds (its place in the queue) and how far it has been dragged.
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragY by remember { mutableStateOf(0f) }
    // List keys, and the inventory keys of the rows (the n-th track row is `q.song<n>`, the n-th heading `q.label<n>`).
    var labels = 0
    var songs = 0
    val names = ui.rows.map { r -> when (r) { is QueueRow.Song -> "q.song${++songs}"; is QueueRow.Label -> "q.label${++labels}" } }
    val keys = ui.rows.mapIndexed { i, r -> when (r) { is QueueRow.Song -> "s${r.index}:${r.row.key}"; is QueueRow.Label -> "l$i" } }
    LazyColumn(modifier.fillMaxWidth(), state = state) {
        item("auto") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MText("Otomatik devam", MeiroType.QHeadTitle, c.onSurface, "q.auto.title", Modifier.fillMaxWidth())
                    MText("Kuyruk bitince benzer müzik ekle", MeiroType.QHeadSub, c.onSurfaceVariant, "q.auto.sub", Modifier.fillMaxWidth())
                }
                Switch(ui.autoPlay) { actions.onAutoPlay(!ui.autoPlay) }
            }
        }
        item("source") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                MText(ui.sourceLine, MeiroType.QHeadSub, c.onSurfaceVariant, "q.source", Modifier.weight(1f))
                Box(
                    Modifier.heightIn(min = 40.dp).inv("q.clearBtn", radiusDp = 20f).clip(RoundedCornerShape(20.dp))
                        .clickableNoIndication(actions.onClear).padding(horizontal = 12.dp),
                    Alignment.Center,
                ) { MText("Temizle", MeiroType.ButtonLabel, c.primary, "q.clear.label", maxLines = 1) }
            }
        }
        itemsIndexed(ui.rows, key = { i, _ -> keys[i] }) { i, r ->
            when (r) {
                is QueueRow.Label -> QLabel(names[i], r.kind)
                is QueueRow.Song -> {
                    val held = dragging == r.index
                    SongRow(
                        names[i], r.row, playing, { actions.onRow(r.index) }, onMore = { actions.onRowMore(r.index) }, modifier =
                        Modifier.zIndex(if (held) 1f else 0f).graphicsLayer { translationY = if (held) dragY else 0f }
                            .then(if (held) Modifier.background(c.surfaceContainerHigh) else Modifier),
                        dragHandle = Modifier.pointerInput(r.index) {
                            detectDragGestures(
                                onDragStart = { dragging = r.index; dragY = 0f },
                                onDragEnd = {
                                    val from = r.index
                                    val to = dropTarget(state, keys, ui.rows, from, dragY)
                                    dragging = null; dragY = 0f
                                    if (to != null && to != from) actions.onMove(from, to)
                                },
                                onDragCancel = { dragging = null; dragY = 0f },
                            ) { change, amount -> change.consume(); dragY += amount.y }
                        },
                    )
                }
            }
        }
    }
}

/** The place in the queue of the visible track row the held row's centre was dropped over, or null. */
private fun dropTarget(state: LazyListState, keys: List<String>, rows: List<QueueRow>, from: Int, dy: Float): Int? {
    val info = state.layoutInfo.visibleItemsInfo
    val held = info.firstOrNull { it.key == keys[rows.indexOfFirst { it is QueueRow.Song && it.index == from }.coerceAtLeast(0)] } ?: return null
    val centre = held.offset + held.size / 2 + dy
    var best: Int? = null
    for (it in info) {
        val i = keys.indexOf(it.key as? String ?: continue)
        val r = rows.getOrNull(i) as? QueueRow.Song ?: continue
        if (centre >= it.offset && centre < it.offset + it.size) best = r.index
    }
    if (best == null) {
        // Past the first or the last visible row: the end of what is visible in that direction.
        val songs = info.mapNotNull { v -> (rows.getOrNull(keys.indexOf(v.key as? String ?: "")) as? QueueRow.Song)?.let { v to it } }
        if (songs.isEmpty()) return null
        best = if (centre < songs.first().first.offset) songs.first().second.index else songs.last().second.index
    }
    return best
}

/** `.sw`: 52x32 with a 2px border; on, primary with a 24px knob at the end; off, the outline colours with a 16px knob. */
@Composable
private fun Switch(on: Boolean, onClick: () -> Unit) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(16.dp)
    val bg = if (on) c.primary else c.surfaceContainerHighest
    Box(
        Modifier.size(52.dp, 32.dp).inv("q.auto.switch", bg = bg, radiusDp = 16f).clip(shape).background(bg)
            .border(2.dp, if (on) c.primary else c.outline, shape).clickableNoIndication(onClick),
    ) {
        val knob = if (on) c.onPrimary else c.outline
        Box(
            Modifier.offset(x = if (on) 24.dp else 8.dp, y = if (on) 4.dp else 8.dp).size(if (on) 24.dp else 16.dp)
                .inv("q.auto.knob", bg = knob, radiusDp = if (on) 12f else 8f).clip(CircleShape).background(knob),
        )
    }
}

/** `.qlabel`: "Önerilenler" and the tag of where the tracks under it came from. */
@Composable
private fun QLabel(key: String, kind: SuggestionKind) {
    val c = Meiro.colors
    Row(
        Modifier.fillMaxWidth().inv(key).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
        Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
    ) {
        MText("Önerilenler", MeiroType.QLabel, c.onSurfaceVariant, "$key.text", maxLines = 1)
        val (bg, fg, text) = when (kind) {
            SuggestionKind.Meiro -> Triple(c.tertiaryContainer, c.onTertiaryContainer, "Meiro: zevkine göre")
            SuggestionKind.Ytm -> Triple(c.surfaceContainerHighest, c.onSurfaceVariant, "YouTube Music")
        }
        Box(Modifier.inv("$key.tag", bg = bg, radiusDp = 6f).clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp)) {
            MText(text, MeiroType.QTag, fg, "$key.tag.text", maxLines = 1)
        }
    }
}
