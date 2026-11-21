package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** The account avatar's letter, provided by the signed-in shell; "M" (the mock's) when nothing provides one. */
val LocalAvatarInitial = staticCompositionLocalOf { "M" }

/**
 * The mock's topbar: 64dp, an optional back button (then 4dp start padding instead of 16), the title (flex 1; the
 * playlist's is empty), [actions] (icon buttons), and the account avatar button (its letter is [LocalAvatarInitial])
 * unless [avatar] is false.
 */
@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    avatar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Meiro.colors
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp).inv("topbar", bg = c.surface, radiusDp = 0f)
            .background(c.surface).padding(start = if (onBack != null) 4.dp else 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconButton("topbar.backBtn", "topbar.back.icon", MeiroIcons.Back, c.onSurfaceVariant, onBack)
        if (title.isNotEmpty()) MText(title, MeiroType.TopBarTitle, c.onSurface, "topbar.title", Modifier.weight(1f), maxLines = 1)
        else Spacer(Modifier.weight(1f))
        actions()
        if (avatar) {
            Box(Modifier.size(48.dp).clip(CircleShape).inv("topbar.avatarBtn", radiusDp = 24f), Alignment.Center) {
                Box(
                    Modifier.size(32.dp).inv("topbar.avatar", bg = c.primaryContainer, radiusDp = 16f)
                        .clip(CircleShape).background(c.primaryContainer),
                    Alignment.Center,
                ) {
                    MText(LocalAvatarInitial.current, MeiroType.AvatarInitial, c.onPrimaryContainer, "topbar.avatar.label")
                }
            }
        }
    }
}
