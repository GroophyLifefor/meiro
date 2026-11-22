package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.MiniState
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.DetailActions
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.MixPageUi
import com.muratkirazkaya.meiro.ui.screens.MixScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mock's demo data for screen `mix` (`MIX`: s10 playing, s11, s4, s9 downloaded, s12, s6, each with its reason). */
val MockMix = MixPageUi(
    kind = "Bugün için · 30 parça · her sabah yenilenir",
    rows = listOf(
        SongRowData("s10", "Bu Kalp Seni Unutur mu", "Fikret Kızılok · 4:22", true, true, true, CoverSpec(null, 10), now = true, why = "Fikret Kızılok'u sık dinliyorsun"),
        SongRowData("s11", "Cesaretin Var mı Aşka", "Gülay · 3:35", true, false, true, CoverSpec(null, 170), link = LinkBadge.Unknown, why = "Fikret Kızılok dinleyenler de dinliyor"),
        SongRowData("s4", "Nightcall", "Kavinsky · 4:18", true, true, true, CoverSpec(null, 300), why = "Gece Sürüşü listene benziyor"),
        SongRowData("s9", "Son Arzum", "Nilüfer · 3:47", true, false, true, CoverSpec(null, 24), downloaded = true, link = LinkBadge.Unknown, why = "Yeni sanatçı · Gülay ile benzer"),
        SongRowData("s12", "Yolla", "Tarkan · 4:05", true, false, true, CoverSpec(null, 270), why = "Son bir ayda 14 kez dinledin"),
        SongRowData("s6", "After Dark", "Mr.Kitty · 4:19", true, false, true, CoverSpec(null, 330), why = "Kavinsky ile benzer"),
    ),
)

/**
 * Mock screen `mix` in both themes. As on `playlist`, the mock draws "İndirilenler" selected (it reaches the page from
 * another tab); the app keeps the tab the mix was opened from, and the comparison uses the mock's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class MixScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) = checkScreen(rule, "mix", theme) {
        MeiroShell(Tab.Downloads, {}, SystemBars(36.dp, 16.dp), SampleMini) { MixScreen(MockMix, true, DetailActions()) }
    }

    /** Play, shuffle, a row and back reach their actions; download, settings and share are inert. */
    @Test fun controlsCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                MixScreen(
                    MockMix, true,
                    DetailActions(onBack = { calls += "back" }, onPlay = { calls += "play" }, onShuffle = { calls += "shuffle" }, onRow = { calls += "row$it" }),
                )
            }
        }
        for (t in listOf("hero.playBtn", "hero.shuffleBtn", "hero.downloadBtn", "hero.settingsBtn", "hero.shareBtn", "song2.title", "topbar.backBtn")) {
            rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        }
        assertEquals(listOf("play", "shuffle", "row1", "back"), calls)
    }
}

