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
fun checkScreen(
    rule: ComposeContentTestRule, screen: String, theme: String,
    /** The key map to use when it is not the screen's own (a scrolled state has other rows on screen). */
    keysName: String = screen,
    /** How far [beforeCapture] scrolls the content; the inventory is read as scrolled by as much. */
    shiftY: Double = 0.0,
    /** Appended to the output file names (a scrolled state is not compared pixel by pixel: the reference is unscrolled). */
    variant: String = "",
    beforeCapture: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    rule.setContent {
        MeiroTheme(darkTheme = theme == "dark") { Box(Modifier.size(370.dp, 824.dp)) { content() } }
    }
    rule.waitForIdle()
    beforeCapture()
    rule.waitForIdle()
    val out = File(Dirs.out, screen).also { it.mkdirs() }
    val png = File(out, "app-$theme$variant.png")
    rule.onRoot().captureRoboImage(png.path)

    val pixel = if (shiftY == 0.0) PixelCompare.compare(theme, File(Dirs.mock, "ref/$screen-$theme.png"), png, out) else null
    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { walk(it) }
    val nodes = walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
    val rb = rule.onRoot().fetchSemanticsNode().boundsInRoot
    val keys = KeyMap.load(keysName)
    val summary = ElementCompare.compare(theme, nodes, Rect(rb.left, rb.top, rb.right, rb.bottom), loadInventory(screen, theme, shiftY), keys)
    // An ellipsis the app is allowed is one the mock has too: its inventory lists the element as clipped text.
    val clipped = org.json.JSONObject(File(Dirs.mock, "inventory/$screen.json").readText()).optJSONArray("clippedText")
        ?.let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("path") }.toSet() } ?: emptySet()
    for (k in keys.ellipsis) {
        val path = keys.entries.firstOrNull { it.key == k }?.path
        if (path == null || path !in clipped) summary.failures.add("ELLIPSIS $k: the key map expects an ellipsis but the mock's inventory does not list its element as clipped")
    }

    val pixelLine = pixel?.line() ?: "pixel $theme: not compared (scrolled state; the reference image is unscrolled)"
    File(out, "summary-$theme$variant.txt").writeText(pixelLine + "\n" + summary.line() + "\n" + summary.failures.joinToString("\n") + "\n")
    println(pixelLine); println(summary.line())
    assertTrue(
        "Element comparison against inventory/$screen.json failed for $theme (${summary.failures.size}):\n" +
            summary.failures.joinToString("\n") { " - $it" },
        summary.failures.isEmpty(),
    )
}

/**
 * A render with no mock counterpart: [content] at [widthDp] x 824dp, font scale [fontScale], in [theme]; writes
 * build/check/stress/<name>-<theme>.png and fails on any structural rule (overflow of text that may not truncate,
 * containment, centring, row lines, overlap of a title with its neighbour, bounds outside the screen).
 * [keysOf] names the key map whose centring/row/overlap rules apply.
 */
fun stressScreen(
    rule: ComposeContentTestRule, name: String, keysOf: String, theme: String, widthDp: Int, fontScale: Float,
    content: @Composable () -> Unit,
): List<String> {
    rule.setContent {
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale),
        ) { MeiroTheme(darkTheme = theme == "dark") { Box(Modifier.size(widthDp.dp, 824.dp)) { content() } } }
    }
    rule.waitForIdle()
    val out = File(Dirs.out, "stress").also { it.mkdirs() }
    rule.onRoot().captureRoboImage(File(out, "$name-$theme.png").path)
    fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { walk(it) }
    val nodes = walk(rule.onRoot(useUnmergedTree = true).fetchSemanticsNode())
    val rb = rule.onRoot().fetchSemanticsNode().boundsInRoot
    val fails = ElementCompare.stress(nodes, Rect(rb.left, rb.top, rb.right, rb.bottom), KeyMap.load(keysOf))
    File(out, "$name-$theme.txt").writeText(fails.joinToString("\n") + "\n")
    println("stress $name $theme: ${fails.size} failures")
    return fails
}
