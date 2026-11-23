package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.playback.RepeatMode
import com.muratkirazkaya.meiro.playback.clockText
import com.muratkirazkaya.meiro.ui.components.Cover
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.IconButton
import com.muratkirazkaya.meiro.ui.components.MIcon
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.Spinner
import com.muratkirazkaya.meiro.ui.components.clickableNoIndication
import com.muratkirazkaya.meiro.ui.components.coverBrush
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.icons.MeiroLogos
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.MeiroType
import java.util.Locale

/** The MusicBrainz state the full player's chip shows (`linkPart`): matched, "other", or not linked yet. */
enum class NpLink { Matched, Other, Unknown }

/** One of the full player's lower tabs (the mock's `qsheet` tabs). */
enum class PlayerTab { UpNext, Lyrics, Related }

/**
 * What the full player (`renderNP`) shows. [context] names the list the queue plays from ("" leaves the line out);
 * [background] is the cover's colours (the mock's `cov(h, 70)`) or null for the gradient of [cover]'s hue.
 */
data class NowPlayingUi(
    val title: String,
    val artist: String,
    val context: String,
    val cover: CoverSpec,
    val link: NpLink,
    val liked: Boolean,
    val ytm: Boolean,
    val sc: Boolean,
    val active: Provider,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val preparing: Boolean,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val background: Brush? = null,
)

class NowPlayingActions(
    val onClose: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onToggle: () -> Unit = {},
    val onPrev: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onSource: (Provider) -> Unit = {},
    val onTab: (PlayerTab) -> Unit = {},
    /** ⋮ in the top row: the track menu of the track playing. */
    val onMore: () -> Unit = {},
)

private val White = Color.White
private val Ink = Color(0xFF1C1B1F)
private val TR: Locale = Locale.forLanguageTag("tr")

