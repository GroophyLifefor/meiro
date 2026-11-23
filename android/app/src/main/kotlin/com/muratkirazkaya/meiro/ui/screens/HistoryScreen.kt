package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.HistoryLine
import com.muratkirazkaya.meiro.ui.HistoryUi
import com.muratkirazkaya.meiro.ui.components.IconButton
import com.muratkirazkaya.meiro.ui.components.MIcon
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.SearchChipBox
import com.muratkirazkaya.meiro.ui.components.SearchField
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

class HistoryActions(
    val onBack: () -> Unit = {},
    /** The search button: opens the search bar, or closes it (and forgets the text). */
    val onSearch: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    /** ⋮: the history menu (`SHEET.histmenu`). */
    val onMenu: () -> Unit = {},
    val onChip: (String) -> Unit = {},
    /** A play's row (its index among the plays shown): plays that track. */
    val onRow: (Int) -> Unit = {},
    val onMore: (Int) -> Unit = {},
    val onLink: (Int) -> Unit = {},
)

/**
 * Mock screen `history` ("Dinleme geçmişi"): the top bar (back, search, ⋮; no avatar), the search bar while it is open,
 * the filter chips (Hepsi, Sayılanlar, Geçilenler and one per device), then day by day a heading with how many plays and
 * minutes and the plays as song rows with a note line (time, device, how much was heard); a play the stats do not count
 * is faded. The note at the end says what counts.
 */
@Composable
fun HistoryScreen(ui: HistoryUi, playing: Boolean, actions: HistoryActions = HistoryActions(), state: LazyListState = rememberLazyListState()) {
    val c = Meiro.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("Dinleme geçmişi", onBack = actions.onBack, avatar = false) {
            IconButton("topbar.searchBtn", "topbar.search.icon", MeiroIcons.Search, c.onSurfaceVariant, actions.onSearch)
            IconButton("topbar.moreBtn", "topbar.more.icon", MeiroIcons.More, c.onSurfaceVariant, actions.onMenu)
        }
        // The search bar opens above the chips: the list goes back to its top so it shows (a list keeps its first row in
        // place when a row is put above it).
        androidx.compose.runtime.LaunchedEffect(ui.searching) { if (ui.searching) state.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state) {
            if (ui.searching) item(key = "search") {
                SearchField(ui.query, actions.onQuery, {}, actions.onSearch, placeholder = "Geçmişte ara", keyPrefix = "hsearch", alwaysClose = true, autoFocus = true)
            }
            item(key = "chips") {
                LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.chips.size) { i ->
                        val chip = ui.chips[i]
                        val sel = chip.key == ui.filter
                        val k = "chip.${chipKey(chip.key, i)}"
                        SearchChipBox(k, chip.label, sel, { actions.onChip(chip.key) }) {
                            if (sel) MIcon(MeiroIcons.Check, c.onSecondaryContainer, "$k.check", size = 14)
                        }
                    }
                }
            }
            var day = 0
            var play = 0
            val keys = ui.lines.map { l -> if (l is HistoryLine.Day) "day${++day}" else "song${++play}" }
            itemsIndexed(ui.lines, key = { i, _ -> keys[i] }) { i, line ->
                when (line) {
                    is HistoryLine.Day -> DayHead(keys[i], line.title, line.sub)
                    is HistoryLine.Play -> {
                        val n = keys[i].removePrefix("song").toInt() - 1
                        SongRow(keys[i], line.row, playing, { actions.onRow(n) }, onMore = { actions.onMore(n) }, onLink = { actions.onLink(n) })
                    }
                }
            }
            if (ui.lines.isEmpty()) item(key = "empty") {
                MText(
                    if (ui.empty) "Henüz çalma yok. Meiro'da dinlediğin şarkılar burada listelenir." else "Bu seçime uyan çalma yok.",
                    MeiroType.Body, c.onSurfaceVariant, "empty", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                )
            }
            item(key = "note") {
                MText(ui.note, MeiroType.Note, c.onSurfaceVariant, "note", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The chips' keys: the three filters by name, the devices by place ("dev1" is the first device). */
private fun chipKey(key: String, i: Int) = when (key) {
    com.muratkirazkaya.meiro.ui.HistoryMapping.ALL -> "all"
    com.muratkirazkaya.meiro.ui.HistoryMapping.COUNTED -> "counted"
    com.muratkirazkaya.meiro.ui.HistoryMapping.SKIPPED -> "skipped"
    else -> "dev${i - 2}"
}

/** A day's `.h-sec`: the day (20/26) and, at the end, how many plays and minutes (`.sub`, 13/20). */
@Composable
private fun DayHead(p: String, title: String, sub: String) {
    val c = Meiro.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically,
    ) {
        MText(title, MeiroType.SectionTitle, c.onSurface, "$p.title", Modifier.weight(1f, fill = false), maxLines = 1)
        Spacer(Modifier.width(12.dp))
        MText(sub, MeiroType.HeroKind, c.onSurfaceVariant, "$p.sub", maxLines = 1)
    }
}
