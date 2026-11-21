package com.muratkirazkaya.meiro.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.LibraryEmptyScreen
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Mock screen `empty` in both themes (see [checkScreen]). The mock shows the Kütüphane tab selected on this
 * screen (as in the app). Status bar and gesture pill are system UI: the shell reserves the mock's 36dp and 16dp
 * and the comparison masks them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class EmptyScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) = checkScreen(rule, "empty", theme) {
        MeiroShell(Tab.Library, {}, SystemBars(36.dp, 16.dp), SampleMini) { LibraryEmptyScreen() }
    }
}
