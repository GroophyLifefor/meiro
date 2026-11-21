package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.muratkirazkaya.meiro.ui.compare.Dirs
import com.muratkirazkaya.meiro.ui.compare.ElementCompare
import com.muratkirazkaya.meiro.ui.compare.KeyMap
import com.muratkirazkaya.meiro.ui.compare.PixelCompare
import com.muratkirazkaya.meiro.ui.compare.loadInventory
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.LibraryEmptyScreen
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders mock screen `empty` (370x824dp at mdpi, so 1dp = 1px like the reference PNG) in both themes,
 * writes the PNG, the pixel comparison against docs/android/mock/ref/empty-<theme>.png (reported, not a
 * gate) and the element comparison against inventory/empty.json (the gate: any mismatch fails).
 *
 * The mock shows the Ara tab selected on this screen, so the shell is rendered with Tab.Search; in the
 * app the Kütüphane tab is selected here. Status bar and gesture pill are system UI: the shell reserves
 * the mock's 36dp and 16dp and the comparison masks them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class EmptyScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) {
        rule.setContent {
            MeiroTheme(darkTheme = theme == "dark") {
                Box(Modifier.size(370.dp, 824.dp)) {
                    MeiroShell(Tab.Search, {}, SystemBars(36.dp, 16.dp), SampleMini) { LibraryEmptyScreen() }
                }
            }
        }
        rule.waitForIdle()
        val out = File(Dirs.out, "empty").also { it.mkdirs() }
        val png = File(out, "app-$theme.png")
        rule.onRoot().captureRoboImage(png.path)

        val pixel = PixelCompare.compare(theme, File(Dirs.mock, "ref/empty-$theme.png"), png, out)
        val nodes = rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
            fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
                listOf(n) + n.children.flatMap { walk(it) }
            walk(root)
        }
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val summary = ElementCompare.compare(theme, nodes, Rect(rootBounds.left, rootBounds.top, rootBounds.right, rootBounds.bottom),
            loadInventory("empty", theme), KeyMap.load("empty"))

        File(out, "summary-$theme.txt").writeText(
            pixel.line() + "\n" + summary.line() + "\n" + summary.failures.joinToString("\n") + "\n",
        )
        println(pixel.line()); println(summary.line())
        assertTrue(
            "Element comparison against inventory/empty.json failed for $theme (${summary.failures.size}):\n" +
                summary.failures.joinToString("\n") { " - $it" },
            summary.failures.isEmpty(),
        )
    }
}
