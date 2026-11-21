package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.PlaylistScreen
import com.muratkirazkaya.meiro.ui.screens.PlaylistUi
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun song(k: String, t: String, a: String, ytm: Boolean, sc: Boolean, activeYtm: Boolean, hue: Int, dl: Boolean = false, link: LinkBadge = LinkBadge.None, now: Boolean = false) =
    SongRowData(k, t, a, ytm, sc, activeYtm, CoverSpec(null, hue), dl, link, now)

/** The mock's demo data for screen `playlist` (list "Gece Sürüşü"). */
val MockPlaylist = PlaylistUi(
    title = "Gece Sürüşü",
    cover = CoverSpec(null, 280, 80),
    kind = "Meiro listesi · 28 parça · 1 sa 52 dk",
    summary = "YT 18 · SC 10 · eşleşen 24",
    rows = listOf(
        song("s4", "Nightcall", "Kavinsky · 4:18", true, true, true, 300),
        song("s5", "Stolen Dance", "Milky Chance · 5:13", false, true, false, 150, dl = true),
        song("s6", "After Dark", "Mr.Kitty · 4:19", true, false, true, 330),
        song("s7", "Gece Yarısı (canlı)", "Anıl Kaya · 3:41", false, true, false, 220, link = LinkBadge.Unknown),
        song("s8", "Bedroom Demo 3", "kendi kaydım · 2:12", false, true, false, 90, link = LinkBadge.Other),
        song("s10", "Bu Kalp Seni Unutur mu", "Fikret Kızılok · 4:22", true, true, true, 10, now = true),
        song("s12", "Yolla", "Tarkan · 4:05", true, false, true, 270),
    ),
    similar = listOf(
        song("s1", "Get Lucky", "Daft Punk, Pharrell Williams · 4:08", true, true, true, 36),
        song("s11", "Cesaretin Var mı Aşka", "Gülay · 3:35", true, false, true, 170, link = LinkBadge.Unknown),
    ),
)

/**
 * Mock screen `playlist` in both themes. The mock's bottom bar has "İndirilenler" selected on this screen (the mock
 * reaches it from another tab); the app keeps "Kütüphane" selected, which the comparison cannot express, so the test
 * uses the mock's state. The tracks beyond the visible area (offscreen in the inventory) are skipped by the comparison;
 * [PlaylistScrolledTest] checks them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class PlaylistScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) = checkScreen(rule, "playlist", theme) {
        MeiroShell(Tab.Downloads, {}, SystemBars(36.dp, 16.dp), SampleMini) { PlaylistScreen(MockPlaylist, true, {}, {}, {}, {}) }
    }
}

/**
 * Mock screen `playlist` scrolled to the end (the inventory's offscreen elements come into view): the link buttons,
 * the track that is playing (equaliser), the downloaded mark and "Benzer şarkılar". Same comparison, the inventory read
 * as scrolled by [SCROLL] px, elements cut by the viewport skipped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class PlaylistScrolledTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) {
        val state = LazyListState()
        checkScreen(
            rule, "playlist", theme, keysName = "playlist-scrolled", shiftY = SCROLL.toDouble(), variant = "-scrolled",
            beforeCapture = { rule.runOnIdle { runBlocking { state.scrollBy(SCROLL.toFloat()) } } },
        ) {
            MeiroShell(Tab.Downloads, {}, SystemBars(36.dp, 16.dp), SampleMini) { PlaylistScreen(MockPlaylist, true, {}, {}, {}, {}, state) }
        }
    }

    companion object { const val SCROLL = 528 }
}
