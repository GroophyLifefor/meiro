package com.muratkirazkaya.meiro.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import com.muratkirazkaya.meiro.ui.inv.invDim
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.icons.MeiroLogos
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/**
 * A cover: the picture at [url] when there is one, else (and under the picture while it loads) the mock's gradient
 * placeholder, whose hue comes from the list or track ([coverBrush]). Radius and size are the caller's.
 */
data class CoverSpec(val url: String? = null, val hue: Int = 0, val spread: Int = 50)

@Composable
fun Cover(spec: CoverSpec, radius: Dp, key: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.inv(key, radiusDp = radius.value).clip(shape).background(coverBrush(spec.hue, spec.spread))) {
        if (!spec.url.isNullOrBlank()) {
            val ctx = LocalContext.current
            val request = remember(spec.url) {
                ImageRequest.Builder(ctx).data(spec.url)
                    .apply { if (isVideoThumbnail(spec.url)) transformations(LetterboxTrim()) }.build()
            }
            AsyncImage(request, null, modifier = Modifier.fillMaxSize(), imageLoader = LocalCoverImageLoader.current ?: ctx.imageLoader, contentScale = ContentScale.Crop)
        }
        content()
    }
}

/** One of the source logos (14dp); the one a track does not play from is dimmed to 35%. */
@Composable
fun SourceLogo(key: String, source: Provider, dim: Boolean) {
    val icon = when (source) { Provider.Ytm -> MeiroLogos.Ytm; Provider.Sc -> MeiroLogos.Sc; Provider.Meiro -> MeiroLogos.Meiro }
    Image(icon, null, Modifier.size(14.dp).alpha(if (dim) 0.35f else 1f).inv(key).semantics { invDim = dim })
}

enum class Provider { Ytm, Sc, Meiro }

/** What the library list shows for one list. [meta] is the line under the name (the logo is added). */
data class ListRowData(val id: String, val name: String, val meta: String, val cover: CoverSpec)

/** The mock's `listRow`: 48dp cover (8dp radius), name, Meiro logo and meta line, a (for now inert) ⋮ button. */
@Composable
fun ListRow(p: String, row: ListRowData, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clickableNoIndication(onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(row.cover, 8.dp, "$p.cover", Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            MText(row.name, MeiroType.ListTitle, c.onSurface, "$p.title", Modifier.fillMaxWidth(), maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SourceLogo("$p.logo", Provider.Meiro, false)
                MText(row.meta, MeiroType.ListMeta, c.onSurfaceVariant, "$p.meta", Modifier.weight(1f, fill = false), maxLines = 1)
            }
        }
        IconButton("$p.moreBtn", "$p.more.icon", MeiroIcons.More, c.onSurfaceVariant)
    }
}

enum class LinkBadge { None, Unknown, Other }

/** What a playlist row shows for one track. [subtitle] is "artist · duration". */
data class SongRowData(
    val key: String,
    val title: String,
    val subtitle: String,
    val ytm: Boolean,
    val sc: Boolean,
    val activeYtm: Boolean,
    val cover: CoverSpec,
    val downloaded: Boolean = false,
    val link: LinkBadge = LinkBadge.None,
    val now: Boolean = false,
)

/**
 * The mock's `songRow`: cover, title, source logos (the one it plays from at full strength), the "downloaded" mark,
 * "artist · duration", the MusicBrainz link button for a track that is `unknown` (solid) or `other` (dashed), and ⋮.
 * The track that is playing has the primary tint, a coloured title and the equaliser on its cover.
 */
@Composable
fun SongRow(p: String, row: SongRowData, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (row.now) Modifier.background(c.primary.copy(alpha = 0.12f)) else Modifier)
            .clickableNoIndication(onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The mock's inventory lists the equaliser (not the cover under it) for the playing row.
        val eq = row.now && playing
        Cover(row.cover, 8.dp, if (eq) "$p.cv" else "$p.cover", Modifier.size(48.dp)) { if (eq) Equaliser("$p.eq") }
        Column(Modifier.weight(1f)) {
            MText(row.title, MeiroType.ListTitle, if (row.now) c.primary else c.onSurface, "$p.title", Modifier.fillMaxWidth(), maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (row.ytm) SourceLogo("$p.src.ytm", Provider.Ytm, !row.activeYtm)
                    if (row.sc) SourceLogo("$p.src.sc", Provider.Sc, row.activeYtm)
                }
                if (row.downloaded) MIcon(MeiroIcons.Done, c.primary, "$p.dl", size = 14)
                MText(row.subtitle, MeiroType.ListMeta, c.onSurfaceVariant, "$p.meta", Modifier.weight(1f, fill = false), maxLines = 1)
            }
        }
        if (row.link != LinkBadge.None) LinkButton(p, row.link == LinkBadge.Other)
        IconButton("$p.moreBtn", "$p.more.icon", MeiroIcons.More, c.onSurfaceVariant)
    }
}

/** The mock's `.linkbtn`: 32dp circle, 1dp outline (dashed and 70% for `other`), an 18dp link glyph. Inert for now. */
@Composable
private fun LinkButton(p: String, other: Boolean) {
    val c = Meiro.colors
    val outline = c.outline
    val dashed = Modifier.drawBehind {
        val w = 1.dp.toPx()
        drawCircle(
            outline, radius = (size.minDimension - w) / 2,
            style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
        )
    }
    Box(
        Modifier.size(32.dp).inv("$p.link", radiusDp = 16f).alpha(if (other) 0.7f else 1f)
            .then(if (other) dashed else Modifier.border(1.dp, outline, CircleShape))
            .clip(CircleShape).clickableNoIndication {},
        Alignment.Center,
    ) { MIcon(MeiroIcons.Link, c.onSurfaceVariant, "$p.link.icon", size = 18) }
}

/** The mock's `.eq`: 45% black over the cover, three 3dp white bars that grow and shrink (6..16dp, 1 s, 0.2 s apart). */
@Composable
private fun Equaliser(key: String) {
    val t = rememberInfiniteTransition(label = "eq")
    Row(
        Modifier.fillMaxSize().inv(key, bg = Color.Black.copy(alpha = 0.45f), radiusDp = 8f).background(Color.Black.copy(alpha = 0.45f)),
        Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally), Alignment.CenterVertically,
    ) {
        for (i in 0..2) {
            val h by t.animateFloat(
                6f, 16f,
                infiniteRepeatable(tween(500, delayMillis = 0, easing = LinearEasing), RepeatMode.Reverse, androidx.compose.animation.core.StartOffset(i * 200)),
                label = "eq$i",
            )
            Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
    }
}

/** The mock's extended FAB (`.fab`): 56dp, 16dp radius, primary container, "add" glyph and label. */
@Composable
fun NewListFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.height(56.dp).inv("fab", bg = c.primaryContainer, radiusDp = 16f)
            .shadow(4.dp, shape).background(c.primaryContainer, shape).clip(shape)
            .clickableNoIndication(onClick).padding(start = 16.dp, end = 20.dp),
        Arrangement.spacedBy(10.dp), Alignment.CenterVertically,
    ) {
        MIcon(MeiroIcons.Add, c.onPrimaryContainer, "fab.icon")
        MText("Yeni liste", MeiroType.ButtonLabel, c.onPrimaryContainer, "fab.label", maxLines = 1)
    }
}
