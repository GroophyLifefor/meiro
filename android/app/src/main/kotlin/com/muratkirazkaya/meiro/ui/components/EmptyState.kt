package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * The button's "add" glyph is the mock's `.btn svg`: 18dp, currentColor (on-primary).
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    buttonLabel: String,
    modifier: Modifier = Modifier,
    onButton: () -> Unit = {},
    /** The mock's `error` screen: the info glyph and a tonal button without a glyph. */
    error: Boolean = false,
) {
    val c = Meiro.colors
    Column(
        modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MIcon(if (error) MeiroIcons.Info else MeiroIcons.Library, c.outline, "empty.icon", size = 56)
        Spacer(Modifier.height(14.dp))
        MText(title, MeiroType.EmptyTitle, c.onSurface, "empty.title", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        MText(body, MeiroType.EmptyBody, c.onSurfaceVariant, "empty.text", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.height(40.dp).inv("empty.button", bg = if (error) c.secondaryContainer else c.primary, radiusDp = 20f)
                .background(if (error) c.secondaryContainer else c.primary, RoundedCornerShape(20.dp))
                .clickableNoIndication(onButton).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!error) MIcon(MeiroIcons.Add, c.onPrimary, "empty.button.icon", size = 18)
            MText(buttonLabel, MeiroType.ButtonLabel, if (error) c.onSecondaryContainer else c.onPrimary, "empty.button.label")
        }
    }
}