/** Mock screen `mix` scrolled by [SCROLL] px: all six rows with their reasons are on screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class MixScrolledTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) {
        val state = LazyListState()
        checkScreen(
            rule, "mix", theme, keysName = "mix-scrolled", shiftY = SCROLL.toDouble(), variant = "-scrolled",
            beforeCapture = { rule.runOnIdle { runBlocking { state.scrollBy(SCROLL.toFloat()) } } },
        ) {
            MeiroShell(Tab.Downloads, {}, SystemBars(36.dp, 16.dp), SampleMini) { MixScreen(MockMix, true, DetailActions(), state = state) }
        }
    }

    companion object { const val SCROLL = 320 }
}

private const val TITLE_150 = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978, yeniden"
private const val ARTIST_LONG = "Sanatçı Bir, Sanatçı İki, Sanatçı Üç, Çok Uzun İsimli Konuk Sanatçı ve Orkestrası"

/**
 * The mix page with no mock counterpart, judged by the structural rules only: 150-character titles, long artists and
 * reasons (the desktop's English text untranslated, a long seed name), one-letter rows, a 30-track mix and a mix with
 * one track, at 370dp, 320dp, font scale 1.3 and both, light and dark, from the top and scrolled to the end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class MixStressTest {
    @get:Rule val rule = createComposeRule()

    private val mini = MiniState(TITLE_150, ARTIST_LONG, CoverSpec(null, 10), listOf(Provider.Ytm, Provider.Sc), Provider.Ytm, 0.38f)

    private val long = MixPageUi(
        kind = "22 Kasım için · 30 parça · her sabah yenilenir",
        rows = listOf(
            SongRowData("a", TITLE_150, "$ARTIST_LONG · 1:04:06", true, true, true, CoverSpec(null, 36), link = LinkBadge.Unknown, now = true, why = "Yeni sanatçı · $ARTIST_LONG ile benzer"),
            SongRowData("b", "Ö", "a · 0:05", true, false, true, CoverSpec(null, 36), link = LinkBadge.Other, why = "A ile benzer"),
            SongRowData("c", TITLE_150, ARTIST_LONG, false, true, false, CoverSpec(null, 36), downloaded = true, preview = true, why = "Because you listen to $ARTIST_LONG and more"),
            SongRowData("d", "Şarkı", "Sanatçı · 3:00", true, false, true, CoverSpec(null, 36)),
        ) + (5..30).map { SongRowData("r$it", "Şarkı $it", "Sanatçı $it · 4:0${it % 10}", true, false, true, CoverSpec(null, it * 10), why = "Sanatçı ${it - 1} ile benzer") },
    )

    private val short = MixPageUi("Bugün için · 1 parça · her sabah yenilenir", listOf(SongRowData("x", "A", "B", true, false, true, CoverSpec(null, 1), why = "C ile benzer")))

    private fun run(which: String, theme: String, tag: String, w: Int, fs: Float, end: Boolean) {
        val state = LazyListState()
        val name = "mix-$which${if (end) "-end" else ""}-$tag"
        val fails = stressScreen(rule, name, if (end) "mix-scrolled" else "mix", theme, w, fs) {
            if (end) androidx.compose.runtime.LaunchedEffect(Unit) { state.scrollBy(100000f) }
            MeiroShell(Tab.Home, {}, SystemBars(36.dp, 16.dp), mini) { MixScreen(if (which == "long") long else short, true, DetailActions(), state = state) }
        }
        assertTrue("stress $name $theme (${fails.size} failures):\n" + fails.joinToString("\n") { " - $it" }, fails.isEmpty())
    }

    @Test fun long_top_light_w370() = run("long", "light", "w370", 370, 1.0f, false)
    @Test fun long_top_light_w320() = run("long", "light", "w320", 320, 1.0f, false)
    @Test fun long_top_light_w370_f1_3() = run("long", "light", "w370-f1.3", 370, 1.3f, false)
    @Test fun long_top_light_w320_f1_3() = run("long", "light", "w320-f1.3", 320, 1.3f, false)
    @Test fun long_top_dark_w370() = run("long", "dark", "w370", 370, 1.0f, false)
    @Test fun long_top_dark_w320() = run("long", "dark", "w320", 320, 1.0f, false)
    @Test fun long_top_dark_w370_f1_3() = run("long", "dark", "w370-f1.3", 370, 1.3f, false)
    @Test fun long_top_dark_w320_f1_3() = run("long", "dark", "w320-f1.3", 320, 1.3f, false)
    @Test fun long_end_light_w370() = run("long", "light", "w370", 370, 1.0f, true)
    @Test fun long_end_light_w320() = run("long", "light", "w320", 320, 1.0f, true)
    @Test fun long_end_light_w370_f1_3() = run("long", "light", "w370-f1.3", 370, 1.3f, true)
    @Test fun long_end_light_w320_f1_3() = run("long", "light", "w320-f1.3", 320, 1.3f, true)
    @Test fun long_end_dark_w370() = run("long", "dark", "w370", 370, 1.0f, true)
    @Test fun long_end_dark_w320() = run("long", "dark", "w320", 320, 1.0f, true)
    @Test fun long_end_dark_w370_f1_3() = run("long", "dark", "w370-f1.3", 370, 1.3f, true)
    @Test fun long_end_dark_w320_f1_3() = run("long", "dark", "w320-f1.3", 320, 1.3f, true)
    @Test fun short_top_light_w320_f1_3() = run("short", "light", "w320-f1.3", 320, 1.3f, false)
    @Test fun short_top_dark_w370() = run("short", "dark", "w370", 370, 1.0f, false)
}
