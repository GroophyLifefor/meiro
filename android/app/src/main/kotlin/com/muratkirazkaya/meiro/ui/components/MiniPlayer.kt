package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.icons.MeiroLogos
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroShapes
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** Placeholder content until playback exists (M3): the mock's now-playing sample. */
data class MiniState(val title: String, val artist: String, val hue: Int, val activeIsYtm: Boolean, val progress: Float)

val SampleMini = MiniState("Bu Kalp Seni Unutur mu", "Fikret Kızılok", 10, true, 0.38f)

/** The mock's cover placeholder: linear-gradient(135deg, hsl(h 55% 52%), hsl(h+50 60% 26%)). */
fun coverBrush(hue: Int, spread: Int = 50) = Brush.linearGradient(
    listOf(Color.hsl(hue.toFloat(), 0.55f, 0.52f), Color.hsl(((hue + spread) % 360).toFloat(), 0.60f, 0.26f)),
)

@Composable
fun MiniPlayer(state: MiniState, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    Box(
        modifier.fillMaxWidth().height(64.dp).inv("mini", bg = c.surfaceContainerHigh, radiusDp = 16f)
            .clip(MeiroShapes.Mini).background(c.surfaceContainerHigh),
    ) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(start = 8.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val coverShape = RoundedCornerShape(10.dp)
            Box(
                Modifier.size(48.dp).inv("mini.cover", radiusDp = 10f).clip(coverShape)
                    .background(coverBrush(state.hue)),
            )
            Column(Modifier.weight(1f)) {
                MText(state.title, MeiroType.MiniTitle, c.onSurface, "mini.title", Modifier.fillMaxWidth(), maxLines = 1)
                Row(
                    Modifier.fillMaxWidth().height(20.dp).inv("mini.artist"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Logo("mini.src.ytm", MeiroLogos.Ytm, dim = !state.activeIsYtm)
                        Logo("mini.src.sc", MeiroLogos.Sc, dim = state.activeIsYtm)
                    }
                    MText(state.artist, MeiroType.MiniArtist, c.onSurfaceVariant, "mini.artist.label", maxLines = 1)
                }
            }
            IconButton("mini.playBtn", "mini.play.icon", MeiroIcons.Pause, c.onSurface)
            IconButton("mini.nextBtn", "mini.next.icon", MeiroIcons.Next, c.onSurface)
        }
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp).height(2.dp)
                .inv("mini.bar", bg = c.outlineVariant, radiusDp = 1f)
                .clip(RoundedCornerShape(1.dp)).background(c.outlineVariant),
        ) {
            Box(
                Modifier.fillMaxWidth(state.progress).fillMaxHeight()
                    .inv("mini.bar.fill", bg = c.primary, radiusDp = 1f)
                    .clip(RoundedCornerShape(1.dp)).background(c.primary),
            )
        }
    }
}

@Composable
private fun Logo(key: String, icon: androidx.compose.ui.graphics.vector.ImageVector, dim: Boolean) {
    Image(icon, null, Modifier.size(14.dp).alpha(if (dim) 0.35f else 1f).inv(key))
}

/** The mock's 48dp circular icon button around a 24dp glyph. */
@Composable
fun IconButton(key: String, iconKey: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit = {}) {
    Box(
        Modifier.size(48.dp).inv(key, radiusDp = 24f).clip(CircleShape).clickableNoIndication(onClick),
        Alignment.Center,
    ) { MIcon(icon, tint, iconKey) }
}
