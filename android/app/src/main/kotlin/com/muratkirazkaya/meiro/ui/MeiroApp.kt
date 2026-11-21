package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.muratkirazkaya.meiro.AppContainer
import com.muratkirazkaya.meiro.MeiroApplication
import com.muratkirazkaya.meiro.Session
import com.muratkirazkaya.meiro.sync.SESSION_ENDED_MESSAGE
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.muratkirazkaya.meiro.playback.PlaybackHost
import com.muratkirazkaya.meiro.ui.components.toMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.LibraryEmptyScreen
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.PlaceholderScreen
import com.muratkirazkaya.meiro.ui.screens.SignInField
import com.muratkirazkaya.meiro.ui.screens.SignInScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app: theme, then the sign-in screen or the shell. Signed in (a saved session) opens the shell; otherwise the
 * sign-in screen, from which "Hesapsız devam et" opens the shell without an account.
 */
@Composable
fun MeiroApp(app: MeiroApplication) {
    MeiroTheme {
        val sb = WindowInsets.systemBars.asPaddingValues()
        val bars = SystemBars(sb.calculateTopPadding(), sb.calculateBottomPadding())
        val container by produceState<AppContainer?>(null) { value = app.container() }
        val c = container
        if (c == null) Box(Modifier.fillMaxSize().background(Meiro.colors.surface)) else AppRoot(c, bars)
    }
}

@Composable
private fun AppRoot(c: AppContainer, bars: SystemBars) {
    // The engine has no change notifications; its account state is read twice a second.
    var session by remember { mutableStateOf(c.session()) }
    LaunchedEffect(c) { while (true) { session = c.session(); delay(500) } }
    var guest by remember { mutableStateOf(false) }

    if (session.signedIn || guest) MainShell(bars)
    else SignInRoute(c, session, bars, onSkip = { guest = true })
}

@Composable
private fun SignInRoute(c: AppContainer, session: Session, bars: SystemBars, onSkip: () -> Unit) {
    var server by remember { mutableStateOf(AppContainer.displayServer(session.server)) }
    var email by remember { mutableStateOf(session.email) }
    var password by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf<SignInField?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The server stopped taking the session: the sign-in screen shows the engine's message.
    LaunchedEffect(session.expired) { if (session.expired) message = SESSION_ENDED_MESSAGE }

    SignInScreen(
        server, email, password,
        { server = it }, { email = it }, { password = it },
        focused, { f, on -> if (on) focused = f else if (focused == f) focused = null },
        bars,
        onSubmit = {
            if (!busy) {
                busy = true
                scope.launch {
                    message = c.signIn(server, email, password)
                    busy = false
                }
            }
        },
        onSkip = onSkip,
        message = message, onMessageDismiss = { message = null },
    )
}

/** The shell with the four tab routes. Library shows the empty state (no lists screen yet). */
@Composable
private fun MainShell(bars: SystemBars) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val ctx = LocalContext.current
    val playing by PlaybackHost.ui.collectAsState()
    val selected = Tab.entries.firstOrNull { it.route == entry?.destination?.route } ?: Tab.Home
    MeiroShell(
        selected = selected,
        onSelect = { tab ->
            nav.navigate(tab.route) {
                popUpTo(Tab.Home.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        bars = bars,
        mini = playing?.toMini(),
        onPlayPause = { PlaybackHost.togglePlay(ctx) },
        onNext = { PlaybackHost.next(ctx) },
    ) {
        NavHost(nav, startDestination = Tab.Home.route) {
            composable(Tab.Home.route) { PlaceholderScreen("Meiro") }
            composable(Tab.Search.route) { PlaceholderScreen("Ara") }
            composable(Tab.Library.route) { LibraryEmptyScreen() }
            composable(Tab.Downloads.route) { PlaceholderScreen("İndirilenler") }
        }
    }
}
