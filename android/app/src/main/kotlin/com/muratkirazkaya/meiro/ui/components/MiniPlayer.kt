package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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

/**
 * What the mini player shows. [SampleMini] is the mock's now-playing sample (screenshot tests); in the app it comes
 * from the playback state ([com.muratkirazkaya.meiro.playback.PlayerUi]).
 */
data class MiniState(
    val title: String, val artist: String, val cover: CoverSpec, val sources: List<Provider>, val active: Provider, val progress: Float,
    val playing: Boolean = true,
    /** The track's audio is being found (the mock's "Hazırlanıyor…" with spinners). */
    val preparing: Boolean = false,
    /** Why the track did not play (the mock's `playerr`): shown where the artist was, in the error colour. */
    val error: String? = null,
)

/**
 * The mini player's state for what plays: the track's own picture (the gradient, hue from the track key, only when it has
 * none) and the sources the track has, the one it plays from active (the mock's `srcIcons`).
 */
fun com.muratkirazkaya.meiro.playback.PlayerUi.toMini(): MiniState {
    val active = item.provider().toProvider()
    val srcs = item.sources.map { it.toProvider() }.ifEmpty { listOf(active) }.let { if (active in it) it else it + active }
    return MiniState(
        title = item.title.ifEmpty { item.key },
        artist = item.artist,
        cover = CoverSpec(item.thumbnail.takeIf { it.isNotBlank() }, Math.floorMod(item.key.hashCode(), 360)),
        sources = srcs,
        active = active,
        progress = progress,
        playing = playing,
        preparing = loading,
        error = error,
    )
}

private fun String.toProvider() = if (this == com.muratkirazkaya.meiro.data.Source.SOUNDCLOUD) Provider.Sc else Provider.Ytm

val SampleMini = MiniState("Bu Kalp Seni Unutur mu", "Fikret Kızılok", CoverSpec(null, 10), listOf(Provider.Ytm, Provider.Sc), Provider.Ytm, 0.38f)

/** The mock's cover placeholder: linear-gradient(135deg, hsl(h 55% 52%), hsl(h+50 60% 26%)). */
fun coverBrush(hue: Int, spread: Int = 50) = Brush.linearGradient(
    listOf(Color.hsl(hue.toFloat(), 0.55f, 0.52f), Color.hsl(((hue + spread) % 360).toFloat(), 0.60f, 0.26f)),
)

@Composable
fun MiniPlayer(state: MiniState, modifier: Modifier = Modifier, onPlayPause: () -> Unit = {}, onNext: () -> Unit = {}, onOpen: () -> Unit = {}) {
    val c = Meiro.colors
    Box(
        modifier.fillMaxWidth().height(64.dp).inv("mini", bg = c.surfaceContainerHigh, radiusDp = 16f)
            .clip(MeiroShapes.Mini).background(c.surfaceContainerHigh)
            // A tap opens the full player; a plain tap detector, so the semantics of the parts stay their own (not merged).
            .pointerInput(onOpen) { detectTapGestures { onOpen() } },
    ) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(start = 8.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(state.cover, 10.dp, "mini.cover", Modifier.size(48.dp))
            Column(Modifier.weight(1f)) {
                MText(state.title, MeiroType.MiniTitle, c.onSurface, "mini.title", Modifier.fillMaxWidth(), maxLines = 1)
                if (state.error != null && !state.preparing) {
                    // The mock's `span.err`: the reason, one line with an ellipsis; the full player shows all of it.
                    MText(state.error, MeiroType.MiniArtist, c.error, "mini.err", maxLines = 1)
                } else Row(
                    Modifier.fillMaxWidth().height(20.dp).inv("mini.artist"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.preparing) {
                        // The mock's `mini()` while preparing: a 12px spinner (2px ring) and "Hazırlanıyor…".
                        Spinner("mini.prep.spinner", 12, 2f, c.outlineVariant, c.primary)
                        MText("Hazırlanıyor…", MeiroType.MiniArtist, c.onSurfaceVariant, "mini.prep.label", maxLines = 1)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (Provider.Ytm in state.sources) SourceLogo("mini.src.ytm", Provider.Ytm, dim = state.active != Provider.Ytm)
                            if (Provider.Sc in state.sources) SourceLogo("mini.src.sc", Provider.Sc, dim = state.active != Provider.Sc)
                        }
                        MText(state.artist, MeiroType.MiniArtist, c.onSurfaceVariant, "mini.artist.label", maxLines = 1)
                    }
                }
            }
            if (state.preparing) {
                Box(Modifier.size(48.dp).inv("mini.playBtn", radiusDp = 24f), Alignment.Center) { Spinner("mini.play.spinner", 22, 3f, c.outlineVariant, c.primary) }
            } else IconButton("mini.playBtn", "mini.play.icon", if (state.playing) MeiroIcons.Pause else MeiroIcons.Play, c.onSurface, onPlayPause)
            IconButton("mini.nextBtn", "mini.next.icon", MeiroIcons.Next, c.onSurface, onNext)
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

/** The mock's 48dp circular icon button around a 24dp glyph. */
@Composable
fun IconButton(key: String, iconKey: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit = {}, iconSize: Int = 24, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).inv(key, radiusDp = 24f).clip(CircleShape).clickableNoIndication(onClick),
        Alignment.Center,
    ) { MIcon(icon, tint, iconKey, size = iconSize) }
}
