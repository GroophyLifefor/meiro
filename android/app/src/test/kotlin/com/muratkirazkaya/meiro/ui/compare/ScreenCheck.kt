package com.muratkirazkaya.meiro.ui.compare

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import org.junit.Assert.assertTrue
import java.io.File

/**
 * Renders [content] as mock screen [screen] (370x824dp at mdpi, so 1dp = 1px like the reference PNG) in [theme],
 * writes build/check/<screen>/app-<theme>.png, the pixel comparison against ref/<screen>-<theme>.png (reported, not
 * a gate) and the element comparison against inventory/<screen>.json with mock-keys/<screen>.json (the gate:
 * any mismatch fails).
 */
fun checkScreen(rule: ComposeContentTestRule, screen: String, theme: String, content: @Composable () -> Unit) {
    rule.setContent {
        MeiroTheme(darkTheme = theme == "dark") { Box(Modifier.size(370.dp, 824.dp)) { content() } }
    }
    rule.waitForIdle()
    val out = File(Dirs.out, screen).also { it.mkdirs() }
    val png = File(out, "app-$theme.png")
    rule.onRoot().captureRoboImage(png.path)

    val pixel = PixelCompare.compare(theme, File(Dirs.mock, "ref/$screen-$theme.png"), png, out)
    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { walk(it) }
    val nodes = walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
    val rb = rule.onRoot().fetchSemanticsNode().boundsInRoot
    val summary = ElementCompare.compare(theme, nodes, Rect(rb.left, rb.top, rb.right, rb.bottom), loadInventory(screen, theme), KeyMap.load(screen))

    File(out, "summary-$theme.txt").writeText(pixel.line() + "\n" + summary.line() + "\n" + summary.failures.joinToString("\n") + "\n")
    println(pixel.line()); println(summary.line())
    assertTrue(
        "Element comparison against inventory/$screen.json failed for $theme (${summary.failures.size}):\n" +
            summary.failures.joinToString("\n") { " - $it" },
        summary.failures.isEmpty(),
    )
}
