package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.DetailHero
import com.muratkirazkaya.meiro.ui.components.HeroPlayButton
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.IconButton
import com.muratkirazkaya.meiro.ui.components.MIcon
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.EmptyState
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SearchField
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.components.clickableNoIndication
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** What the playlist screen shows (`V.playlist`, Meiro list). [kind] is "Meiro listesi · N parça · duration". */
data class PlaylistUi(
    val title: String,
    val cover: CoverSpec,
    val kind: String,
    val summary: String,
    val rows: List<SongRowData>,
    val similar: List<SongRowData> = emptyList(),
    /** The logo before [kind]: Meiro for the user's own list, YouTube Music for a list of the account's (M6g). */
    val provider: Provider = Provider.Meiro,
)

/**
 * M6h: the page's search bar and order (mock `plsearch`, `plsort`). [rows][PlaylistUi.rows] are given already searched
 * and ordered; [sortLabel] is the sort button's text; [share] false leaves the share button inert (a Meiro list has no
 * public link).
 */
data class PlaylistTools(val searching: Boolean = false, val query: String = "", val sortLabel: String = "Ekleme sırası", val share: Boolean = false)

class PlaylistToolActions(
    /** The top bar's search button and the bar's ×: the bar opens, or closes and the whole list is back. */
    val onSearch: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    /** "Ekleme sırası": the order sheet. */
    val onSort: () -> Unit = {},
    /** The hero's share button (a YouTube Music list: Android's share sheet with its link). */
    val onShare: () -> Unit = {},
)

/**
 * Mock screens `playlist`, `plsearch` and `plsort`: top bar (back, search and ⋮; no avatar), the search bar while it is
 * open, the hero (cover, name, kind line, five actions), the summary row with the sort button, the tracks, and
 * "Benzer şarkılar" with its tracks when there are any (not while a search is typed). "Sırayı düzenle" is inert (the
 * desktop cannot move a list's songs), and share on a Meiro list (no public link); ⋮ opens the list menu, a track's ⋮
 * the track menu, its link button the link sheet.
 */
@Composable
fun PlaylistScreen(
    ui: PlaylistUi?,
    playing: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRow: (Int) -> Unit,
    state: LazyListState = rememberLazyListState(),
    /** ⋮ of the track at that place: the track menu. */
    onMore: (Int) -> Unit = {},
    /** ⋮ in the top bar: the list menu. */
    onListMenu: () -> Unit = {},
    /** The link button of the track at that place: the link sheet. */
    onLink: (Int) -> Unit = {},
    /** The hero's download button: the list's tracks are downloaded. */
    onDownload: () -> Unit = {},
    /** M6g, a YouTube Music list: [ui] null while it loads (only the top bar), [error] when YouTube Music did not answer. */
    error: String? = null,
    onRetry: () -> Unit = {},
    tools: PlaylistTools = PlaylistTools(),
    toolActions: PlaylistToolActions = PlaylistToolActions(),
) {
    val c = Meiro.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("", onBack = onBack, avatar = false) {
            IconButton("topbar.searchBtn", "topbar.search.icon", MeiroIcons.Search, c.onSurfaceVariant, toolActions.onSearch)
            IconButton("topbar.moreBtn", "topbar.more.icon", MeiroIcons.More, c.onSurfaceVariant, onListMenu)
        }
        if (ui == null) {
            if (error != null) EmptyState("YouTube Music cevap vermedi", error, "Tekrar dene", onButton = onRetry, error = true)
            return@Column
        }
        // The search bar opens above the hero: the list goes back to its top so it shows.
        androidx.compose.runtime.LaunchedEffect(tools.searching) { if (tools.searching) state.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state) {
            if (tools.searching) item(key = "search") {
                SearchField(tools.query, toolActions.onQuery, {}, toolActions.onSearch, placeholder = "Listede ara", keyPrefix = "plsearch", alwaysClose = true, autoFocus = true)
            }
            item { Hero(ui, onPlay, onShuffle, onDownload, if (tools.share) toolActions.onShare else ({})) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
                ) {
                    MText(ui.summary, MeiroType.HeroKind, c.onSurfaceVariant, "tools.summary", Modifier.weight(1f))
                    SortButton(tools.sortLabel, toolActions.onSort)
                }
            }
            itemsIndexed(ui.rows, key = { i, r -> "$i/${r.key}" }) { i, r -> SongRow("song${i + 1}", r, playing, { onRow(i) }, onMore = { onMore(i) }, onLink = { onLink(i) }) }
            if (ui.similar.isNotEmpty() && tools.query.isBlank()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp)) {
                        MText("Benzer şarkılar", MeiroType.SectionTitle, c.onSurface, "similar.title")
                    }
                }
                itemsIndexed(ui.similar, key = { i, r -> "s$i/${r.key}" }) { i, r -> SongRow("similar${i + 1}", r, playing, {}) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Hero(ui: PlaylistUi, onPlay: () -> Unit, onShuffle: () -> Unit, onDownload: () -> Unit = {}, onShare: () -> Unit = {}) {
    val c = Meiro.colors
    DetailHero(ui.cover, ui.title, ui.kind, ui.provider) {
        IconButton("hero.downloadBtn", "hero.download.icon", MeiroIcons.Download, c.onSurfaceVariant, onDownload)
        IconButton("hero.shuffleBtn", "hero.shuffle.icon", MeiroIcons.Shuffle, c.onSurfaceVariant, onShuffle)
        HeroPlayButton(onPlay)
        IconButton("hero.editBtn", "hero.edit.icon", MeiroIcons.Edit, c.onSurfaceVariant)
        IconButton("hero.shareBtn", "hero.share.icon", MeiroIcons.Share, c.onSurfaceVariant, onShare)
    }
}

/** The mock's `.btn.text` "Ekleme sırası": the order the page shows ([label]); a tap opens the order sheet. */
@Composable
private fun SortButton(label: String, onClick: () -> Unit) {
    val c = Meiro.colors
    Row(
        Modifier.heightIn(min = 40.dp).inv("tools.sort", radiusDp = 20f).clip(RoundedCornerShape(20.dp)).clickableNoIndication(onClick)
            .padding(horizontal = 12.dp),
        Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
    ) {
        MIcon(MeiroIcons.Sort, c.primary, "tools.sort.icon", size = 18)
        MText(label, MeiroType.ButtonLabel, c.primary, "tools.sort.label", maxLines = 1)
    }
}
