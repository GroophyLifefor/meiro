package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.IconButton
import com.muratkirazkaya.meiro.ui.components.LibraryTabs
import com.muratkirazkaya.meiro.ui.components.ListRow
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.NewListFab
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** The mock's note under the YouTube Music tab's lists. */
const val YTM_LIBRARY_NOTE = "YouTube Music sekmesi yalnız YouTube Music girişi varken görünür. Bir listeyi Meiro listesine kopyalamak için ⋮ menüsü."

/**
 * Mock screens `library` and `library-ytm` (`V.library`): top bar with the (inert) sort button and avatar, the tabs,
 * then the selected tab. Meiro tab: the lists, the sync note and the "Yeni liste" FAB. "YouTube Music" tab (only with
 * [ytmTab], a YouTube Music sign-in; [ytmSelected]): the liked songs, the account's playlists and saved albums
 * ([ytmRows]) and the note; no FAB. Without lists the Meiro tab is [LibraryEmptyScreen] instead.
 */
@Composable
fun LibraryScreen(
    rows: List<ListRowData>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    ytmTab: Boolean = false,
    /** ⋮ of a list: the list menu. */
    onMore: (String) -> Unit = {},
    ytmSelected: Boolean = false,
    /** A tap on a tab: true for "YouTube Music". */
    onTab: (Boolean) -> Unit = {},
    ytmRows: List<ListRowData> = emptyList(),
    onYtmOpen: (String) -> Unit = {},
    onYtmMore: (String) -> Unit = {},
) {
    val c = Meiro.colors
    val ytm = ytmTab && ytmSelected
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Kütüphane") { IconButton("topbar.sortBtn", "topbar.sort.icon", MeiroIcons.Sort, c.onSurfaceVariant) }
            LibraryTabs(ytmTab = ytmTab, ytmSelected = ytm, onTab = onTab)
            val offline = com.muratkirazkaya.meiro.ui.components.LocalOffline.current
            LazyColumn(Modifier.fillMaxSize()) {
                if (offline) item { com.muratkirazkaya.meiro.ui.components.OfflineBanner() }
                item { Spacer(Modifier.height(8.dp)) }
                if (ytm) {
                    itemsIndexed(ytmRows, key = { _, r -> r.id }) { i, r -> ListRow("row${i + 1}", r, { onYtmOpen(r.id) }, onMore = { onYtmMore(r.id) }) }
                    item {
                        MText(YTM_LIBRARY_NOTE, MeiroType.Note, c.onSurfaceVariant, "note", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp))
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                } else {
                    itemsIndexed(rows, key = { _, r -> r.id }) { i, r -> ListRow("row${i + 1}", r, { onOpen(r.id) }, onMore = { onMore(r.id) }) }
                    item {
                        MText(
                            "Listeler Meiro hesabında tutulur, masaüstüyle iki yönlü eşitlenir.", MeiroType.Note, c.onSurfaceVariant, "note",
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        )
                    }
                    item { Spacer(Modifier.height(88.dp + 16.dp)) }
                }
            }
        }
        if (!ytm) NewListFab(onNew, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp))
    }
}
