package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.LibraryEmptyScreen
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.PlaceholderScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme

/** The app: theme, shell and the four tab routes. Library shows the empty state (no lists yet). */
@Composable
fun MeiroApp() {
    MeiroTheme {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val selected = Tab.entries.firstOrNull { it.route == entry?.destination?.route } ?: Tab.Home
        val sb = WindowInsets.systemBars.asPaddingValues()
        MeiroShell(
            selected = selected,
            onSelect = { tab ->
                nav.navigate(tab.route) {
                    popUpTo(Tab.Home.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            bars = SystemBars(sb.calculateTopPadding(), sb.calculateBottomPadding()),
            mini = SampleMini,
        ) {
            NavHost(nav, startDestination = Tab.Home.route) {
                composable(Tab.Home.route) { PlaceholderScreen("Meiro") }
                composable(Tab.Search.route) { PlaceholderScreen("Ara") }
                composable(Tab.Library.route) { LibraryEmptyScreen() }
                composable(Tab.Downloads.route) { PlaceholderScreen("İndirilenler") }
            }
        }
    }
}
