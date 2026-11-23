package com.muratkirazkaya.meiro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.inv.inv

/**
 * The mock's `.spinner`: a [sizeDp] circle whose [ringDp] border is [ring] with the top quarter [head], turning once a
 * second. The border lies inside the box, as CSS draws it.
 */
@Composable
fun Spinner(key: String?, sizeDp: Int, ringDp: Float, ring: Color, head: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "spin")
    Canvas(modifier.size(sizeDp.dp).then(if (key != null) Modifier.inv(key, radiusDp = sizeDp / 2f) else Modifier).rotate(angle)) {
        val w = ringDp.dp.toPx()
        val inset = w / 2
        val arcSize = Size(size.width - w, size.height - w)
        drawArc(ring, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        // border-top: the quarter between the top-left and top-right corners.
        drawArc(head, 225f, 90f, false, Offset(inset, inset), arcSize, style = Stroke(w))
    }
}
