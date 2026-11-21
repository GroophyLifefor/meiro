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
import com.muratkirazkaya.meiro.ui.screens.LibraryScreen
import com.muratkirazkaya.meiro.ui.screens.NewListSheet
import com.muratkirazkaya.meiro.ui.screens.PlaylistScreen
import com.muratkirazkaya.meiro.ui.screens.SearchActions
import com.muratkirazkaya.meiro.ui.screens.SearchScreen
import com.muratkirazkaya.meiro.ui.components.Snack
import com.muratkirazkaya.meiro.ui.components.LocalAvatarInitial
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.PlaceholderScreen
import com.muratkirazkaya.meiro.ui.screens.HomeActions
import com.muratkirazkaya.meiro.ui.screens.HomeScreen
import java.time.ZonedDateTime
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

    if (session.signedIn || guest) MainShell(c, bars, if (session.signedIn) session.email else "")
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

private const val PLAYLIST_ROUTE = "playlist/{id}"

/** The shell with the four tab routes; Library lists the lists, and a list opens as `playlist/<id>`. */
@Composable
private fun MainShell(c: AppContainer, bars: SystemBars, email: String) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val playing by PlaybackHost.ui.collectAsState()
    val route = entry?.destination?.route
    // A list keeps the tab it was opened from selected (Kütüphane, or Ana sayfa for a quick pick), as the mock's stack does.
    val from = if (route == PLAYLIST_ROUTE) nav.previousBackStackEntry?.destination?.route else route
    val selected = Tab.entries.firstOrNull { it.route == from } ?: if (route == PLAYLIST_ROUTE) Tab.Library else Tab.Home
    var sheet by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var snack by remember { mutableStateOf<String?>(null) }
    val openSheet = { newName = ""; sheet = true }

    CompositionLocalProvider(LocalAvatarInitial provides avatarInitial(email)) { Box(Modifier.fillMaxSize()) {
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
                composable(Tab.Home.route) {
                    HomeRoute(c, email, onOpen = { nav.navigate("playlist/$it") }, onSnack = { snack = it })
                }
                composable(Tab.Search.route) { SearchRoute(c) }
                composable(Tab.Library.route) { LibraryRoute(c, onOpen = { nav.navigate("playlist/$it") }, onNew = openSheet) }
                composable(Tab.Downloads.route) { PlaceholderScreen("İndirilenler") }
                composable(PLAYLIST_ROUTE) { e ->
                    PlaylistRoute(c, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                }
            }
        }
        if (sheet) {
            val create = {
                val n = newName.trim()
                if (n.isNotEmpty()) {
                    sheet = false
                    scope.launch { c.createList(n); snack = "\u201C$n\u201D oluşturuldu" }
                }
            }
            NewListSheet(newName, { newName = it }, { sheet = false }, create, bars)
        }
        snack?.let { m ->
            LaunchedEffect(m) { delay(2600); if (snack == m) snack = null }
            Snack(m, { snack = null }, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 160.dp))
        }
    } }
}

/** The account avatar's letter: the first letter of the greeting's name, "M" without an account. */
internal fun avatarInitial(email: String): String =
    HomeMapping.firstName(email)?.take(1)?.uppercase(java.util.Locale.forLanguageTag("tr")) ?: "M"

/**
 * Home: rebuilt from the store when the lists, the history or what plays change, and every half minute (the greeting
 * and "today" follow the clock). The Daily mix card and the history button lead to screens that do not exist yet.
 */
@Composable
private fun HomeRoute(c: AppContainer, email: String, onOpen: (String) -> Unit, onSnack: (String) -> Unit) {
    val ctx = LocalContext.current
    val version by c.libraries.version.collectAsState()
    val historyVersion by c.historyVersion.collectAsState()
    val now by PlaybackHost.ui.collectAsState()
    var clock by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); clock = ZonedDateTime.now() } }
    val playingKey = now?.item?.key
    val minute = clock.withSecond(0).withNano(0)
    val data = remember(version, historyVersion, playingKey, minute, email) {
        HomeMapping.build(email, ZonedDateTime.now(), c.engine.history(), c.libraries.list(), c.links, c.engine.settings.dailyMixId, playingKey)
    }
    HomeScreen(
        data.ui, playing = now?.playing == true,
        actions = HomeActions(
            onPlayMix = {
                if (data.mixItems.isNotEmpty()) {
                    PlaybackHost.playQueue(ctx, data.mixItems, 0, "Daily mix")
                    onSnack("Daily mix çalıyor")
                }
            },
            onPick = { i -> data.pickIds.getOrNull(i)?.let(onOpen) },
            // The desktop plays a history row on its own (`historyPlay`).
            onResume = { i -> data.resume.getOrNull(i)?.let { PlaybackHost.playQueue(ctx, listOf(it), 0, "history") } },
        ),
    )
}

@Composable
private fun LibraryRoute(c: AppContainer, onOpen: (String) -> Unit, onNew: () -> Unit) {
    val version by c.libraries.version.collectAsState()
    val rows = remember(version) { c.libraries.list().map { it.toRow() } }
    if (rows.isEmpty()) LibraryEmptyScreen(onNew) else LibraryScreen(rows, onOpen, onNew)
}

@Composable
private fun SearchRoute(c: AppContainer) {
    val ctx = LocalContext.current
    val ctl = c.search
    val s by ctl.state.collectAsState()
    val now by PlaybackHost.ui.collectAsState()
    val linksVersion by c.libraries.version.collectAsState()
    val playingKey = now?.item?.key
    val ui = remember(s, playingKey, linksVersion) {
        SearchMapping.toUi(s, { src, id -> c.links.song(src, id)?.state }, playingKey)
    }
    SearchScreen(
        ui, playing = now?.playing == true,
        actions = SearchActions(
            onQuery = ctl::setField,
            onSubmit = { ctl.submit() },
            onClear = ctl::clear,
            onRecent = { ctl.submit(it) },
            onForget = ctl::forget,
            onClearRecent = ctl::clearRecent,
            onChip = ctl::selectChip,
            onProvider = ctl::toggleProvider,
            onSong = { i ->
                val items = SearchMapping.songs(ctl.state.value)
                with(SearchMapping) { PlaybackHost.playQueue(ctx, items.map { it.toQItem() }, i, "search: ${s.submitted}") }
            },
            onEnd = ctl::loadMore,
        ),
    )
}

@Composable
private fun PlaylistRoute(c: AppContainer, id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version by c.libraries.version.collectAsState()
    val now by PlaybackHost.ui.collectAsState()
    val lib = remember(version, id) { c.libraries.get(id) }
    // The list is gone (deleted on another device, say): back to the lists.
    if (lib == null) { LaunchedEffect(Unit) { onBack() }; return }
    val playingKey = now?.item?.key
    val ui = remember(lib, playingKey) { lib.toPlaylistUi(c.links, playingKey) }
    fun play(from: Int, items: List<com.muratkirazkaya.meiro.playback.QItem>) = PlaybackHost.playQueue(ctx, items, from, lib.name)
    PlaylistScreen(
        ui, playing = now?.playing == true, onBack = onBack,
        onPlay = { play(0, lib.items.map { it.toQItem() }) },
        onShuffle = { play(0, lib.items.map { it.toQItem() }.shuffled()) },
        onRow = { i -> play(i, lib.items.map { it.toQItem() }) },
    )
}
