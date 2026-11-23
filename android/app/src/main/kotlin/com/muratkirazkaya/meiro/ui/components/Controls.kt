package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/**
 * The mock's `.seg`: a 40dp outlined pill (1dp outline, 20dp radius, 16dp side margins) of equal buttons with a 1dp line
 * between them; the chosen one has the secondary container. [keys] tag the buttons (their labels are "<key>.label").
 */
@Composable
fun Segmented(key: String, labels: List<String>, keys: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(20.dp)
    val line = c.outline
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).height(40.dp).inv(key, radiusDp = 20f)
            .clip(shape).border(1.dp, c.outline, shape).padding(1.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().inv(keys[i], bg = if (on) c.secondaryContainer else null)
                    .then(if (on) Modifier.background(c.secondaryContainer) else Modifier)
                    .then(if (i > 0) Modifier.drawBehind { drawLine(line, Offset(0.5f, 0f), Offset(0.5f, size.height), 1.dp.toPx()) } else Modifier)
                    .clickableNoIndication { onSelect(i) },
                Alignment.Center,
            ) { MText(label, MeiroType.Segment, if (on) c.onSecondaryContainer else c.onSurface, "${keys[i]}.label", maxLines = 1) }
        }
    }
}

/** The mock's `.sw`: 52x32 with a 2px border; on, primary with a 24px knob at the end; off, the outline colours with a 16px knob. */
@Composable
fun MeiroSwitch(key: String, on: Boolean, onClick: () -> Unit) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(16.dp)
    val bg = if (on) c.primary else c.surfaceContainerHighest
    Box(
        Modifier.size(52.dp, 32.dp).inv(key, bg = bg, radiusDp = 16f).clip(shape).background(bg)
            .border(2.dp, if (on) c.primary else c.outline, shape).clickableNoIndication(onClick),
    ) {
        val knob = if (on) c.onPrimary else c.outline
        Box(
            Modifier.offset(x = if (on) 24.dp else 8.dp, y = if (on) 4.dp else 8.dp).size(if (on) 24.dp else 16.dp)
                .inv("$key.knob", bg = knob, radiusDp = if (on) 12f else 8f).clip(CircleShape).background(knob),
        )
    }
}

/** The mock's `.btn.text`: 40dp, 20dp radius, 12dp sides, the 14/500 label in primary. */
@Composable
fun TextButton(key: String, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val c = Meiro.colors
    Box(
        Modifier.height(40.dp).inv(key, radiusDp = 20f).clip(RoundedCornerShape(20.dp))
            .clickableNoIndication { if (enabled) onClick() }.padding(horizontal = 12.dp),
        Alignment.Center,
    ) { MText(label, MeiroType.ButtonLabel, if (enabled) c.primary else c.primary.copy(alpha = 0.38f), "$key.label", maxLines = 1) }
}

/**
 * The mock's `.slider`: a 120x4 track (surface-container-highest) with the primary fill to [value] (0..1) and a 4x20
 * thumb at its end. A tap or a drag sets the value; [onChange] gets it as the finger moves, [onDone] when it lifts.
 */
@Composable
fun MeiroSlider(key: String, value: Float, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    val c = Meiro.colors
    var width by remember { mutableIntStateOf(1) }
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val v = value.coerceIn(0f, 1f)
    // The touch area is 32dp high around the 4dp track; the thumb hangs 8dp above and below the track.
    Box(
        Modifier.width(120.dp).height(32.dp).onSizeChanged { width = maxOf(1, it.width) }
            .pointerInput(Unit) { detectTapGestures { change((it.x / width).coerceIn(0f, 1f)); done() } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { done() }) { ch, _ -> change((ch.position.x / width).coerceIn(0f, 1f)) }
            },
        Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).inv(key, bg = c.surfaceContainerHighest, radiusDp = 2f).clip(RoundedCornerShape(2.dp)).background(c.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(v).fillMaxHeight().inv("$key.fill", bg = c.primary, radiusDp = 2f).clip(RoundedCornerShape(2.dp)).background(c.primary))
        }
        Box(
            Modifier.offset(x = (120 * v).dp).width(4.dp).height(20.dp)
                .inv("$key.thumb", bg = c.primary, radiusDp = 2f).clip(RoundedCornerShape(2.dp)).background(c.primary),
        )
    }
}
