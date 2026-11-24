package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/**
 * The mock's tabs row: "Meiro listeleri" and, only when [ytmTab] (signed in to YouTube Music), "YouTube Music". The
 * selected tab ([ytmSelected]) has the primary label and the indicator (28% inset each side); a tap on a tab gives
 * [onTab] whether it is the YouTube Music one. 48dp + 1dp divider.
 */
@Composable
fun LibraryTabs(modifier: Modifier = Modifier, ytmTab: Boolean = false, ytmSelected: Boolean = false, onTab: (Boolean) -> Unit = {}) {
    val c = Meiro.colors
    val ytmOn = ytmTab && ytmSelected
    Column(modifier.fillMaxWidth().inv("tabs", bg = c.surface, radiusDp = 0f).background(c.surface)) {
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            LibraryTab("tabs.meiro", "Meiro listeleri", !ytmOn) { onTab(false) }
            if (ytmTab) LibraryTab("tabs.ytm", "YouTube Music", ytmOn) { onTab(true) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
    }
}

@Composable
private fun RowScope.LibraryTab(tag: String, label: String, on: Boolean, onClick: () -> Unit) {
    val c = Meiro.colors
    Box(Modifier.weight(1f).height(48.dp).inv(tag).clickableNoIndication(onClick), Alignment.Center) {
        MText(label, MeiroType.TabLabel, if (on) c.primary else c.onSurfaceVariant, "$tag.label", maxLines = 1)
        if (on) {
            val shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.44f).height(3.dp)
                    .inv("$tag.indicator", bg = c.primary, radiusDp = 3f)
                    .clip(shape).background(c.primary),
            )
        }
    }
}
