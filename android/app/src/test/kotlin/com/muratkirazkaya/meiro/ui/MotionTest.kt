package com.muratkirazkaya.meiro.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.LocalSheetProgress
import com.muratkirazkaya.meiro.ui.components.Motion
import com.muratkirazkaya.meiro.ui.components.SheetPresence
import com.muratkirazkaya.meiro.ui.components.SnackHost
import com.muratkirazkaya.meiro.ui.components.isTabRoute
import com.muratkirazkaya.meiro.ui.components.popEnter
import com.muratkirazkaya.meiro.ui.components.popExit
import com.muratkirazkaya.meiro.ui.components.pushEnter
import com.muratkirazkaya.meiro.ui.components.pushExit
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.screens.MenuSheetFrame
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The motion the mock defines (`.sheet` .28s, `.scrim` .2s, `.snack` .2s, `.sw::after` .15s, `.np` .32s, all
 * `cubic-bezier(.2,0,0,1)` except the ones that name no curve) and the page slide the mock got with it (300 ms).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class MotionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun durationsAndCurvesAreTheMocks() {
        assertEquals(280, Motion.SHEET_MS)
        assertEquals(200, Motion.SCRIM_MS)
        assertEquals(320, Motion.PLAYER_MS)
        assertEquals(200, Motion.SNACK_MS)
        assertEquals(150, Motion.SWITCH_MS)
        assertEquals(300, Motion.PAGE_MS)
        // cubic-bezier(.2,0,0,1) is steeply front-loaded: nearly 90% done at half time; CSS ease is gentler.
        assertEquals(0f, Motion.Standard.transform(0f), 1e-4f)
        assertEquals(1f, Motion.Standard.transform(1f), 1e-4f)
        assertTrue(Motion.Standard.transform(0.5f) > 0.8f)
        assertTrue(Motion.CssEase.transform(0.5f) in 0.7f..0.85f)
        // The 105% of `.sheet { transform: translateY(105%) }`.
        assertEquals(1.05f, Motion.SHEET_CLOSED, 0f)
    }

    @Test fun specsCarryDurationAndEasing() {
        val sheet = Motion.sheetSpec<Float>() as androidx.compose.animation.core.TweenSpec<Float>
        assertEquals(280, sheet.durationMillis); assertEquals(Motion.Standard, sheet.easing)
        val scrim = Motion.scrimSpec<Float>() as androidx.compose.animation.core.TweenSpec<Float>
        assertEquals(200, scrim.durationMillis); assertEquals(Motion.CssEase, scrim.easing)
        val player = Motion.playerSpec<Float>() as androidx.compose.animation.core.TweenSpec<Float>
        assertEquals(320, player.durationMillis); assertEquals(Motion.Standard, player.easing)
        val page = Motion.pageSpec<Float>() as androidx.compose.animation.core.TweenSpec<Float>
        assertEquals(300, page.durationMillis); assertEquals(Motion.Standard, page.easing)
    }

    @Test fun pagesSlideAndTabsDoNot() {
        assertTrue(isTabRoute("home") && isTabRoute("downloads"))
        assertFalse(isTabRoute("album/{id}?tab={tab}"))
        // Opening a detail page: slide in, and the page under it slides back.
        assertEquals(Motion.pageEnter, pushEnter("album/{id}?tab={tab}"))
        assertEquals(Motion.pageExit, pushExit("playlist/{id}"))
        assertNotEquals(EnterTransition.None, pushEnter("settings"))
        // A tab switch (or a jump out to a tab): nothing, as the mock's 'tab' action.
        assertEquals(EnterTransition.None, pushEnter("library"))
        assertEquals(ExitTransition.None, pushExit("library"))
        assertEquals(EnterTransition.None, popEnter("library", "home"))
        assertEquals(ExitTransition.None, popExit("library", "home"))
        // Back: the page slides out, the one under it comes back.
        assertEquals(Motion.pagePopExit, popExit("album/{id}?tab={tab}", "home"))
        assertEquals(Motion.pagePopEnter, popEnter("album/{id}?tab={tab}", "home"))
    }

    @Test fun sheetSlidesInAndStaysDrawnWhileSlidingOut() {
        var open by mutableStateOf<String?>(null)
        var scrimAt = -1f
        var sheetAt = -1f
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MeiroTheme {
                SheetPresence(open) { _ ->
                    val p = LocalSheetProgress.current!!
                    scrimAt = p.scrim.value; sheetAt = p.sheet.value
                    MenuSheetFrame("t", SystemBars(36.dp, 16.dp), { open = null }) { Box(Modifier.size(40.dp).inv("t.body")) }
                }
            }
        }
        rule.onNodeWithTag("t.body", useUnmergedTree = true).assertDoesNotExist()
        open = "x"
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag("t.body", useUnmergedTree = true).assertExists()
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        val mid = sheetAt
        assertTrue("sheet mid-slide, was $mid", mid > 0f && mid < 1f)
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        // Settled: fully open, scrim fully in.
        rule.onNodeWithTag("t.body", useUnmergedTree = true).assertExists()
        // Close: the sheet is still drawn part-way through its slide out.
        open = null
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithTag("t.body", useUnmergedTree = true).assertExists()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("t.body", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun snackFadesOutBeforeItLeaves() {
        var msg by mutableStateOf<String?>("Merhaba")
        rule.mainClock.autoAdvance = false
        rule.setContent { MeiroTheme { Box(Modifier.fillMaxSize()) { SnackHost(msg, { msg = null }, Modifier.inv("snack")) } } }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("snack").assertExists()
        msg = null
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeBy(80)
        rule.onNodeWithTag("snack").assertExists()
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
        rule.onNodeWithTag("snack").assertDoesNotExist()
    }
}
