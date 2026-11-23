package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** The mock's offline banner text (`offlineBanner()`). */
const val OFFLINE_TEXT = "Çevrimdışı. İndirilenler ve önbellekteki şarkılar çalar."

/**
 * The mock's `.banner` (`offlineBanner()`, shown on Home, Ara, Kütüphane and İndirilenler while offline): 16dp side
 * margins, 10/14dp padding, 14dp radius, surface-container-high, the offline glyph (18dp) and the 13px text. [bottom] is
 * the space under it: the mock's 8px bottom margin, less what collapses into the next block's own top margin.
 */
@Composable
fun OfflineBanner(bottom: Dp = 8.dp, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.padding(start = 16.dp, end = 16.dp, bottom = bottom).fillMaxWidth()
            .inv("banner", bg = c.surfaceContainerHigh, radiusDp = 14f).background(c.surfaceContainerHigh, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        Arrangement.spacedBy(10.dp), Alignment.CenterVertically,
    ) {
        MIcon(MeiroIcons.Offline, c.onSurfaceVariant, "banner.icon", size = 18)
        MText(OFFLINE_TEXT, MeiroType.Banner, c.onSurfaceVariant, "banner.text", Modifier.weight(1f))
    }
}

/** Whether the phone is offline: Home, Ara, Kütüphane and İndirilenler show the banner. False in tests unless set. */
val LocalOffline = staticCompositionLocalOf { false }

/** `source:key` of every downloaded track; a song row whose track is in it shows the downloaded mark. Empty in tests. */
val LocalDownloaded = staticCompositionLocalOf<Set<String>> { emptySet() }

/** Sizes as the mock writes them: "480 MB", "9,1 MB", "2,1 GB" (1024-based, one decimal under 10, Turkish comma). */
fun sizeText(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var v = bytes.toDouble() / 1024
    var unit = 0
    while (v >= 1024 && unit < 4) { v /= 1024; unit++ }
    val u = listOf("KB", "MB", "GB", "TB", "PB")[unit]
    val n = if (v < 10) String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',') else Math.round(v).toString()
    return "$n $u"
}
