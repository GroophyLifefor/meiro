package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro

/**
 * Mock screen `loading` (`V.loading`, "İskelet ekran"): Home while the first read of a new account's lists and plays is
 * on its way and the phone holds nothing of it yet. The top bar "Meiro" with the avatar (no summary button: there is
 * nothing to sum up yet), then the skeleton of Home: greeting, the minutes line, the Daily mix card and four rows. Each
 * block is the mock's `.skel`: a surface-container / surface-container-high / surface-container gradient twice the
 * block's width that sweeps across it every 1.4 s.
 */
@Composable
fun HomeLoadingScreen(animate: Boolean = true) {
    val c = Meiro.colors
    // background-position 200% -> -200% over 1.4 s, ease-in-out, repeated (the mock's `@keyframes sk`).
    val phase: State<Float> = if (animate) rememberInfiniteTransition(label = "skel").animateFloat(
        2f, -2f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "skel.phase",
    ) else remember { mutableFloatStateOf(2f) }
    val base = c.surfaceContainer
    val high = c.surfaceContainerHigh
    Column(Modifier.fillMaxSize()) {
        TopBar("Meiro")
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Skel("skel.title", Modifier.fillMaxWidth(0.7f).height(34.dp), 8.dp, base, high, phase)
            Spacer(Modifier.height(10.dp))
            Skel("skel.sub", Modifier.fillMaxWidth(0.4f).height(14.dp), 8.dp, base, high, phase)
            Spacer(Modifier.height(24.dp))
            Skel("skel.card", Modifier.fillMaxWidth().height(128.dp), 24.dp, base, high, phase)
            Spacer(Modifier.height(24.dp))
            for (n in 1..4) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Skel("skel.row$n.cover", Modifier.size(48.dp), 8.dp, base, high, phase)
                    Column(Modifier.weight(1f)) {
                        Skel("skel.row$n.line1", Modifier.fillMaxWidth(0.7f).height(14.dp), 8.dp, base, high, phase)
                        Spacer(Modifier.height(8.dp))
                        Skel("skel.row$n.line2", Modifier.fillMaxWidth(0.45f).height(12.dp), 8.dp, base, high, phase)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** One `.skel` block: the sweeping gradient, clipped to its radius. */
@Composable
private fun Skel(key: String, modifier: Modifier, radius: Dp, base: Color, high: Color, phase: State<Float>) {
    Box(
        modifier.inv(key, radiusDp = radius.value).clip(RoundedCornerShape(radius)).drawBehind {
            // The gradient image is twice as wide as the block and repeats (CSS background-repeat); background-position
            // p% puts its left edge at p * (block - image) = -p * w.
            val w = size.width
            val left = -phase.value * w
            drawRect(Brush.linearGradient(listOf(base, high, base), start = Offset(left, 0f), end = Offset(left + 2 * w, 0f), tileMode = androidx.compose.ui.graphics.TileMode.Repeated))
        },
    )
}
