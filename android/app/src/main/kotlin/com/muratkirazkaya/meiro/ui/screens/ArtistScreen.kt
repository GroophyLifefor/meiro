package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.AlbumTile
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.DetailHero
import com.muratkirazkaya.meiro.ui.components.EmptyState
import com.muratkirazkaya.meiro.ui.components.HeroLabelButton
import com.muratkirazkaya.meiro.ui.components.HeroPlayButton
import com.muratkirazkaya.meiro.ui.components.SectionHead
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons

/** What the artist page shows (`V.artist`): its popular tracks, its albums and the artists like it. */
data class ArtistUi(
    val name: String,
    val cover: CoverSpec,
    val kind: String,
    val popular: List<SongRowData>,
    val albums: List<TileData>,
    val similar: List<TileData>,
)

/**
 * Mock screen `artist`: top bar (back only), the hero (round picture, name, kind line; "Karıştır", play, "Bağı
 * değiştir"), "Popüler" tracks, the "Albümler" shelf and the "Benzer sanatçılar" shelf; a section with nothing in it
 * is left out. "Bağı değiştir" opens the artist's link sheet, a track's link button the track's; a track's ⋮ opens the
 * track menu. [ui] null:
 * loading (only the top bar), or [error]. M6d: the same screen shows a MusicBrainz artist (`MbArtistMapping`): its top
 * recordings, those with nothing to play faded and without menu (the mock's `naRow`), no albums, ListenBrainz's
 * similar artists.
 */
@Composable
fun ArtistScreen(
    ui: ArtistUi?,
    playing: Boolean,
    actions: DetailActions,
    error: String? = null,
    state: LazyListState = rememberLazyListState(),
    /** The error block's title: the service the page comes from did not answer. */
    errorTitle: String = "YouTube Music cevap vermedi",
) {
    Column(Modifier.fillMaxSize()) {
        TopBar("", onBack = actions.onBack, avatar = false)
        if (ui == null) {
            if (error != null) EmptyState(errorTitle, error, "Tekrar dene", onButton = actions.onRetry, error = true)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state) {
            item {
                DetailHero(ui.cover, ui.name, ui.kind, logo = null, round = true) {
                    HeroLabelButton("hero.shuffleBtn", MeiroIcons.Shuffle, "Karıştır", tonal = true, onClick = actions.onShuffle)
                    HeroPlayButton(actions.onPlay, margin = 0.dp)
                    // The last button takes what is left: on a narrow screen its label ends in an ellipsis.
                    HeroLabelButton("hero.linkBtn", MeiroIcons.Link, "Bağı değiştir", tonal = false, onClick = actions.onArtistLink, modifier = Modifier.weight(1f, fill = false))
                }
            }
            if (ui.popular.isNotEmpty()) {
                item { SectionHead("Popüler", "popular.title") }
                itemsIndexed(ui.popular, key = { i, r -> "p$i/${r.key}" }) { i, r -> SongRow("song${i + 1}", r, playing, { actions.onRow(i) }, onMore = { actions.onMore(i) }, onLink = { actions.onLink(i) }) }
            }
            if (ui.albums.isNotEmpty()) {
                item { SectionHead("Albümler", "albums.title") }
                item { Shelf("album", ui.albums, actions.onAlbum) }
            }
            if (ui.similar.isNotEmpty()) {
                item { SectionHead("Benzer sanatçılar", "similar.title") }
                item { Shelf("similar", ui.similar, actions.onArtist) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** The mock's `.shelf`: tiles 12dp apart, 16dp in from the sides, 4dp below. */
@Composable
private fun Shelf(p: String, tiles: List<TileData>, onTile: (Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(tiles, key = { i, t -> "$i/${t.key}" }) { i, t -> AlbumTile("$p${i + 1}", t) { onTile(i) } }
    }
}
