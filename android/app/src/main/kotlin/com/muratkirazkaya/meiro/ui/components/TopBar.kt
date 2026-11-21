package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** The mock's topbar: 64dp, title left, account avatar button right (the "M" initial is a placeholder). */
@Composable
fun TopBar(title: String, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp).inv("topbar", bg = c.surface, radiusDp = 0f)
            .background(c.surface).padding(start = 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MText(
            title, MeiroType.TopBarTitle, c.onSurface, "topbar.title",
            Modifier.weight(1f), maxLines = 1,
        )
        Box(Modifier.size(48.dp).clip(CircleShape).inv("topbar.avatarBtn", radiusDp = 24f), Alignment.Center) {
            Box(
                Modifier.size(32.dp).inv("topbar.avatar", bg = c.primaryContainer, radiusDp = 16f)
                    .clip(CircleShape).background(c.primaryContainer),
                Alignment.Center,
            ) {
                MText("M", MeiroType.AvatarInitial, c.onPrimaryContainer, "topbar.avatar.label")
            }
        }
    }
}
