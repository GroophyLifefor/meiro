package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.AlbumTile
import com.muratkirazkaya.meiro.ui.components.Cover
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.MixBrush
import com.muratkirazkaya.meiro.ui.components.IconButton
import com.muratkirazkaya.meiro.ui.components.MIcon
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.SectionHead
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.components.clickableNoIndication
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType
import java.util.Locale

/** The Daily mix card: "Daily mix · bugün", "30 şarkı", the artist line and up to four track covers (2 x 2). */
data class MixUi(val kicker: String, val title: String, val artists: String, val covers: List<CoverSpec>)

/**
 * What the home screen shows (mock screen `home`, `V.home`). A section whose data is empty is left out: [mix] null hides
 * "Senin için", no [picks] hides "Hızlı seçimler", no [resume] hides "Kaldığın yerden", no [releases] hides the
 * YouTube Music shelf (the mock shows it only with a YouTube Music sign-in, which the app does not have yet).
 */
data class HomeUi(
    val greeting: String,
    val minutes: String,
    val mix: MixUi? = null,
    val picks: List<TileData> = emptyList(),
    val resume: List<SongRowData> = emptyList(),
    val releases: List<TileData> = emptyList(),
)

class HomeActions(
    val onStats: () -> Unit = {},
    val onMix: () -> Unit = {},
    val onPlayMix: () -> Unit = {},
    val onPick: (Int) -> Unit = {},
    val onHistory: () -> Unit = {},
    val onResume: (Int) -> Unit = {},
    /** ⋮ of a "Kaldığın yerden" row: the track menu. */
    val onResumeMore: (Int) -> Unit = {},
    val onReleases: () -> Unit = {},
    val onRelease: (Int) -> Unit = {},
)

private val MixPlayIcon = Color(0xFF1C1B1F)
private val Turkish = Locale.forLanguageTag("tr")

/**
 * Mock screen `home`: top bar "Meiro" with the summary (stats) button and the avatar, the greeting, today's listening
 * time, "Senin için" with the Daily mix card, "Hızlı seçimler" (a shelf of lists), "Kaldığın yerden" (recent plays)
 * with "Geçmiş", and the YouTube Music "Yeni çıkanlar" shelf.
 */
@Composable
fun HomeScreen(ui: HomeUi, playing: Boolean, actions: HomeActions, state: LazyListState = rememberLazyListState()) {
    val c = Meiro.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("Meiro") { IconButton("topbar.statsBtn", "topbar.stats.icon", MeiroIcons.Stats, c.onSurfaceVariant, actions.onStats) }
        LazyColumn(Modifier.fillMaxSize(), state) {
            item(key = "hello") {
                Box(Modifier.fillMaxWidth().inv("hello")) {
                    MText(ui.greeting, MeiroType.Greeting, c.onSurface, "hello.label", Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            item(key = "sub") {
                Box(Modifier.fillMaxWidth().inv("sub")) {
                    MText(ui.minutes, MeiroType.GreetingSub, c.onSurfaceVariant, "sub.label", Modifier.padding(horizontal = 16.dp))
                }
            }
            ui.mix?.let { mix ->
                item(key = "mix.head") { SectionHead("Senin için", "forYou.title") }
                item(key = "mix") { MixCard(mix, actions.onMix, actions.onPlayMix) }
            }
            if (ui.picks.isNotEmpty()) {
                item(key = "picks.head") { SectionHead("Hızlı seçimler", "quick.title") }
                item(key = "picks") {
                    LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(ui.picks, key = { i, t -> "p$i/${t.key}" }) { i, t -> AlbumTile("pick${i + 1}", t) { actions.onPick(i) } }
                    }
                }
            }
            if (ui.resume.isNotEmpty()) {
                item(key = "resume.head") { SectionHead("Kaldığın yerden", "resume.title", "Geçmiş", "resume.historyBtn", actions.onHistory) }
                itemsIndexed(ui.resume, key = { i, s -> "r$i/${s.key}" }) { i, s -> SongRow("resume${i + 1}", s, playing, { actions.onResume(i) }, onMore = { actions.onResumeMore(i) }) }
            }
            if (ui.releases.isNotEmpty()) {
                item(key = "releases.head") { SectionHead("YouTube Music: Yeni çıkanlar", "fresh.title", "Tümü", "fresh.allBtn", actions.onReleases) }
                item(key = "releases") {
                    LazyRow(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(ui.releases, key = { i, t -> "n$i/${t.key}" }) { i, t -> AlbumTile("rel${i + 1}", t) { actions.onRelease(i) } }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * The mock's `.mix`: 24dp radius, 16dp padding, the 96dp 2 x 2 cover grid (16dp radius, shadow), the kicker (12px,
 * upper case, 85%), the title (22/28 600), the artist line (13/18, 90%) and the white 48dp play button in the bottom
 * right corner (the text keeps 52dp clear of it).
 */
@Composable
private fun MixCard(mix: MixUi, onOpen: () -> Unit, onPlay: () -> Unit) {
    val white = Color.White
    Box(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MixBrush).clickableNoIndication(onOpen),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
            val grid = RoundedCornerShape(16.dp)
            Column(Modifier.size(96.dp).shadow(6.dp, grid).inv("mix.cv", radiusDp = 16f).clip(grid)) {
                for (r in 0..1) Row {
                    for (col in 0..1) {
                        val i = r * 2 + col
                        val spec = mix.covers.getOrNull(i) ?: mix.covers.lastOrNull() ?: CoverSpec(null, 270)
                        Cover(spec, 0.dp, "mix.cv${i + 1}", Modifier.size(48.dp))
                    }
                }
            }
            Column(Modifier.weight(1f).padding(end = 52.dp)) {
                // The kicker and the title wrap as the mock's CSS does (a narrow screen at a large font).
                MText(mix.kicker.uppercase(Turkish), MeiroType.MixKicker, white, "mix.kind", Modifier.fillMaxWidth().alpha(0.85f))
                MText(mix.title, MeiroType.MixTitle, white, "mix.title", Modifier.padding(top = 2.dp).fillMaxWidth())
                // At most three lines (the mock's text takes three); longer artist names end in an ellipsis.
                MText(mix.artists, MeiroType.MixBody, white, "mix.artists", Modifier.padding(top = 4.dp, bottom = 10.dp).fillMaxWidth().alpha(0.9f), maxLines = 3, ellipsis = true)
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp).size(48.dp)
                .inv("mix.playBtn", bg = white, radiusDp = 24f).clip(CircleShape).background(white).clickableNoIndication(onPlay),
            Alignment.Center,
        ) { MIcon(MeiroIcons.Play, MixPlayIcon, "mix.play.icon", size = 26) }
    }
}
