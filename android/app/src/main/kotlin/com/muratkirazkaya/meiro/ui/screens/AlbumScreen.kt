package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.DetailHero
import com.muratkirazkaya.meiro.ui.components.EmptyState
import com.muratkirazkaya.meiro.ui.components.HeroPlayButton
import com.muratkirazkaya.meiro.ui.components.IconButton
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** What the album page shows (`V.album`): [kind] is "Albüm · artist · year · N parça", [nums] the track numbers. */
data class AlbumUi(
    val title: String,
    val cover: CoverSpec,
    val kind: String,
    val rows: List<SongRowData>,
    val nums: List<String>,
)

/** The mock's note under an album's tracks. */
const val ALBUM_NOTE = "Albüm şarkılarında kapak yoksa albüm kapağı kullanılır. Kapaklar telefonda önbelleğe alınır."

class DetailActions(
    val onBack: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onRow: (Int) -> Unit = {},
    /** Album: "Meiro listesi olarak kopyala". */
    val onCopy: () -> Unit = {},
    /** Artist: an album tile of the "Albümler" shelf, and an artist of "Benzer sanatçılar". */
    val onAlbum: (Int) -> Unit = {},
    val onArtist: (Int) -> Unit = {},
    val onRetry: () -> Unit = {},
    /** ⋮ of the track at that place: the track menu. */
    val onMore: (Int) -> Unit = {},
    /** ⋮ in the top bar: the list menu (album; the mix page's ⋮ opens nothing in the mock). */
    val onListMenu: () -> Unit = {},
    /** The link button of the track at that place: the link sheet. */
    val onLink: (Int) -> Unit = {},
    /** Artist: "Bağı değiştir", the artist's link sheet. */
    val onArtistLink: () -> Unit = {},
    /** Album and Daily mix: the hero's download button ("Hepsini indir" for the page's tracks). */
    val onDownload: () -> Unit = {},
    /** Daily mix: the hero's settings button (the settings screen, where the recommendations are). */
    val onSettings: () -> Unit = {},
    /** M6h, album: the hero's share button (Android's share sheet with the album's YouTube Music link). */
    val onShare: () -> Unit = {},
)

/**
 * Mock screen `album`: top bar (back and ⋮), the hero (cover, name, YouTube Music logo and kind line; download,
 * shuffle, play, "Meiro listesi olarak kopyala", share), the tracks with their numbers, and the note. Every control works
 * (share: Android's share sheet with the album's link, M6h; ⋮ opens the list menu, a track's ⋮ the track menu, its link button the link sheet). [ui] null: the page is loading (only the top bar);
 * [error] set: YouTube Music did not answer (the mock's `error` block with "Tekrar dene").
 */
@Composable
fun AlbumScreen(
    ui: AlbumUi?,
    playing: Boolean,
    actions: DetailActions,
    error: String? = null,
    state: LazyListState = rememberLazyListState(),
) {
    val c = Meiro.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("", onBack = actions.onBack, avatar = false) {
            IconButton("topbar.moreBtn", "topbar.more.icon", MeiroIcons.More, c.onSurfaceVariant, actions.onListMenu)
        }
        if (ui == null) {
            if (error != null) EmptyState("YouTube Music cevap vermedi", error, "Tekrar dene", onButton = actions.onRetry, error = true)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), state) {
            item {
                DetailHero(ui.cover, ui.title, ui.kind, Provider.Ytm) {
                    IconButton("hero.downloadBtn", "hero.download.icon", MeiroIcons.Download, c.onSurfaceVariant, actions.onDownload)
                    IconButton("hero.shuffleBtn", "hero.shuffle.icon", MeiroIcons.Shuffle, c.onSurfaceVariant, actions.onShuffle)
                    HeroPlayButton(actions.onPlay)
                    IconButton("hero.copyBtn", "hero.copy.icon", MeiroIcons.Copy, c.onSurfaceVariant, actions.onCopy)
                    IconButton("hero.shareBtn", "hero.share.icon", MeiroIcons.Share, c.onSurfaceVariant, actions.onShare)
                }
            }
            itemsIndexed(ui.rows, key = { i, r -> "$i/${r.key}" }) { i, r ->
                SongRow("song${i + 1}", r, playing, { actions.onRow(i) }, num = ui.nums.getOrElse(i) { "${i + 1}" }, onMore = { actions.onMore(i) }, onLink = { actions.onLink(i) })
            }
            item {
                MText(ALBUM_NOTE, MeiroType.Note, c.onSurfaceVariant, "note", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
