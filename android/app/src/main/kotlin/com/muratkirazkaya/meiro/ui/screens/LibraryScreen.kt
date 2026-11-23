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

/**
 * Mock screen `library` (`V.library`, Meiro tab): top bar with the (inert) sort button and avatar, the tabs, the lists,
 * the sync note and the "Yeni liste" FAB. The "YouTube Music" tab shows only with [ytmTab] (not signed in to YouTube
 * Music yet, so the app never sets it). Without lists the app shows [LibraryEmptyScreen] instead.
 */
@Composable
fun LibraryScreen(
    rows: List<ListRowData>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
    ytmTab: Boolean = false,
    /** ⋮ of a list: the list menu. */
    onMore: (String) -> Unit = {},
) {
    val c = Meiro.colors
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Kütüphane") { IconButton("topbar.sortBtn", "topbar.sort.icon", MeiroIcons.Sort, c.onSurfaceVariant) }
            LibraryTabs(ytmTab = ytmTab)
            val offline = com.muratkirazkaya.meiro.ui.components.LocalOffline.current
            LazyColumn(Modifier.fillMaxSize()) {
                if (offline) item { com.muratkirazkaya.meiro.ui.components.OfflineBanner() }
                item { Spacer(Modifier.height(8.dp)) }
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
        NewListFab(onNew, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp))
    }
}
