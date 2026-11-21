package com.muratkirazkaya.meiro.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.LibraryScreen
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mock's demo data for screen `library` (Meiro tab; the YouTube Music tab shows, as in the mock's signed-in state). */
val MockLibraryRows = listOf(
    ListRowData("1", "Daily mix", "Meiro · bugün · 30 parça", CoverSpec(null, 270, 80)),
    ListRowData("2", "Gece Sürüşü", "Meiro · 28 parça · YT 18, SC 10", CoverSpec(null, 280, 80)),
    ListRowData("3", "Simülasyon", "Meiro · Hidra albümünden kopya · 11", CoverSpec(null, 200, 80)),
    ListRowData("4", "Keşifler", "Meiro · 37 parça", CoverSpec(null, 120, 80)),
)

/** Mock screen `library` in both themes (see [checkScreen]). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class LibraryScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) = checkScreen(rule, "library", theme) {
        MeiroShell(Tab.Library, {}, SystemBars(36.dp, 16.dp), SampleMini) { LibraryScreen(MockLibraryRows, {}, {}, ytmTab = true) }
    }
}