/** Material's "repeat one" glyph, for repeat-the-track (the desktop's `IconRepeatOne`; the mock draws only `repeat`). */
private val RepeatOne: ImageVector by lazy {
    ImageVector.Builder("repeatone", 24.dp, 24.dp, 24f, 24f).addPath(
        PathParser().parsePathString("M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4zm-4-2V9h-1l-2 1v1h1.5v4H13z").toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}

/**
 * The mock's full player (`.np`, screens `np` and `np-prep`): the cover's colours behind a darkening gradient, the top
 * row (close, "Çalınıyor" over the list, ⋮), the cover (with the "Ses hazırlanıyor…" veil while the audio is found),
 * title, artist, the MusicBrainz chip and the heart, the source buttons, the seek bar, the controls and the lower tabs.
 * The ⋮, the chip and the heart are drawn as the mock draws them and do nothing yet.
 */
@Composable
fun NowPlayingScreen(ui: NowPlayingUi, bars: SystemBars, actions: NowPlayingActions = NowPlayingActions()) {
    BoxWithConstraints(Modifier.fillMaxSize().clickableNoIndication {}) {
        // The mock's 326px cover is 48px narrower than the screen; on a phone shorter than the mock's 824 it gives way too.
        val art = minOf(326.dp, maxWidth - 48.dp, maxHeight - 502.dp).coerceAtLeast(120.dp)
        Box(Modifier.fillMaxSize().background(ui.background ?: coverBrush(ui.cover.hue, 70)))
        Box(
            Modifier.fillMaxSize().inv("np.shade").background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.15f), 0.7f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.7f)),
            ),
        )
        Column(Modifier.fillMaxSize().padding(top = bars.top)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton("np.closeBtn", "np.close.icon", MeiroIcons.Down, White, actions.onClose)
                Column(Modifier.weight(1f).inv("np.ctx"), horizontalAlignment = Alignment.CenterHorizontally) {
                    MText("Çalınıyor".uppercase(TR), MeiroType.NpKicker, White, "np.ctx.kicker", Modifier.fillMaxWidth().alpha(0.75f), maxLines = 1, textAlign = TextAlign.Center)
                    if (ui.context.isNotEmpty()) MText(ui.context, MeiroType.NpContext, White, "np.ctx.label", Modifier.fillMaxWidth(), maxLines = 1, textAlign = TextAlign.Center)
                }
                IconButton("np.moreBtn", "np.more.icon", MeiroIcons.More, White, actions.onMore)
            }
            Spacer(Modifier.height(18.dp))
            val artShape = RoundedCornerShape(22.dp)
            Cover(
                ui.cover, 22.dp, "np.art",
                Modifier.align(Alignment.CenterHorizontally).size(art)
                    .shadow(24.dp, artShape, ambientColor = Color.Black.copy(alpha = 0.45f), spotColor = Color.Black.copy(alpha = 0.45f)),
            ) { if (ui.preparing) PrepVeil() }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MText(ui.title, MeiroType.NpTitle, White, "np.title", Modifier.fillMaxWidth(), maxLines = 1)
                    MText(ui.artist, MeiroType.NpArtist, White, "np.artist", Modifier.fillMaxWidth().alpha(0.8f))
                    Spacer(Modifier.height(6.dp))
                    LinkChip(ui.link)
                }
                IconButton("np.likeBtn", "np.like.icon", if (ui.liked) MeiroIcons.Heartf else MeiroIcons.Heart, White)
            }
            Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp), Arrangement.spacedBy(8.dp)) {
                SourceButton("np.src.ytm", Provider.Ytm, "YouTube Music", ui.active == Provider.Ytm, ui.ytm) { actions.onSource(Provider.Ytm) }
                SourceButton("np.src.sc", Provider.Sc, "SoundCloud", ui.active == Provider.Sc, ui.sc) { actions.onSource(Provider.Sc) }
            }
            SeekBar(ui.positionMs, ui.durationMs, actions.onSeek)
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp),
                Arrangement.SpaceBetween, Alignment.CenterVertically,
            ) {
                IconButton("np.shuffleBtn", "np.shuffle.icon", MeiroIcons.Shuffle, White, actions.onShuffle, modifier = Modifier.alpha(if (ui.shuffle) 1f else 0.6f))
                IconButton("np.prevBtn", "np.prev.icon", MeiroIcons.Prev, White, actions.onPrev, iconSize = 30)
                Box(
                    Modifier.size(76.dp).inv("np.playBtn", bg = White, radiusDp = 26f).clip(RoundedCornerShape(26.dp)).background(White)
                        .clickableNoIndication(actions.onToggle),
                    Alignment.Center,
                ) {
                    if (ui.preparing) Spinner("np.play.spinner", 22, 3f, Color(0xFFCCCCCC), Ink)
                    else MIcon(if (ui.playing) MeiroIcons.Pause else MeiroIcons.Play, Ink, "np.play.icon", size = 36)
                }
                IconButton("np.nextBtn", "np.next.icon", MeiroIcons.Next, White, actions.onNext, iconSize = 30)
                IconButton(
                    "np.repeatBtn", "np.repeat.icon", if (ui.repeat == RepeatMode.Track) RepeatOne else MeiroIcons.Repeat, White, actions.onRepeat,
                    modifier = Modifier.alpha(if (ui.repeat == RepeatMode.Off) 0.6f else 1f),
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 22.dp + (bars.bottom - 16.dp).coerceAtLeast(0.dp)),
                Arrangement.SpaceAround,
            ) {
                LowTab("np.tab.upnext", "Sıradakiler") { actions.onTab(PlayerTab.UpNext) }
                LowTab("np.tab.lyrics", "Sözler") { actions.onTab(PlayerTab.Lyrics) }
                LowTab("np.tab.related", "Benzerler") { actions.onTab(PlayerTab.Related) }
            }
        }
    }
}

/** `.np .art .prep`: 40% black over the cover, the white spinner, "Ses hazırlanıyor…" and the note under it. */
@Composable
private fun PrepVeil() {
    Box(Modifier.fillMaxSize().inv("np.prep", bg = Color.Black.copy(alpha = 0.4f), radiusDp = 22f).background(Color.Black.copy(alpha = 0.4f)), Alignment.Center) {
        Column(Modifier.inv("np.prep.text"), horizontalAlignment = Alignment.CenterHorizontally) {
            Spinner("np.prep.spinner", 22, 3f, White.copy(alpha = 0.3f), White)
            Spacer(Modifier.height(10.dp))
            MText("Ses hazırlanıyor…", MeiroType.NpPrep, White, "np.prep.label", textAlign = TextAlign.Center)
            MText("ilk çalmada birkaç saniye sürebilir", MeiroType.NpPrepNote, White, "np.prep.note", Modifier.alpha(0.7f), textAlign = TextAlign.Center)
        }
    }
}

