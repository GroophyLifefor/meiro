package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** The mock's tabs row with its single selected tab ("Meiro listeleri"): 48dp + 1dp divider. */
@Composable
fun LibraryTabs(modifier: Modifier = Modifier) {
    val c = Meiro.colors
    Column(modifier.fillMaxWidth().inv("tabs", bg = c.surface, radiusDp = 0f).background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(48.dp).inv("tabs.meiro"), Alignment.Center) {
            MText("Meiro listeleri", MeiroType.TabLabel, c.primary, "tabs.meiro.label")
            val shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.44f).height(3.dp)
                    .inv("tabs.meiro.indicator", bg = c.primary, radiusDp = 3f)
                    .clip(shape).background(c.primary),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
    }
}
