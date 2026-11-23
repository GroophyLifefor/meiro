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
import com.muratkirazkaya.meiro.ui.components.Provider
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
)

/**
 * Mock screen `playlist`: top bar (back, search and ⋮; no avatar), the hero (cover, name, kind line, five actions),
 * the summary row with the sort button, the tracks, and "Benzer şarkılar" with its tracks when there are any.
 * Download, edit, share, search, sort and the link buttons are inert; ⋮ opens the list menu, a track's ⋮ the track menu.
 */
@Composable
fun PlaylistScreen(
    ui: PlaylistUi,
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
) {
    val c = Meiro.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("", onBack = onBack, avatar = false) {
            IconButton("topbar.searchBtn", "topbar.search.icon", MeiroIcons.Search, c.onSurfaceVariant)
            IconButton("topbar.moreBtn", "topbar.more.icon", MeiroIcons.More, c.onSurfaceVariant, onListMenu)
        }
        LazyColumn(Modifier.fillMaxSize(), state) {
            item { Hero(ui, onPlay, onShuffle) }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
                ) {
                    MText(ui.summary, MeiroType.HeroKind, c.onSurfaceVariant, "tools.summary", Modifier.weight(1f))
                    SortButton()
                }
            }
            itemsIndexed(ui.rows, key = { i, r -> "$i/${r.key}" }) { i, r -> SongRow("song${i + 1}", r, playing, { onRow(i) }, onMore = { onMore(i) }) }
            if (ui.similar.isNotEmpty()) {
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
private fun Hero(ui: PlaylistUi, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val c = Meiro.colors
    DetailHero(ui.cover, ui.title, ui.kind, Provider.Meiro) {
        IconButton("hero.downloadBtn", "hero.download.icon", MeiroIcons.Download, c.onSurfaceVariant)
        IconButton("hero.shuffleBtn", "hero.shuffle.icon", MeiroIcons.Shuffle, c.onSurfaceVariant, onShuffle)
        HeroPlayButton(onPlay)
        IconButton("hero.editBtn", "hero.edit.icon", MeiroIcons.Edit, c.onSurfaceVariant)
        IconButton("hero.shareBtn", "hero.share.icon", MeiroIcons.Share, c.onSurfaceVariant)
    }
}

/** The mock's `.btn.text` "Ekleme sırası" (the only order for now; inert). */
@Composable
private fun SortButton() {
    val c = Meiro.colors
    Row(
        Modifier.heightIn(min = 40.dp).inv("tools.sort", radiusDp = 20f).clip(RoundedCornerShape(20.dp)).clickableNoIndication {}
            .padding(horizontal = 12.dp),
        Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
    ) {
        MIcon(MeiroIcons.Sort, c.primary, "tools.sort.icon", size = 18)
        MText("Ekleme sırası", MeiroType.ButtonLabel, c.primary, "tools.sort.label", maxLines = 1)
    }
}
