package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.AlbumTile
import com.muratkirazkaya.meiro.ui.components.ArtistRow
import com.muratkirazkaya.meiro.ui.components.ArtistRowData
import com.muratkirazkaya.meiro.ui.components.ListRow
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.MIcon
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.RecentRow
import com.muratkirazkaya.meiro.ui.components.SearchChipBox
import com.muratkirazkaya.meiro.ui.components.SearchField
import com.muratkirazkaya.meiro.ui.components.SectionHead
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.SourceLogo
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** The mock's filter chips, in its order. */
enum class SearchChip(val label: String, val key: String) {
    All("Hepsi", "all"), Songs("Şarkılar", "songs"), Artists("Sanatçılar", "artists"), Albums("Albümler", "albums"), Lists("Listeler", "lists"),
}

/**
 * What the search screen shows. Before a search ([results] false) the recent searches; after one the filter and
 * provider chips and the sections the chosen filter shows (a section without rows is left out). [note] is the line the
 * mock's offline state shows over results that came from this device.
 */
data class SearchUi(
    val query: String = "",
    val results: Boolean = false,
    val recent: List<String> = emptyList(),
    val chip: SearchChip = SearchChip.All,
    val ytmOn: Boolean = true,
    val scOn: Boolean = true,
    val note: String? = null,
    val artists: List<ArtistRowData> = emptyList(),
    val songs: List<SongRowData> = emptyList(),
    val albums: List<TileData> = emptyList(),
    val lists: List<ListRowData> = emptyList(),
)

class SearchActions(
    val onQuery: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onClear: () -> Unit = {},
    val onRecent: (String) -> Unit = {},
    val onForget: (String) -> Unit = {},
    val onClearRecent: () -> Unit = {},
    val onChip: (SearchChip) -> Unit = {},
    val onProvider: (Provider) -> Unit = {},
    val onSong: (Int) -> Unit = {},
    /** The list was scrolled to its end (the next page of a filtered search is asked for). */
    val onEnd: () -> Unit = {},
    /** An artist row and an album tile: they open the artist and the album page. */
    val onArtist: (Int) -> Unit = {},
    val onAlbum: (Int) -> Unit = {},
)

/**
 * Mock screens `search` and `search-done` (`V.search`): top bar "Ara" with the avatar, the search bar, then either
 * "Son aramalar" or the results: filter chips, provider chips, Sanatçılar, Şarkılar, Albümler (a shelf) and Listeler.
 * Artist, album and list taps, the ⋮ buttons and the link buttons are inert in this milestone.
 */
@Composable
fun SearchScreen(ui: SearchUi, playing: Boolean, actions: SearchActions, state: LazyListState = rememberLazyListState()) {
    val c = Meiro.colors
    val atEnd by remember(state) {
        derivedStateOf { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= state.layoutInfo.totalItemsCount - 2 } == true }
    }
    LaunchedEffect(atEnd, ui.songs.size) { if (atEnd && ui.results) actions.onEnd() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Ara")
        LazyColumn(Modifier.fillMaxSize(), state) {
            item(key = "field") { SearchField(ui.query, actions.onQuery, actions.onSubmit, actions.onClear) }
            if (!ui.results) {
                if (ui.recent.isNotEmpty()) {
                    item(key = "recent.head") { SectionHead("Son aramalar", "recent.title", "Temizle", "recent.clearBtn", actions.onClearRecent) }
                    itemsIndexed(ui.recent, key = { _, q -> "r/$q" }) { i, q ->
                        RecentRow("recent${i + 1}", q, { actions.onRecent(q) }, { actions.onForget(q) })
                    }
                }
                return@LazyColumn
            }
            item(key = "chips") {
                LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SearchChip.entries.size) { i ->
                        val f = SearchChip.entries[i]
                        val sel = f == ui.chip
                        SearchChipBox("chip.${f.key}", f.label, sel, { actions.onChip(f) }) {
                            if (sel) MIcon(MeiroIcons.Check, c.onSecondaryContainer, "chip.${f.key}.check", size = 14)
                        }
                    }
                }
            }
            item(key = "providers") {
                LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { SearchChipBox("prov.ytm", "YouTube Music", ui.ytmOn, { actions.onProvider(Provider.Ytm) }) { SourceLogo("prov.ytm.logo", Provider.Ytm, false) } }
                    item { SearchChipBox("prov.sc", "SoundCloud", ui.scOn, { actions.onProvider(Provider.Sc) }) { SourceLogo("prov.sc.logo", Provider.Sc, false) } }
                }
            }
            ui.note?.let { n ->
                item(key = "note") {
                    MText(n, MeiroType.Note, c.onSurfaceVariant, "note", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp))
                }
            }
            if (ui.artists.isNotEmpty()) {
                item(key = "artists.head") { SectionHead("Sanatçılar", "artists.title") }
                itemsIndexed(ui.artists, key = { i, a -> "a$i/${a.key}" }) { i, a -> ArtistRow("artist${i + 1}", a) { actions.onArtist(i) } }
            }
            if (ui.songs.isNotEmpty()) {
                // The offline note stands over its rows without a section title, as in the mock's offline state.
                if (ui.note == null) item(key = "songs.head") { SectionHead("Şarkılar", "songs.title") }
                itemsIndexed(ui.songs, key = { i, s -> "s$i/${s.key}" }) { i, s -> SongRow("song${i + 1}", s, playing, { actions.onSong(i) }) }
            }
            if (ui.albums.isNotEmpty()) {
                item(key = "albums.head") { SectionHead("Albümler", "albums.title") }
                item(key = "albums") {
                    LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(ui.albums, key = { i, t -> "t$i/${t.key}" }) { i, t -> AlbumTile("album${i + 1}", t) { actions.onAlbum(i) } }
                    }
                }
            }
            if (ui.lists.isNotEmpty()) {
                item(key = "lists.head") { SectionHead("Listeler", "lists.title") }
                itemsIndexed(ui.lists, key = { i, l -> "l$i/${l.id}" }) { i, l -> ListRow("list${i + 1}", l, {}) }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
