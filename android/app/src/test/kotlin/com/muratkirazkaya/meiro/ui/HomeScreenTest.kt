package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.screens.HomeActions
import com.muratkirazkaya.meiro.ui.screens.HomeScreen
import com.muratkirazkaya.meiro.ui.screens.HomeUi
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.MixUi
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The mock's demo data for screen `home` (signed in to YouTube Music, online, s10 playing): the "Beğenilenler" tile, the
 * "Late Night Set" SoundCloud tile and the "Yeni çıkanlar" shelf are shown here so the comparison covers them; the app
 * has no data for them yet (see PROGRESS.md, M4c deviations).
 */
val MockHome = HomeUi(
    greeting = "İyi akşamlar, Murat",
    minutes = "Bugün 47 dakika dinledin",
    mix = MixUi(
        "Daily mix · bugün", "30 şarkı", "Fikret Kızılok, Gülay, Kavinsky ve 9 yeni sanatçı",
        listOf(CoverSpec(null, 10), CoverSpec(null, 170), CoverSpec(null, 300), CoverSpec(null, 24)),
    ),
    picks = listOf(
        TileData("gece", "Gece Sürüşü", "Meiro listesi · 28", CoverSpec(null, 280, 80), Provider.Meiro),
        TileData("liked", "Beğenilenler", "YouTube Music · 1.204", CoverSpec(null, 350, 80), Provider.Ytm),
        TileData("sim", "Simülasyon", "Hidra · Meiro kopyası", CoverSpec(null, 200, 80), Provider.Meiro),
        TileData("late", "Late Night Set", "SoundCloud seti", CoverSpec(null, 20, 80), Provider.Sc),
    ),
    resume = listOf(
        SongRowData("s10", "Bu Kalp Seni Unutur mu", "Fikret Kızılok · 4:22", true, true, true, CoverSpec(null, 10), now = true),
        SongRowData("s4", "Nightcall", "Kavinsky · 4:18", true, true, true, CoverSpec(null, 300)),
        SongRowData("s5", "Stolen Dance", "Milky Chance · 5:13", false, true, false, CoverSpec(null, 150), downloaded = true),
    ),
    releases = listOf(
        TileData("ram", "Random Access Memories", "Daft Punk · Albüm", CoverSpec(null, 36, 80)),
        TileData("outrun", "OutRun", "Kavinsky · Albüm", CoverSpec(null, 300, 80)),
        TileData("time", "Time", "Mr.Kitty · Albüm", CoverSpec(null, 330, 80)),
    ),
)

/** Mock screen `home` in both themes; the rows below the visible area are checked by [HomeScrolledTest]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class HomeScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) = checkScreen(rule, "home", theme) {
        MeiroShell(Tab.Home, {}, SystemBars(36.dp, 16.dp), SampleMini) { HomeScreen(MockHome, true, HomeActions()) }
    }

    /** The taps the mock wires: a quick pick opens its list, the mix's play button plays it. */
    @Test fun tapsReachTheirActions() {
        var pick = -1; var mix = 0
        rule.setContent {
            MeiroTheme(darkTheme = false) {
                MeiroShell(Tab.Home, {}, SystemBars(36.dp, 16.dp), SampleMini) {
                    HomeScreen(MockHome, true, HomeActions(onPick = { pick = it }, onPlayMix = { mix++ }))
                }
            }
        }
        rule.onNodeWithTag("pick2.title", useUnmergedTree = true).performClick()
        assertEquals(1, pick)
        rule.onNodeWithTag("mix.playBtn").performClick()
        assertEquals(1, mix)
    }

    /**
     * The mock's third and fourth quick picks lie right of the screen (inventory: offscreen, so no comparison checks
     * them); the shelf scrolls to them and they are tiles like the first two.
     */
    @Test fun lastPicksAreReachedByScrollingTheShelf() {
        rule.setContent {
            MeiroTheme(darkTheme = false) {
                MeiroShell(Tab.Home, {}, SystemBars(36.dp, 16.dp), SampleMini) { HomeScreen(MockHome, true, HomeActions()) }
            }
        }
        rule.onNodeWithTag("pick4.title", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("pick1.title", useUnmergedTree = true).performTouchInput { swipeLeft() }
        rule.onNodeWithTag("pick4.title", useUnmergedTree = true).assertTextEquals("Late Night Set")
        rule.onNodeWithTag("pick4.meta", useUnmergedTree = true).assertTextEquals("SoundCloud seti")
        rule.onNodeWithTag("pick4.logo", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("pick3.title", useUnmergedTree = true).assertTextEquals("Simülasyon")
    }

    /** Without a mix, lists or plays (a new account) the screen is the greeting and the minutes line. */
    @Test fun emptySectionsAreLeftOut() {
        rule.setContent {
            MeiroTheme(darkTheme = false) {
                MeiroShell(Tab.Home, {}, SystemBars(36.dp, 16.dp), null) { HomeScreen(HomeUi("Günaydın", "Bugün 0 dakika dinledin"), false, HomeActions()) }
            }
        }
        rule.onNodeWithTag("hello").assertExists()
        rule.onNodeWithTag("sub").assertExists()
        for (k in listOf("forYou.title", "mix.cv", "quick.title", "resume.title", "fresh.title")) rule.onNodeWithTag(k).assertDoesNotExist()
    }
}

/**
 * Mock screen `home` scrolled to its end: "Kaldığın yerden" (the playing row with the equaliser, a downloaded mark) and
 * the "Yeni çıkanlar" shelf come into view. The inventory is read as scrolled by [SCROLL] px; elements cut by the
 * viewport are skipped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class HomeScrolledTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) {
        val state = LazyListState()
        checkScreen(
            rule, "home", theme, keysName = "home-scrolled", shiftY = SCROLL.toDouble(), variant = "-scrolled",
            beforeCapture = { rule.runOnIdle { runBlocking { state.scrollBy(SCROLL.toFloat()) } } },
        ) {
            MeiroShell(Tab.Home, {}, SystemBars(36.dp, 16.dp), SampleMini) { HomeScreen(MockHome, true, HomeActions(), state) }
        }
    }

    companion object { const val SCROLL = 476 }
}
