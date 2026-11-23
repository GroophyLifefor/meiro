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
import com.muratkirazkaya.meiro.ui.components.HeroPlayButton
import com.muratkirazkaya.meiro.ui.components.IconButton
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.MixBrush
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** What the Daily mix page shows (`V.mix`). [kind] is "Bugün için · N parça · her sabah yenilenir"; each row carries its reason. */
data class MixPageUi(
    val kind: String,
    val rows: List<SongRowData>,
)

/** The mock's note over the mix's tracks. */
const val MIX_NOTE = "Neden önerildiği her şarkının altında. Bir şarkıyı beğenmezsen ⋮ → \"Bunu önerme\"."

/** The mix page's cover: the mix's own gradient, not a picture (the mock's `.big` with the `.mix` background). */
val MixCover = CoverSpec(brush = MixBrush)

/**
 * Mock screen `mix`: top bar (back and ⋮; no avatar), the hero (the mix's gradient, "Daily mix", Meiro logo and kind
 * line; download, shuffle, play, settings, share), the note, and the tracks, each with why it was recommended under its
 * meta. Download, settings, share, the top bar's ⋮ (the mock opens nothing) and the link buttons are inert; a track's ⋮ opens the track menu.
 */
@Composable
fun MixScreen(
    ui: MixPageUi,
    playing: Boolean,
    actions: DetailActions,
    state: LazyListState = rememberLazyListState(),
) {
    val c = Meiro.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("", onBack = actions.onBack, avatar = false) {
            IconButton("topbar.moreBtn", "topbar.more.icon", MeiroIcons.More, c.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize(), state) {
            item(key = "hero") {
                DetailHero(MixCover, "Daily mix", ui.kind, Provider.Meiro) {
                    IconButton("hero.downloadBtn", "hero.download.icon", MeiroIcons.Download, c.onSurfaceVariant)
                    IconButton("hero.shuffleBtn", "hero.shuffle.icon", MeiroIcons.Shuffle, c.onSurfaceVariant, actions.onShuffle)
                    HeroPlayButton(actions.onPlay)
                    IconButton("hero.settingsBtn", "hero.settings.icon", MeiroIcons.Settings, c.onSurfaceVariant)
                    IconButton("hero.shareBtn", "hero.share.icon", MeiroIcons.Share, c.onSurfaceVariant)
                }
            }
            item(key = "note") {
                // `.note` (6dp 16dp margins) with the page's own margin-bottom of 8px.
                MText(MIX_NOTE, MeiroType.Note, c.onSurfaceVariant, "note", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp))
            }
            itemsIndexed(ui.rows, key = { i, r -> "$i/${r.key}" }) { i, r ->
                SongRow("song${i + 1}", r, playing, { actions.onRow(i) }, onMore = { actions.onMore(i) })
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}
