package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

enum class Tab(val route: String, val label: String, val icon: () -> ImageVector) {
    Home("home", "Ana sayfa", { MeiroIcons.Home }),
    Search("search", "Ara", { MeiroIcons.Search }),
    Library("library", "Kütüphane", { MeiroIcons.Library }),
    Downloads("downloads", "İndirilenler", { MeiroIcons.Download }),
}

/**
 * The mock's bottom bar: 12dp top, 16dp bottom padding (the gesture zone; on a device with a taller
 * inset the inset wins), four equal tabs. The active indicator is 64x26 because the mock's flex layout
 * squeezes the 32dp pill to the 52dp button content (inventory pill h = 26).
 */
@Composable
fun NavBar(selected: Tab, onSelect: (Tab) -> Unit, bottomInset: Dp, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    val bottom = maxOf(16.dp, bottomInset)
    Row(
        modifier.fillMaxWidth().height(64.dp + bottom).inv("nav", bg = c.surfaceContainer, radiusDp = 0f)
            .background(c.surfaceContainer).padding(top = 12.dp, bottom = bottom),
    ) {
        Tab.entries.forEach { tab ->
            NavItem(tab, tab == selected, { onSelect(tab) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavItem(tab: Tab, on: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Meiro.colors
    val labelColor = if (on) c.onSurface else c.onSurfaceVariant
    val iconColor = if (on) c.onSecondaryContainer else c.onSurfaceVariant
    Column(
        modifier.fillMaxHeight().inv("nav.${tab.route}").clickableNoIndication(onClick).padding(horizontal = 6.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val pill = Modifier.size(width = 64.dp, height = 26.dp).let {
            if (on) it.inv("nav.${tab.route}.pill", bg = c.secondaryContainer, radiusDp = 16f)
                .clip(RoundedCornerShape(16.dp)).background(c.secondaryContainer) else it
        }
        Box(pill, Alignment.Center) { MIcon(tab.icon(), iconColor, "nav.${tab.route}.icon") }
        MText(tab.label, MeiroType.NavLabel, labelColor, "nav.${tab.route}.label", maxLines = 1)
    }
}
