package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.RankRow
import com.muratkirazkaya.meiro.ui.StatsPeriod
import com.muratkirazkaya.meiro.ui.StatsUi
import com.muratkirazkaya.meiro.ui.components.Cover
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.SectionHead
import com.muratkirazkaya.meiro.ui.components.Segmented
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

class StatsActions(val onBack: () -> Unit = {}, val onPeriod: (StatsPeriod) -> Unit = {})

/**
 * Mock screen `stats` ("Özet"): the top bar (back; no avatar), the month / year switch, four tiles (time heard, plays,
 * different tracks, artists and how many are new), the bars by day or by month, the most played artists and tracks,
 * and the note on where the numbers come from.
 */
@Composable
fun StatsScreen(ui: StatsUi, actions: StatsActions = StatsActions(), state: LazyListState = rememberLazyListState()) {
    val c = Meiro.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("Özet", onBack = actions.onBack, avatar = false)
        LazyColumn(Modifier.fillMaxSize(), state) {
            item(key = "seg") {
                Segmented(
                    "seg", listOf(ui.monthLabel, ui.yearLabel), listOf("seg.month", "seg.year"),
                    if (ui.period == StatsPeriod.Month) 0 else 1, { actions.onPeriod(if (it == 0) StatsPeriod.Month else StatsPeriod.Year) },
                    Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
            item(key = "tiles") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (r in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (col in 0..1) {
                            val i = r * 2 + col
                            ui.tiles.getOrNull(i)?.let { (value, label) -> Tile("tile${i + 1}", value, label, Modifier.weight(1f)) }
                        }
                    }
                }
            }
            item(key = "bars.head") { SectionHead(if (ui.period == StatsPeriod.Month) "Günlere göre" else "Aylara göre", "bars.title") }
            item(key = "bars") { Bars(ui) }
            if (ui.artists.isNotEmpty()) {
                item(key = "artists.head") { SectionHead("En çok dinlenen sanatçılar", "artists.title") }
                itemsIndexed(ui.artists, key = { i, _ -> "a$i" }) { i, r -> Rank("artist${i + 1}", i + 1, r, round = true) }
            }
            if (ui.tracks.isNotEmpty()) {
                item(key = "tracks.head") { SectionHead("En çok dinlenen şarkılar", "tracks.title") }
                itemsIndexed(ui.tracks, key = { i, _ -> "t$i" }) { i, r -> Rank("track${i + 1}", i + 1, r, round = false) }
            }
            item(key = "note") {
                MText(ui.note, MeiroType.Note, c.onSurfaceVariant, "note", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** `.stat > div`: surface-container-low, 18dp radius, 14dp padding; the value (26/600/32) over its label (12, on the body's 20px line). */
@Composable
private fun Tile(p: String, value: String, label: String, modifier: Modifier) {
    val c = Meiro.colors
    Column(
        modifier.inv(p, bg = c.surfaceContainerLow, radiusDp = 18f).clip(RoundedCornerShape(18.dp)).background(c.surfaceContainerLow)
            .padding(14.dp),
    ) {
        MText(value, MeiroType.StatValue, c.onSurface, "$p.value", Modifier.fillMaxWidth(), maxLines = 1)
        // The label is an inline span in a 21px line box (the 12px span on the body's 14px strut).
        Box(Modifier.height(21.dp), Alignment.CenterStart) { MText(label, MeiroType.StatLabel, c.onSurfaceVariant, "$p.label", maxLines = 1) }
    }
}

/**
 * `.bars`: 120dp, surface-container-low, 18dp radius, 14dp padding; the bars share the width 6dp apart (each at its own
 * fractional place, rounded, as CSS flex lays them out) and stand on the bottom.
 */
@Composable
private fun Bars(ui: StatsUi) {
    val c = Meiro.colors
    androidx.compose.ui.layout.Layout(
        content = {
            ui.bars.forEachIndexed { i, b ->
                Box(
                    Modifier.inv("bar${i + 1}", bg = c.primary, radiusDp = 4f).alpha(if (b.lo) 0.35f else 0.85f)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 2.dp, bottomEnd = 2.dp)).background(c.primary),
                )
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(120.dp).inv("bars", bg = c.surfaceContainerLow, radiusDp = 18f)
            .clip(RoundedCornerShape(18.dp)).background(c.surfaceContainerLow).padding(14.dp),
    ) { measurables, constraints ->
        val n = measurables.size.coerceAtLeast(1)
        val gap = 6.dp.toPx()
        val w = (constraints.maxWidth - gap * (n - 1)) / n
        val h = constraints.maxHeight
        val placeables = measurables.mapIndexed { i, m ->
            val left = Math.round(i * (w + gap)); val right = Math.round(i * (w + gap) + w)
            val bh = Math.round(h * ui.bars[i].height.coerceIn(0f, 1f))
            m.measure(androidx.compose.ui.unit.Constraints.fixed((right - left).coerceAtLeast(0), bh.coerceAtLeast(0))) to left
        }
        layout(constraints.maxWidth, h) { placeables.forEach { (p, x) -> p.place(x, h - p.height) } }
    }
}

/** `.rank`: 8dp/16dp padding, the place (22dp, 600), a 44dp cover (round for an artist), the name and its line, both one line. */
@Composable
private fun Rank(p: String, n: Int, r: RankRow, round: Boolean) {
    val c = Meiro.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), Arrangement.spacedBy(12.dp), Alignment.CenterVertically) {
        MText(n.toString(), MeiroType.RankNum, c.onSurfaceVariant, "$p.n", Modifier.width(22.dp), maxLines = 1)
        Cover(r.cover, if (round) 22.dp else 8.dp, "$p.cover", Modifier.size(44.dp))
        Column(Modifier.weight(1f)) {
            MText(r.title, MeiroType.RankTitle, c.onSurface, "$p.title", Modifier.fillMaxWidth(), maxLines = 1)
            MText(r.sub, MeiroType.RankSub, c.onSurfaceVariant, "$p.sub", Modifier.fillMaxWidth(), maxLines = 1)
        }
    }
}