/** The mock's `.chip` in the info block: check + "MusicBrainz", or the link glyph and "diğer" / "MusicBrainz'e bağla". */
@Composable
private fun LinkChip(link: NpLink) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        // 10px padding plus the 1px border CSS adds outside it; at least 24 tall (it grows with a larger font).
        Modifier.heightIn(min = 24.dp).inv("np.link", radiusDp = 8f).border(1.dp, White.copy(alpha = 0.4f), shape).clip(shape)
            .clickableNoIndication {}.padding(horizontal = 11.dp),
        Arrangement.spacedBy(4.dp), Alignment.CenterVertically,
    ) {
        MIcon(if (link == NpLink.Matched) MeiroIcons.Check else MeiroIcons.Link, White, "np.link.icon", size = 14)
        val label = when (link) { NpLink.Matched -> "MusicBrainz"; NpLink.Other -> "diğer"; NpLink.Unknown -> "MusicBrainz'e bağla" }
        MText(label, MeiroType.NpButton, White, "np.link.label", maxLines = 1)
    }
}

/** `.provsw button`: 32 tall, 16 radius, the logo and the name; the one playing filled white, one the track lacks at 40%. */
@Composable
private fun SourceButton(key: String, p: Provider, name: String, on: Boolean, available: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val bg = if (on) White.copy(alpha = 0.92f) else null
    Row(
        Modifier.heightIn(min = 32.dp).alpha(if (available) 1f else 0.4f).inv(key, bg = bg, radiusDp = 16f)
            .then(if (on) Modifier.background(bg!!, shape) else Modifier.border(1.dp, White.copy(alpha = 0.35f), shape))
            .clip(shape).clickableNoIndication(onClick).padding(start = 9.dp, end = 13.dp),
        Arrangement.spacedBy(6.dp), Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            if (p == Provider.Ytm) MeiroLogos.Ytm else MeiroLogos.Sc, null, Modifier.size(16.dp).inv("$key.icon"),
        )
        MText(name, MeiroType.NpButton, if (on) Ink else White, "$key.label", maxLines = 1)
    }
}

/**
 * `.seek`: the 4px line with the part played, the 16px knob and the two times (position, length). A drag or a tap
 * anywhere over it moves the knob, and letting go seeks there.
 */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var widthPx by remember { mutableStateOf(1f) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val played = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val pad = 24.dp
    Column(
        Modifier.fillMaxWidth()
            .pointerInput(durationMs) {
                val left = pad.toPx()
                fun frac(x: Float) = ((x - left) / widthPx).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { dragFraction = frac(it.x) },
                    onDragEnd = { dragFraction?.let { f -> if (durationMs > 0) onSeek((f * durationMs).toLong()) }; dragFraction = null },
                    onDragCancel = { dragFraction = null },
                ) { change, _ -> dragFraction = frac(change.position.x) }
            }
            .pointerInput(durationMs) {
                val left = pad.toPx()
                detectTapGestures { o -> if (durationMs > 0) onSeek((((o.x - left) / widthPx).coerceIn(0f, 1f) * durationMs).toLong()) }
            }
            .padding(start = pad, end = pad, top = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }) {
            val track = White.copy(alpha = 0.3f)
            Box(Modifier.fillMaxSize().inv("np.seek.line", bg = track, radiusDp = 2f).clip(RoundedCornerShape(2.dp)).background(track)) {
                Box(Modifier.fillMaxWidth(played).fillMaxHeight().inv("np.seek.fill", bg = White, radiusDp = 2f).clip(RoundedCornerShape(2.dp)).background(White))
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(
                    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset(x = maxWidth * played - 8.dp, y = (-6).dp).size(16.dp)
                        .inv("np.seek.thumb", bg = White, radiusDp = 8f).clip(CircleShape).background(White),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).alpha(0.8f)) {
            val shown = if (dragFraction != null) (played * durationMs).toLong() else positionMs
            MText(clockText(shown), MeiroType.NpTime, White, "np.seek.pos", maxLines = 1)
            Spacer(Modifier.weight(1f))
            MText(if (durationMs > 0) clockText(durationMs) else "", MeiroType.NpTime, White, "np.seek.dur", maxLines = 1)
        }
    }
}

/** `.lowtabs button`: 13/500 upper case, 12px padding, 85%. */
@Composable
private fun LowTab(key: String, label: String, onClick: () -> Unit) {
    Box(Modifier.alpha(0.85f).inv(key).clickableNoIndication(onClick).padding(12.dp), Alignment.Center) {
        MText(label.uppercase(TR), MeiroType.NpTab, White, "$key.label", maxLines = 1)
    }
}
