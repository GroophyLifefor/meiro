package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/**
 * The mock's `.empty` block ("Henüz liste yok") with its filled "Yeni liste" button.
 *
 * Mock quirk reproduced on purpose: the mock's `.empty svg` rule also hits the button's "add" glyph, so
 * it is drawn 56dp in the outline colour, overflowing the 40dp button (inventory: icon 56x56 at y 344).
 * The button must not clip, so the shape is a background shape, not a clip.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    buttonLabel: String,
    modifier: Modifier = Modifier,
    onButton: () -> Unit = {},
) {
    val c = Meiro.colors
    Column(
        modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MIcon(MeiroIcons.Library, c.outline, "empty.icon", size = 56)
        Spacer(Modifier.height(14.dp))
        MText(title, MeiroType.EmptyTitle, c.onSurface, "empty.title", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        MText(body, MeiroType.EmptyBody, c.onSurfaceVariant, "empty.text", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.height(40.dp).inv("empty.button", bg = c.primary, radiusDp = 20f)
                .background(c.primary, RoundedCornerShape(20.dp))
                .clickableNoIndication(onButton).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.requiredSize(56.dp).offset(y = (-7).dp), Alignment.Center) {
                MIcon(MeiroIcons.Add, c.outline, "empty.button.icon", size = 56)
            }
            MText(buttonLabel, MeiroType.ButtonLabel, c.onPrimary, "empty.button.label")
        }
    }
}
