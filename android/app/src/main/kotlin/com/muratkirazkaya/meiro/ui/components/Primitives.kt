package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.size
import com.muratkirazkaya.meiro.ui.inv.InvText
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.inv.invOverflow
import com.muratkirazkaya.meiro.ui.inv.invSingleLine
import com.muratkirazkaya.meiro.ui.inv.invText
import com.muratkirazkaya.meiro.ui.theme.MeiroText

/** Text in one of the mock's styles. [key] tags it for the comparison (style and overflow exported). */
@Composable
fun MText(
    text: String,
    style: MeiroText,
    color: Color,
    key: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    var overflow by remember { mutableStateOf(false) }
    BasicText(
        text = text,
        modifier = modifier.testTag(key).semantics {
            invText = InvText(style, color)
            invOverflow = overflow
            invSingleLine = maxLines == 1
        }.then(if (style.tightLine) Modifier.cssLineBox(style.lineHeightSp) else Modifier),
        style = style.toStyle(color).copy(textAlign = textAlign),
        maxLines = maxLines,
        overflow = if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip,
        onTextLayout = { overflow = it.hasVisualOverflow },
    )
}

/** A mock glyph tinted with [tint]. */
@Composable
fun MIcon(icon: ImageVector, tint: Color, key: String, modifier: Modifier = Modifier, size: Int = 24) {
    Image(
        imageVector = icon,
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size.dp).inv(key, tint = tint),
    )
}

/**
 * CSS line box for a single line whose line-height is smaller than the font's natural height: the box is
 * exactly [lineHeightSp] tall and the glyphs overflow it equally at top and bottom (Compose's own
 * lineHeight never shrinks a line below the font height).
 */
private fun Modifier.cssLineBox(lineHeightSp: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    val h = lineHeightSp.dp.roundToPx()
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}
