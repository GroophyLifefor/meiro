package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/**
 * The mock's `.snack`: on-surface bar, 6dp radius, 14/16dp padding, message and a "Tamam" button that closes it.
 * The caller places it 160dp above the bottom edge and hides it after 2.6 s, as the mock does.
 */
@Composable
fun Snack(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.fillMaxWidth().shadow(3.dp, shape).background(c.onSurface, shape).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(message, Modifier.weight(1f), style = MeiroType.Body.toStyle(c.surface))
        BasicText("Tamam", Modifier.clickableNoIndication(onDismiss), style = MeiroType.SnackAction.toStyle(c.primaryContainer))
    }
}
