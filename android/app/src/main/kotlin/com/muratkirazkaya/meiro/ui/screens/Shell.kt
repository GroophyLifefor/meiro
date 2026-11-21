package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.MiniPlayer
import com.muratkirazkaya.meiro.ui.components.MiniState
import com.muratkirazkaya.meiro.ui.components.NavBar
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.theme.Meiro

/** System bar sizes the shell leaves room for (real values on a device, fixed in screenshot tests). */
data class SystemBars(val top: Dp, val bottom: Dp)

/**
 * Status-bar space, content, mini player (8dp side margins, 8dp below), bottom bar. The status bar and
 * the gesture pill are drawn by the system on a device; the mock's copies of them are not part of the app.
 */
@Composable
fun MeiroShell(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    bars: SystemBars,
    mini: MiniState?,
    onPlayPause: () -> Unit = {},
    onNext: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Meiro.colors.surface)) {
        Spacer(Modifier.height(bars.top))
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        // The mock draws the mini player over a song that plays; with nothing queued there is no mock for it, so it is hidden.
        if (mini != null) MiniPlayer(mini, Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), onPlayPause, onNext)
        NavBar(selected, onSelect, bars.bottom)
    }
}
