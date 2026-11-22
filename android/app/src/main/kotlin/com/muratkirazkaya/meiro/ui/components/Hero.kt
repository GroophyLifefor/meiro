package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/**
 * The mock's `.hero` of a list, an album or an artist: the 220dp cover (20dp radius, or a circle with [round]), the
 * name, the kind line (with the provider's [logo] before it when there is one) and the `.hero.actions` row [actions].
 * The actions row is also a `.hero` in the mock's markup, which gives it the 8dp top and 4dp bottom padding.
 */
@Composable
fun DetailHero(
    cover: CoverSpec,
    title: String,
    kind: String,
    logo: Provider?,
    round: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
) {
    val c = Meiro.colors
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val radius: Dp = if (round) 110.dp else 20.dp
        val shape = if (round) CircleShape else RoundedCornerShape(20.dp)
        Cover(
            cover, radius, "hero.cover",
            Modifier.size(220.dp).shadow(16.dp, shape, clip = false, ambientColor = Color.Black.copy(0.35f), spotColor = Color.Black.copy(0.35f)),
        )
        Spacer(Modifier.height(16.dp))
        MText(title, MeiroType.HeroTitle, c.onSurface, "hero.title", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        if (logo != null) {
            Row(Modifier.fillMaxWidth().inv("hero.kind"), Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
                SourceLogo("hero.kind.logo", logo, false)
                MText(kind, MeiroType.HeroKind, c.onSurfaceVariant, "hero.kind.label", Modifier.weight(1f, fill = false), textAlign = TextAlign.Center)
            }
        } else {
            MText(kind, MeiroType.HeroKind, c.onSurfaceVariant, "hero.kind", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(top = 8.dp, bottom = 4.dp), Arrangement.spacedBy(4.dp), Alignment.CenterVertically, content = actions)
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * The mock's `.hero .actions .play`: 56dp square, 18dp radius, primary, a 28dp play glyph, 8dp side margins
 * ([margin]); beside two labelled buttons (artist page) the margins are 0, so the row fits without squeezing it.
 */
@Composable
fun HeroPlayButton(onPlay: () -> Unit, margin: Dp = 8.dp) {
    val c = Meiro.colors
    Box(
        Modifier.padding(horizontal = margin).size(56.dp).inv("hero.playBtn", bg = c.primary, radiusDp = 18f)
            .clip(RoundedCornerShape(18.dp)).background(c.primary).clickableNoIndication(onPlay),
        Alignment.Center,
    ) { MIcon(MeiroIcons.Play, c.onPrimary, "hero.play.icon", size = 28) }
}

/**
 * The mock's `.btn.tonal` (secondary container) and `.btn.outlined` (1dp outline border, primary text): 40dp, 20dp
 * radius, 20dp side padding, an 18dp glyph and the label 8dp apart. In a row too narrow for it the label ends in an
 * ellipsis instead of pushing the row off the screen (the caller gives it a weight).
 */
@Composable
fun HeroLabelButton(key: String, icon: ImageVector, label: String, tonal: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(20.dp)
    val fg = if (tonal) c.onSecondaryContainer else c.primary
    Row(
        modifier.height(40.dp).inv(key, bg = if (tonal) c.secondaryContainer else null, radiusDp = 20f).clip(shape)
            .then(if (tonal) Modifier.background(c.secondaryContainer) else Modifier.border(1.dp, c.outline, shape))
            // CSS puts the 1px border outside the 20px padding.
            .clickableNoIndication(onClick).padding(horizontal = if (tonal) 20.dp else 21.dp),
        Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
    ) {
        MIcon(icon, fg, "$key.icon", size = 18)
        MText(label, MeiroType.ButtonLabel, fg, "$key.label", maxLines = 1)
    }
}
