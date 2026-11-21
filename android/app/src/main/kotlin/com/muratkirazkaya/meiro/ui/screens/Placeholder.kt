package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.muratkirazkaya.meiro.ui.components.TopBar

/** Route stand-in until the tab's screen is built: only the mock's top bar for that tab. */
@Composable
fun PlaceholderScreen(title: String) {
    Column { TopBar(title) }
}
