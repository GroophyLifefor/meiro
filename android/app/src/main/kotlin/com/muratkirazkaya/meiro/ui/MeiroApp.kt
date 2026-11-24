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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.muratkirazkaya.meiro.AppContainer
import com.muratkirazkaya.meiro.MeiroApplication
import com.muratkirazkaya.meiro.Session
import com.muratkirazkaya.meiro.sync.SESSION_ENDED_MESSAGE
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.muratkirazkaya.meiro.playback.PlaybackHost
import com.muratkirazkaya.meiro.ui.components.toMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.HomeLoadingScreen
import com.muratkirazkaya.meiro.ui.screens.LibraryEmptyScreen
import com.muratkirazkaya.meiro.ui.screens.LibraryScreen
import com.muratkirazkaya.meiro.ui.screens.NewListSheet
import com.muratkirazkaya.meiro.ui.screens.PlaylistScreen
import com.muratkirazkaya.meiro.ui.screens.AlbumScreen
import com.muratkirazkaya.meiro.ui.screens.ArtistScreen
import com.muratkirazkaya.meiro.ui.screens.DetailActions
import com.muratkirazkaya.meiro.youtube.BrowsePage
import com.muratkirazkaya.meiro.ui.screens.SearchActions
import com.muratkirazkaya.meiro.ui.screens.SearchScreen
import com.muratkirazkaya.meiro.ui.screens.ListMenuUi
import com.muratkirazkaya.meiro.data.LinkState
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
import com.muratkirazkaya.meiro.ui.screens.MixScreen
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
    val container by produceState<AppContainer?>(null) { value = app.container() }
    val c = container
    // "Tema" (Sistem / Açık / Koyu): the system's setting unless one is chosen.
    val theme = c?.prefs?.collectAsState()?.value?.theme
    val dark = when (theme) {
        com.muratkirazkaya.meiro.ui.screens.ThemeChoice.Light -> false
        com.muratkirazkaya.meiro.ui.screens.ThemeChoice.Dark -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    ThemedStatusBar(dark)
    MeiroTheme(darkTheme = dark) {
        val sb = WindowInsets.systemBars.asPaddingValues()
        val bars = SystemBars(sb.calculateTopPadding(), sb.calculateBottomPadding())
        if (c == null) Box(Modifier.fillMaxSize().background(Meiro.colors.surface)) else AppRoot(c, bars)
    }
}

/** The status and navigation bar icons follow the theme in use (dark icons on the light theme), also when it is chosen in the settings. */
@Composable
private fun ThemedStatusBar(dark: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(view, dark) {
        val window = (view.context as? android.app.Activity)?.window
        window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }?.let {
            it.isAppearanceLightStatusBars = !dark
            it.isAppearanceLightNavigationBars = !dark
        }
        onDispose {}
    }
}

@Composable
private fun AppRoot(c: AppContainer, bars: SystemBars) {
    // The engine has no change notifications; its account state is read twice a second.
    var session by remember { mutableStateOf(c.session()) }
    LaunchedEffect(c) { while (true) { session = c.session(); delay(500) } }
    var guest by remember { mutableStateOf(false) }

    // "Çıkış yap" signs out and comes back here; without an account ("Giriş yap", the server's "Değiştir") it only leaves
    // guest mode, so the sign-in screen shows again.
    val scope = rememberCoroutineScope()
    val exit: () -> Unit = {
        if (session.signedIn) scope.launch { c.signOut(); session = c.session(); guest = false } else guest = false
    }
    if (session.signedIn || guest) MainShell(c, bars, if (session.signedIn) session.email else "", session.signedIn, exit)
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
private const val MIX_ROUTE = "mix"
private const val ALBUM_ROUTE = "album/{id}?tab={tab}"
private const val ARTIST_ROUTE = "artist/{id}?tab={tab}"
private const val MB_ARTIST_ROUTE = "mbartist/{id}?tab={tab}"
private const val HISTORY_ROUTE = "history?tab={tab}"
private const val STATS_ROUTE = "stats?tab={tab}"
private const val SETTINGS_ROUTE = "settings?tab={tab}"
private const val YTM_LOGIN_ROUTE = "ytmlogin?tab={tab}"

/** The shell with the four tab routes; Library lists the lists, and a list opens as `playlist/<id>`. */
@Composable
private fun MainShell(c: AppContainer, bars: SystemBars, email: String, signedIn: Boolean, onExit: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val playing by PlaybackHost.ui.collectAsState()
    val route = entry?.destination?.route
    // A list keeps the tab it was opened from selected (Kütüphane, or Ana sayfa for a quick pick), as the mock's stack
    // does; an album or artist page carries the tab it was opened from (it can be several pages deep).
    val from = when (route) {
        PLAYLIST_ROUTE, MIX_ROUTE -> nav.previousBackStackEntry?.destination?.route
        ALBUM_ROUTE, ARTIST_ROUTE, MB_ARTIST_ROUTE, HISTORY_ROUTE, STATS_ROUTE, SETTINGS_ROUTE, YTM_LOGIN_ROUTE -> entry?.arguments?.getString("tab")
        else -> route
    }
    val selected = Tab.entries.firstOrNull { it.route == from } ?: if (route == PLAYLIST_ROUTE) Tab.Library else Tab.Home
    val openAlbum = { id: String -> nav.navigate("album/$id?tab=${selected.route}") }
    // M6d: a search row of a MusicBrainz artist ("mb:<mbid>") opens the MusicBrainz artist page.
    val openArtist = { id: String ->
        if (id.startsWith(SearchMapping.MB_ARTIST_PREFIX)) nav.navigate("mbartist/${id.removePrefix(SearchMapping.MB_ARTIST_PREFIX)}?tab=${selected.route}")
        else nav.navigate("artist/$id?tab=${selected.route}")
    }
    val openMbArtist = { mbid: String -> nav.navigate("mbartist/$mbid?tab=${selected.route}") }
    // M6b: the history, the stats and the settings keep the tab they were opened from selected.
    val openHistory = { nav.navigate("history?tab=${selected.route}") }
    val openStats = { nav.navigate("stats?tab=${selected.route}") }
    val openSettings = { nav.navigate("settings?tab=${selected.route}") }
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    var histMenu by rememberSaveable { mutableStateOf(false) }
    var clearAsk by rememberSaveable { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var snack by remember { mutableStateOf<String?>(null) }
    val openSheet = { newName = ""; sheet = true }
    // The full player (the mock's `.np`, opened by the mini player or the playing track's row) and its queue sheet.
    var npOpen by rememberSaveable { mutableStateOf(false) }
    // The tab the queue sheet shows ("Sıradakiler", "Sözler", "Benzerler"), null while it is closed.
    var sheetTab by rememberSaveable { mutableStateOf<com.muratkirazkaya.meiro.ui.screens.PlayerTab?>(null) }
    LaunchedEffect(playing == null) { if (playing == null) { npOpen = false; sheetTab = null } }
    val openPlayer = { if (PlaybackHost.ui.value != null) npOpen = true }
    // The track menu, the list menu and the sheets they open (M4h), over everything else.
    val menus = remember { MenuState() }
    val openers = remember { MenuOpeners({ menus.track = it }, { menus.list = it }, { menus.link = it }) }
    // M5: the downloaded marks, the offline banner, and the downloads' own snacks (a download that failed).
    val downloaded by c.downloads.keys.collectAsState()
    val online by com.muratkirazkaya.meiro.downloads.Online.state.collectAsState()
    LaunchedEffect(c) { c.downloadEvents.collect { snack = it } }
    // A track passed over because it did not play (the mock's `playerr` snack, the desktop's notice).
    LaunchedEffect(c) { PlaybackHost.notices.collect { snack = it } }
    // M6f: YouTube Music stopped taking the session (the desktop's `sessionExpiredNotice`).
    LaunchedEffect(c) { c.ytmAccount.notices.collect { snack = it } }

    CompositionLocalProvider(
        LocalAvatarInitial provides avatarInitial(email), LocalOpenPlayer provides openPlayer, LocalMenus provides openers,
        com.muratkirazkaya.meiro.ui.components.LocalOpenAccount provides { accountOpen = true },
        com.muratkirazkaya.meiro.ui.components.LocalDownloaded provides downloaded, com.muratkirazkaya.meiro.ui.components.LocalOffline provides !online,
    ) { Box(Modifier.fillMaxSize()) {
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
            onOpenPlayer = openPlayer,
        ) {
            NavHost(nav, startDestination = Tab.Home.route) {
                composable(Tab.Home.route) {
                    HomeRoute(c, email, onOpen = { nav.navigate("playlist/$it") }, onMix = { nav.navigate(MIX_ROUTE) }, onSnack = { snack = it }, onStats = openStats, onHistory = openHistory)
                }
                composable(Tab.Search.route) { SearchRoute(c, openAlbum, openArtist) }
                composable(Tab.Library.route) {
                    LibraryRoute(c, onOpen = { nav.navigate("playlist/$it") }, onMix = { nav.navigate(MIX_ROUTE) }, onNew = openSheet)
                }
                composable(Tab.Downloads.route) { DownloadsRoute(c, onSnack = { snack = it }) }
                composable(ALBUM_ROUTE, listOf(navArgument("tab") { defaultValue = Tab.Search.route })) { e ->
                    AlbumRoute(c, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, onSnack = { snack = it })
                }
                composable(ARTIST_ROUTE, listOf(navArgument("tab") { defaultValue = Tab.Search.route })) { e ->
                    ArtistRoute(c, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, openAlbum, openArtist)
                }
                composable(MB_ARTIST_ROUTE, listOf(navArgument("tab") { defaultValue = Tab.Search.route })) { e ->
                    MbArtistRoute(c, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, openMbArtist)
                }
                composable(MIX_ROUTE) { MixRoute(c, onBack = { nav.popBackStack() }, onSnack = { snack = it }, onSettings = openSettings) }
                composable(HISTORY_ROUTE, listOf(navArgument("tab") { defaultValue = Tab.Home.route })) {
                    HistoryRoute(c, signedIn, onBack = { nav.popBackStack() }, onMenu = { histMenu = true })
                }
                composable(STATS_ROUTE, listOf(navArgument("tab") { defaultValue = Tab.Home.route })) { StatsRoute(c, signedIn, onBack = { nav.popBackStack() }) }
                composable(SETTINGS_ROUTE, listOf(navArgument("tab") { defaultValue = Tab.Home.route })) {
                    SettingsRoute(
                        c, email, signedIn, onBack = { nav.popBackStack() }, onHistory = openHistory, onExit = onExit, onSnack = { snack = it },
                        onYtmLogin = { nav.navigate("ytmlogin?tab=${selected.route}") },
                    )
                }
                composable(YTM_LOGIN_ROUTE, listOf(navArgument("tab") { defaultValue = Tab.Home.route })) {
                    YtmLoginRoute(c, onBack = { nav.popBackStack() }, onDone = { msg -> nav.popBackStack(); snack = msg }, onSnack = { snack = it })
                }
                composable(PLAYLIST_ROUTE) { e ->
                    PlaylistRoute(c, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, onSnack = { snack = it })
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
        // The last state stays drawn while the player slides away after playback stopped.
        val shown = remember { mutableStateOf<com.muratkirazkaya.meiro.playback.PlayerUi?>(null) }
        if (playing != null) shown.value = playing
        AnimatedVisibility(
            visible = npOpen && playing != null,
            enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(320)) { it },
        ) {
            shown.value?.let { p ->
                PlayerRoute(c, p, bars, sheetTab, { sheetTab = it }, onClose = { sheetTab = null; npOpen = false }, onSnack = { snack = it })
            }
        }
        BackHandler(enabled = npOpen) { if (sheetTab != null) sheetTab = null else npOpen = false }
        MenuLayer(c, menus, bars, MenuNav(openArtist, openAlbum, closePlayer = { sheetTab = null; npOpen = false }), snack = { snack = it })
        // M6b: the account sheet (every tab's avatar), the history menu and the question before clearing the history.
        if (accountOpen) {
            BackHandler { accountOpen = false }
            // "N dk önce" follows the clock and the sync while the sheet is open.
            var tick by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
            val ui = remember(email, signedIn, syncing, tick) { AccountMapping.sheet(c, email, signedIn, syncing) }
            com.muratkirazkaya.meiro.ui.screens.AccountSheet(
                ui, bars,
                com.muratkirazkaya.meiro.ui.screens.AccountActions(
                    onDismiss = { accountOpen = false },
                    onHistory = { accountOpen = false; openHistory() },
                    onStats = { accountOpen = false; openStats() },
                    onSettings = { accountOpen = false; openSettings() },
                    onSync = {
                        if (signedIn && !syncing) {
                            syncing = true
                            scope.launch { try { c.sync("manual") } finally { syncing = false } }
                        }
                    },
                    onSignOut = { accountOpen = false; onExit() },
                ),
            )
        }
        val prefs by c.prefs.collectAsState()
        if (histMenu) {
            BackHandler { histMenu = false }
            com.muratkirazkaya.meiro.ui.screens.HistoryMenuSheet(
                prefs.historyPaused, signedIn, bars,
                com.muratkirazkaya.meiro.ui.screens.HistoryMenuActions(
                    onDismiss = { histMenu = false },
                    onPause = {
                        histMenu = false
                        val paused = !prefs.historyPaused
                        c.updatePrefs { it.copy(historyPaused = paused) }
                        snack = if (paused) "Geçmiş kaydı duraklatıldı" else "Geçmiş kaydı sürdürüldü"
                    },
                    onStats = { histMenu = false; openStats() },
                    onClear = { histMenu = false; clearAsk = true },
                ),
            )
        }
        if (clearAsk) {
            BackHandler { clearAsk = false }
            com.muratkirazkaya.meiro.ui.screens.ClearHistorySheet(signedIn, bars, onCancel = { clearAsk = false }) {
                clearAsk = false
                scope.launch { c.clearHistory(); snack = "Dinleme geçmişi temizlendi" }
            }
        }
        snack?.let { m ->
            LaunchedEffect(m) { delay(2600); if (snack == m) snack = null }
            Snack(m, { snack = null }, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 160.dp))
        }
    } }
}

/**
 * `downloadCollection`: the list's tracks that are not kept or on their way go to the download queue as one batch;
 * returns the snack: the mock's "İndirme başladı", or why nothing started.
 */
internal fun downloadAll(c: AppContainer, items: List<com.muratkirazkaya.meiro.playback.QItem>, name: String, cover: String): String {
    val ask = c.downloader.download(items, name, cover)
    return when {
        ask.started > 0 -> DOWNLOAD_STARTED
        ask.kept > 0 && ask.pending == 0 -> "\u201C$name\u201D zaten indirildi"
        ask.pending > 0 -> "\u201C$name\u201D zaten iniyor"
        else -> "\u201C$name\u201D içinde indirilecek şarkı yok"
    }
}

/** The mock's snack when a download starts (`SHEET.menu` "İndir"). */
const val DOWNLOAD_STARTED = "İndirme başladı"

/**
 * The downloads tab: the storage summary (read again every few seconds and on every change), the batches on their way,
 * the kept tracks in the chosen order. A row plays the downloads from that track; ⋮ opens the track menu; × stops a
 * batch; "Sırala" goes round newest, title and size.
 */
@Composable
private fun DownloadsRoute(c: AppContainer, onSnack: (String) -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalMenus.current
    val openPlayer = LocalOpenPlayer.current
    val version by c.downloads.version.collectAsState()
    val ytv by c.ytCache.version.collectAsState()
    val scv by c.scCache.version.collectAsState()
    val batches by c.downloader.state.collectAsState()
    val linkVersion by c.linker.version.collectAsState()
    val online by com.muratkirazkaya.meiro.downloads.Online.state.collectAsState()
    val now by PlaybackHost.ui.collectAsState()
    var sort by rememberSaveable { mutableStateOf(DownloadSort.Newest) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
    val meter by produceState<com.muratkirazkaya.meiro.ui.screens.MeterUi?>(null, version, ytv, scv, tick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val (dlBytes, n) = c.downloads.usage()
            val free = try { android.os.StatFs(ctx.filesDir.path).availableBytes } catch (e: Exception) { 0L }
            DownloadsMapping.meter(dlBytes, n, c.ytCache.usage(), c.scCache.usage(), free)
        }
    }
    val items = remember(version, sort) { DownloadsMapping.sorted(c.downloads.list(), sort) }
    val playingKey = now?.item?.key
    val m = meter ?: DownloadsMapping.meter(0, 0, 0, 0, 0)
    val ui = remember(items, batches, m, linkVersion, playingKey, online) {
        DownloadsMapping.build(items, batches, m, { src, key -> c.links.song(src, key)?.state }, playingKey, !online)
    }
    val queue = remember(items) { items.map { it.toQItem() } }
    com.muratkirazkaya.meiro.ui.screens.DownloadsScreen(
        ui, playing = now?.playing == true,
        actions = com.muratkirazkaya.meiro.ui.screens.DownloadsActions(
            onStopAll = { c.downloader.cancelAll() },
            onStop = { c.downloader.cancel(it) },
            onSort = {
                sort = DownloadSort.entries[(sort.ordinal + 1) % DownloadSort.entries.size]
                onSnack("Sıralama: ${sort.label}")
            },
            onRow = { i -> if (!openIfPlaying(queue, i, openPlayer)) PlaybackHost.playQueue(ctx, queue, i, "İndirilenler") },
            onMore = { i -> queue.getOrNull(i)?.let { menus.track(MenuTarget(it)) } },
            onLink = { i -> queue.getOrNull(i)?.let { menus.link(LinkTarget.song(it)) } },
        ),
    )
}

/** Opens the full player (the mini player's tap, or the mock's `play` on the track already playing). */
internal val LocalOpenPlayer = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** The mock's `play` on the track that is playing opens the full player instead of starting the track again. */
private fun openIfPlaying(items: List<com.muratkirazkaya.meiro.playback.QItem>, i: Int, open: () -> Unit): Boolean {
    val k = items.getOrNull(i)?.key ?: return false
    if (PlaybackHost.ui.value?.item?.key != k) return false
    open(); return true
}

private fun playRow(ctx: android.content.Context, items: List<com.muratkirazkaya.meiro.playback.QItem>, i: Int, source: String, open: () -> Unit) {
    if (!openIfPlaying(items, i, open)) PlaybackHost.playQueue(ctx, items, i, source)
}

/** The account avatar's letter: the first letter of the greeting's name, "M" without an account. */
internal fun avatarInitial(email: String): String =
    HomeMapping.firstName(email)?.take(1)?.uppercase(java.util.Locale.forLanguageTag("tr")) ?: "M"

/**
 * Home: rebuilt from the store when the lists, the history or what plays change, and every half minute (the greeting
 * and "today" follow the clock). The Daily mix card opens the mix page; the history button leads to a screen that does
 * not exist yet.
 */
@Composable
private fun HomeRoute(c: AppContainer, email: String, onOpen: (String) -> Unit, onMix: () -> Unit, onSnack: (String) -> Unit, onStats: () -> Unit, onHistory: () -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalMenus.current
    val openPlayer = LocalOpenPlayer.current
    val version by c.libraries.version.collectAsState()
    val historyVersion by c.historyVersion.collectAsState()
    val linkVersion by c.linker.version.collectAsState()
    val now by PlaybackHost.ui.collectAsState()
    var clock by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); clock = ZonedDateTime.now() } }
    val playingKey = now?.item?.key
    val minute = clock.withSecond(0).withNano(0)
    val firstRead by c.firstRead.collectAsState()
    val data = remember(version, historyVersion, linkVersion, playingKey, minute, email) {
        HomeMapping.build(email, ZonedDateTime.now(), c.engine.history(), c.libraries.list(), c.links, c.engine.settings.dailyMixId, playingKey)
    }
    // The mock's `loading`: a new account's first read is on its way and nothing of it is here yet.
    val empty = remember(version, historyVersion) { c.libraries.list().isEmpty() && c.engine.history().isEmpty() }
    if (firstRead && empty) { HomeLoadingScreen(); return }
    HomeScreen(
        data.ui, playing = now?.playing == true,
        actions = HomeActions(
            onStats = onStats,
            onHistory = onHistory,
            onMix = { if (data.mixId != null) onMix() },
            onPlayMix = {
                if (data.mixItems.isNotEmpty()) {
                    PlaybackHost.playQueue(ctx, data.mixItems, 0, "Daily mix")
                    onSnack("Daily mix çalıyor")
                }
            },
            onPick = { i -> data.pickIds.getOrNull(i)?.let(onOpen) },
            // The desktop plays a history row on its own (`historyPlay`).
            onResume = { i -> data.resume.getOrNull(i)?.let { playRow(ctx, listOf(it), 0, "Dinleme geçmişi", openPlayer) } },
            onResumeMore = { i -> data.resume.getOrNull(i)?.let { menus.track(MenuTarget(it)) } },
            onResumeLink = { i -> data.resume.getOrNull(i)?.let { menus.link(LinkTarget.song(it)) } },
        ),
    )
}

@Composable
private fun LibraryRoute(c: AppContainer, onOpen: (String) -> Unit, onMix: () -> Unit, onNew: () -> Unit) {
    val version by c.libraries.version.collectAsState()
    val (rows, mixId) = remember(version) {
        val libs = c.libraries.list()
        val mix = HomeMapping.dailyMix(libs, c.engine.settings.dailyMixId)
        val now = ZonedDateTime.now()
        // The Daily mix is the mock's "Daily mix" row ("Meiro · bugün · 30 parça"), which opens the mix page.
        libs.map { if (it.id == mix?.id) MixMapping.libraryRow(it, now.toLocalDate(), now.zone) else it.toRow() } to mix?.id
    }
    val menus = LocalMenus.current
    if (rows.isEmpty()) LibraryEmptyScreen(onNew)
    else LibraryScreen(
        rows, { id -> if (id == mixId) onMix() else onOpen(id) }, onNew,
        onMore = { id -> c.libraries.get(id)?.let { menus.list(ListMenuTarget.own(it)) } },
    )
}

/**
 * The Daily mix page: the stored mix (the list the settings name, else the one under the shared mix id), rebuilt when
 * the lists or what plays change. Rows play the mix from that track; play and shuffle play all of it.
 */
@Composable
private fun MixRoute(c: AppContainer, onBack: () -> Unit, onSnack: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalMenus.current
    val openPlayer = LocalOpenPlayer.current
    val version by c.libraries.version.collectAsState()
    val now by PlaybackHost.ui.collectAsState()
    val lib = remember(version) { HomeMapping.dailyMix(c.libraries.list(), c.engine.settings.dailyMixId) }
    // The mix is gone (no mix stored any more): back to where it was opened from.
    if (lib == null) { LaunchedEffect(Unit) { onBack() }; return }
    val playingKey = now?.item?.key
    val ui = remember(lib, playingKey) { val t = ZonedDateTime.now(); MixMapping.page(lib, c.links, playingKey, t.toLocalDate(), t.zone) }
    fun play(from: Int, items: List<com.muratkirazkaya.meiro.playback.QItem>) { if (items.isNotEmpty()) PlaybackHost.playQueue(ctx, items, from, "Daily mix") }
    MixScreen(
        ui, playing = now?.playing == true,
        actions = DetailActions(
            onBack = onBack,
            onPlay = { play(0, lib.items.map { it.toQItem() }) },
            onShuffle = { play(0, lib.items.map { it.toQItem() }.shuffled()) },
            onRow = { i -> lib.items.map { it.toQItem() }.let { q -> if (!openIfPlaying(q, i, openPlayer)) play(i, q) } },
            onMore = { i -> lib.items.getOrNull(i)?.let { menus.track(MenuTarget(it.toQItem(), listId = lib.id)) } },
            onLink = { i -> lib.items.getOrNull(i)?.let { menus.link(LinkTarget.song(it.toQItem())) } },
            onDownload = { onSnack(downloadAll(c, lib.items.map { it.toQItem() }, lib.name, lib.cover)) },
            onSettings = onSettings,
        ),
    )
}

@Composable
private fun SearchRoute(c: AppContainer, onAlbum: (String) -> Unit, onArtist: (String) -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalMenus.current
    val openPlayer = LocalOpenPlayer.current
    val ctl = c.search
    val s by ctl.state.collectAsState()
    val now by PlaybackHost.ui.collectAsState()
    val listsVersion by c.libraries.version.collectAsState()
    val linkVersion by c.linker.version.collectAsState()
    val playingKey = now?.item?.key
    val ui = remember(s, playingKey, listsVersion, linkVersion) {
        SearchMapping.toUi(s, { src, id -> c.links.song(src, id)?.state }, playingKey, { c.linker.artistState(it) })
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
                with(SearchMapping) { playRow(ctx, items.map { it.toQItem() }, i, "\u201C${s.submitted}\u201D araması", openPlayer) }
            },
            onSongMore = { i -> with(SearchMapping) { SearchMapping.songs(ctl.state.value).getOrNull(i)?.let { menus.track(MenuTarget(it.toQItem())) } } },
            onEnd = ctl::loadMore,
            onArtist = { i -> ui.artists.getOrNull(i)?.let { onArtist(it.key) } },
            onAlbum = { i -> ui.albums.getOrNull(i)?.let { onAlbum(it.key) } },
            onSongLink = { i -> with(SearchMapping) { SearchMapping.songs(ctl.state.value).getOrNull(i)?.let { menus.link(LinkTarget.song(it.toQItem())) } } },
            onArtistLink = { i -> ui.artists.getOrNull(i)?.let { menus.link(LinkTarget.artist(it.name)) } },
            onRetry = ctl::retryYtm,
        ),
    )
}

@Composable
private fun PlaylistRoute(c: AppContainer, id: String, onBack: () -> Unit, onSnack: (String) -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalMenus.current
    val openPlayer = LocalOpenPlayer.current
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
        onRow = { i -> lib.items.map { it.toQItem() }.let { q -> if (!openIfPlaying(q, i, openPlayer)) play(i, q) } },
        onMore = { i -> lib.items.getOrNull(i)?.let { menus.track(MenuTarget(it.toQItem(), listId = lib.id)) } },
        onListMenu = { menus.list(ListMenuTarget.own(lib)) },
        onLink = { i -> lib.items.getOrNull(i)?.let { menus.link(LinkTarget.song(it.toQItem())) } },
        onDownload = { onSnack(downloadAll(c, lib.items.map { it.toQItem() }, lib.name, lib.cover)) },
    )
}

/** A browse page as it loads: null while it loads, then the page or the error's message. */
private class Loaded(val page: BrowsePage? = null, val error: String? = null)

@Composable
private fun rememberBrowsePage(c: AppContainer, id: String, retry: Int): Loaded? {
    val loaded by produceState<Loaded?>(null, id, retry) {
        value = try {
            Loaded(c.browsePage(id))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.i(AppContainer.TAG, "browse: $id failed: $e")
            Loaded(error = e.message ?: e.javaClass.simpleName)
        }
    }
    return loaded
}

/**
 * An album page (`browse` `MPR...`): rows play the album from that track, play and shuffle play all of it, and the copy
 * button makes a Meiro list of it with the mock's snack.
 */
@Composable
private fun AlbumRoute(c: AppContainer, id: String, onBack: () -> Unit, onSnack: (String) -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalMenus.current
    val openPlayer = LocalOpenPlayer.current
    val scope = rememberCoroutineScope()
    val now by PlaybackHost.ui.collectAsState()
    val version by c.libraries.version.collectAsState()
    var retry by remember { mutableStateOf(0) }
    val loaded = rememberBrowsePage(c, id, retry)
    val linkVersion by c.linker.version.collectAsState()
    val playingKey = now?.item?.key
    val data = remember(loaded, playingKey, version, linkVersion) {
        loaded?.page?.let { BrowseMapping.album(id, it, { src, key -> c.links.song(src, key)?.state }, playingKey) }
    }
    fun play(from: Int, items: List<com.muratkirazkaya.meiro.playback.QItem>) = PlaybackHost.playQueue(ctx, items, from, data?.ui?.title.orEmpty())
    AlbumScreen(
        data?.ui, playing = now?.playing == true,
        actions = DetailActions(
            onBack = onBack,
            onPlay = { data?.let { if (it.tracks.isNotEmpty()) play(0, it.queue) } },
            onShuffle = { data?.let { if (it.tracks.isNotEmpty()) play(0, it.queue.shuffled()) } },
            onRow = { i -> data?.let { if (!openIfPlaying(it.queue, i, openPlayer)) play(i, it.queue) } },
            onCopy = {
                data?.let { d ->
                    if (d.tracks.isNotEmpty()) scope.launch {
                        c.copyAsList(d.ui.title, d.cover, d.listItems())
                        onSnack("\u201C${d.ui.title}\u201D Meiro listesi olarak kopyalandı")
                    }
                }
            },
            onRetry = { retry++ },
            onMore = { i -> data?.let { d -> d.queue.getOrNull(i)?.let { menus.track(MenuTarget(it, albumId = id, albumArt = d.cover)) } } },
            onLink = { i -> data?.queue?.getOrNull(i)?.let { menus.link(LinkTarget.song(it)) } },
            onListMenu = {
                data?.let { d -> menus.list(ListMenuTarget(null, ListMenuUi(d.queue.count { c.linker.stateOf(it).let { s -> s != LinkState.MATCHED && s != LinkState.OTHER } }, own = false), d.ui.title, d.cover, d.listItems())) }
            },
            // An album's songs carry no cover of their own: the album's is what they are kept with (`pageArt`).
            onDownload = { data?.let { d -> onSnack(downloadAll(c, d.queue.map { q -> if (q.thumbnail.isEmpty()) q.copy(thumbnail = d.cover) else q }, d.ui.title, d.cover)) } },
        ),
        error = loaded?.error,
    )
}

/** An artist page (`browse` `UC...`): its popular tracks play, its album tiles open the album, similar artists open theirs. */
@Composable
private fun ArtistRoute(c: AppContainer, id: String, onBack: () -> Unit, onAlbum: (String) -> Unit, onArtist: (String) -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalMenus.current
    val openPlayer = LocalOpenPlayer.current
    val now by PlaybackHost.ui.collectAsState()
    val version by c.libraries.version.collectAsState()
    var retry by remember { mutableStateOf(0) }
    val loaded = rememberBrowsePage(c, id, retry)
    val linkVersion by c.linker.version.collectAsState()
    val playingKey = now?.item?.key
    val data = remember(loaded, playingKey, version, linkVersion) {
        loaded?.page?.let { BrowseMapping.artist(id, it, { src, key -> c.links.song(src, key)?.state }, playingKey) }
    }
    fun play(from: Int, items: List<com.muratkirazkaya.meiro.playback.QItem>) = PlaybackHost.playQueue(ctx, items, from, data?.ui?.name.orEmpty())
    ArtistScreen(
        data?.ui, playing = now?.playing == true,
        actions = DetailActions(
            onBack = onBack,
            onPlay = { data?.let { if (it.tracks.isNotEmpty()) play(0, it.queue) } },
            onShuffle = { data?.let { if (it.tracks.isNotEmpty()) play(0, it.queue.shuffled()) } },
            onRow = { i -> data?.let { if (!openIfPlaying(it.queue, i, openPlayer)) play(i, it.queue) } },
            onAlbum = { i -> data?.albumIds?.getOrNull(i)?.let(onAlbum) },
            onArtist = { i -> data?.artistIds?.getOrNull(i)?.let(onArtist) },
            onRetry = { retry++ },
            onMore = { i -> data?.queue?.getOrNull(i)?.let { menus.track(MenuTarget(it)) } },
            onLink = { i -> data?.queue?.getOrNull(i)?.let { menus.link(LinkTarget.song(it)) } },
            onArtistLink = { data?.ui?.name?.takeIf { it.isNotBlank() }?.let { menus.link(LinkTarget.artist(it)) } },
        ),
        error = loaded?.error,
    )
}

/**
 * M6d, a MusicBrainz artist page (`mbArtistPage`): the top recordings as they load and light up, a playable one plays
 * the artist's playable recordings from it (the desktop's `playFrom`), one with nothing to play does nothing; play and
 * shuffle play the playable ones; a similar artist opens its own page; "Bağı değiştir" opens the artist's link sheet.
 */
@Composable
private fun MbArtistRoute(c: AppContainer, mbid: String, onBack: () -> Unit, onArtist: (String) -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalMenus.current
    val openPlayer = LocalOpenPlayer.current
    val now by PlaybackHost.ui.collectAsState()
    val version by c.libraries.version.collectAsState()
    val linkVersion by c.linker.version.collectAsState()
    var retry by remember { mutableStateOf(0) }
    val page by produceState<com.muratkirazkaya.meiro.musicbrainz.Artists.Page?>(null, mbid, retry) {
        c.mbArtistPage(mbid).collect { value = it }
    }
    val playingKey = now?.item?.key
    val p = page
    val data = remember(p, playingKey, version, linkVersion) {
        p?.takeIf { !it.loading && it.error.isEmpty() }?.let { MbArtistMapping.page(it, c.mbArtistName(mbid), { src, key -> c.links.song(src, key)?.state }, playingKey) }
    }
    fun play(from: Int, items: List<com.muratkirazkaya.meiro.playback.QItem>) { if (items.isNotEmpty()) PlaybackHost.playQueue(ctx, items, from, data?.ui?.name.orEmpty()) }
    ArtistScreen(
        data?.ui, playing = now?.playing == true,
        actions = DetailActions(
            onBack = onBack,
            onPlay = { data?.let { play(0, it.queue) } },
            onShuffle = { data?.let { play(0, it.queue.shuffled()) } },
            onRow = { i -> data?.let { d -> d.queueIndex(i).takeIf { it >= 0 }?.let { q -> if (!openIfPlaying(d.queue, q, openPlayer)) play(q, d.queue) } } },
            onArtist = { i -> data?.similarIds?.getOrNull(i)?.let(onArtist) },
            onRetry = { retry++ },
            onMore = { i -> data?.let { d -> d.queue.getOrNull(d.queueIndex(i))?.let { menus.track(MenuTarget(it)) } } },
            onLink = { i -> data?.let { d -> d.queue.getOrNull(d.queueIndex(i))?.let { menus.link(LinkTarget.song(it)) } } },
            onArtistLink = { data?.ui?.name?.takeIf { it.isNotBlank() }?.let { menus.link(LinkTarget.artist(it)) } },
        ),
        error = p?.error?.takeIf { it.isNotEmpty() },
        errorTitle = "MusicBrainz cevap vermedi",
    )
}

/**
 * The full player over the shell, and its queue sheet on tab [sheetTab] (null: closed): what plays, the seek bar, the
 * controls (shuffle and repeat change the queue's behaviour), the source buttons (a switch plays on from the other
 * provider at the same place, and the lists holding the track follow it), the up-next list, the lyrics (loaded when the
 * tab shows, again for each new track while it shows) and the related tracks (a tap plays the related list from that
 * track). The status bar turns light over its dark background.
 */
@Composable
private fun PlayerRoute(
    c: AppContainer, p: com.muratkirazkaya.meiro.playback.PlayerUi, bars: SystemBars, sheetTab: com.muratkirazkaya.meiro.ui.screens.PlayerTab?,
    onSheet: (com.muratkirazkaya.meiro.ui.screens.PlayerTab?) -> Unit,
    onClose: () -> Unit, onSnack: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val menus = LocalMenus.current
    val version by c.libraries.version.collectAsState()
    LightStatusBarIcons()
    val linkVersion by c.linker.version.collectAsState()
    val linkOf: (String, String) -> String? = remember(version, linkVersion) {
        val libs = c.libraries.list();
        { src: String, key: String ->
            c.links.song(src, key)?.state?.takeIf { it.isNotEmpty() }
                ?: libs.asSequence().flatMap { it.items }.firstOrNull { it.activeRef() == (src to key) }?.state
        }
    }
    // "Rengi kapaktan al": off, the player keeps the mock's own gradient instead of the cover's colours.
    val coverColor = c.prefs.collectAsState().value.coverColor
    val background = com.muratkirazkaya.meiro.ui.components.rememberCoverBrush(p.item.thumbnail.takeIf { it.isNotBlank() && coverColor })
    val ui = NowPlayingMapping.player(p, linkOf(p.item.provider(), p.item.key), background)
    com.muratkirazkaya.meiro.ui.screens.NowPlayingScreen(
        ui, bars,
        com.muratkirazkaya.meiro.ui.screens.NowPlayingActions(
            onClose = onClose,
            onSeek = { PlaybackHost.seekTo(ctx, it) },
            onToggle = { if (!p.loading) PlaybackHost.togglePlay(ctx) },
            onPrev = { PlaybackHost.previous(ctx) },
            onNext = { PlaybackHost.next(ctx) },
            onShuffle = { PlaybackHost.setShuffle(ctx, !p.shuffle) },
            onRepeat = { PlaybackHost.cycleRepeat(ctx) },
            onSource = { prov ->
                val src = if (prov == com.muratkirazkaya.meiro.ui.components.Provider.Sc) com.muratkirazkaya.meiro.data.Source.SOUNDCLOUD else com.muratkirazkaya.meiro.data.Source.YTM
                val name = if (src == com.muratkirazkaya.meiro.data.Source.SOUNDCLOUD) "SoundCloud" else "YouTube Music"
                when {
                    src == p.item.provider() -> {}
                    p.item.refFor(src) == null -> onSnack("$name'da bu şarkı bulunamadı")
                    else -> PlaybackHost.switchSource(ctx, src) { old -> scope.launch { c.switchListSource(old, src) } }
                }
            },
            onTab = { onSheet(it) },
            onMore = { menus.track(MenuTarget(p.item)) },
            onLink = { menus.link(LinkTarget.song(p.item)) },
        ),
    )
    if (sheetTab != null) {
        val tabs = c.trackTabs
        val key = p.item.key
        // The tab's load starts when it shows and again when the track changes under it (`loadPanel`).
        LaunchedEffect(sheetTab, key) {
            when (sheetTab) {
                com.muratkirazkaya.meiro.ui.screens.PlayerTab.Lyrics -> tabs.loadLyrics(p.item, p.durationMs)
                com.muratkirazkaya.meiro.ui.screens.PlayerTab.Related -> tabs.loadRelated(p.item)
                else -> {}
            }
        }
        val lyricsState by tabs.lyrics.collectAsState()
        val relatedState by tabs.related.collectAsState()
        val static by c.staticLyrics.collectAsState()
        val lyrics = remember(key, lyricsState, static) { NowPlayingMapping.lyrics(key, lyricsState, static) }
        val (related, relatedQueue) = remember(key, relatedState, version, linkVersion) { NowPlayingMapping.related(key, relatedState, key, linkOf) }
        com.muratkirazkaya.meiro.ui.screens.UpNextSheet(
            NowPlayingMapping.upNext(p, linkOf), p.playing, bars,
            com.muratkirazkaya.meiro.ui.screens.UpNextActions(
                onClose = { onSheet(null) },
                onAutoPlay = { PlaybackHost.setAutoPlay(ctx, it) },
                onClear = { PlaybackHost.clearUpcoming(ctx) },
                onRow = { PlaybackHost.playFromQueue(ctx, it) },
                onMove = { from, to -> PlaybackHost.moveQueueItem(ctx, from, to) },
                onTab = { onSheet(it) },
                onLyricsSynced = { c.saveStaticLyrics(!it) },
                onLyricSeek = { PlaybackHost.seekTo(ctx, it) },
                // The desktop's related rows (`directQueue`, source "Related"): the related list becomes the queue from
                // that track. The playing track's row does nothing (the player is already open).
                onRelated = { i -> if (relatedQueue.getOrNull(i)?.key != key) PlaybackHost.playQueue(ctx, relatedQueue, i, RELATED_SOURCE) },
                onRowMore = { i -> p.queue.items.getOrNull(i)?.let { menus.track(MenuTarget(it)) } },
                onRelatedMore = { i -> relatedQueue.getOrNull(i)?.let { menus.track(MenuTarget(it)) } },
                onRowLink = { i -> p.queue.items.getOrNull(i)?.let { menus.link(LinkTarget.song(it)) } },
                onRelatedLink = { i -> relatedQueue.getOrNull(i)?.let { menus.link(LinkTarget.song(it)) } },
            ),
            tab = sheetTab, lyrics = lyrics, related = related, positionMs = p.positionMs,
        )
    }
}

/** The name of a queue played from the related tab (the player's "Çalınıyor" line). */
private const val RELATED_SOURCE = "Benzerler"

/** Light status bar icons while the full player (dark, whatever the theme) is shown; the theme's own afterwards. */
@Composable
private fun LightStatusBarIcons() {
    val view = androidx.compose.ui.platform.LocalView.current
    val dark = Meiro.colors == com.muratkirazkaya.meiro.ui.theme.DarkMeiroColors
    androidx.compose.runtime.DisposableEffect(view, dark) {
        val window = (view.context as? android.app.Activity)?.window
        val ctl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        ctl?.isAppearanceLightStatusBars = false
        onDispose { ctl?.isAppearanceLightStatusBars = !dark }
    }
}

/**
 * "Dinleme geçmişi": rebuilt when the history, the lists, the links or what plays change, and every half minute (the day
 * headings follow the clock). A row plays that one track (the desktop's `historyPlay`), ⋮ opens the track menu, the link
 * button the link sheet; ⋮ in the top bar opens the history menu.
 */
@Composable
private fun HistoryRoute(c: AppContainer, signedIn: Boolean, onBack: () -> Unit, onMenu: () -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalMenus.current
    val openPlayer = LocalOpenPlayer.current
    val historyVersion by c.historyVersion.collectAsState()
    val version by c.libraries.version.collectAsState()
    val linkVersion by c.linker.version.collectAsState()
    val now by PlaybackHost.ui.collectAsState()
    var filter by rememberSaveable { mutableStateOf(HistoryMapping.ALL) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var clock by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); clock = ZonedDateTime.now() } }
    val playingKey = now?.item?.key
    val ui = remember(historyVersion, version, linkVersion, playingKey, filter, query, searching, clock.toLocalDate(), signedIn) {
        HistoryMapping.build(c.engine.history(), filter, query, searching, ZonedDateTime.now(), c.engine.deviceName, c.libraries.list(), c.links, playingKey, signedIn)
    }
    // A device chip whose device has no plays left (after clearing) falls back to every play.
    if (ui.chips.none { it.key == filter }) filter = HistoryMapping.ALL
    val plays = remember(ui) { ui.lines.filterIsInstance<HistoryLine.Play>().map { it.item } }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    com.muratkirazkaya.meiro.ui.screens.HistoryScreen(
        ui, playing = now?.playing == true,
        actions = com.muratkirazkaya.meiro.ui.screens.HistoryActions(
            onBack = onBack,
            onSearch = { searching = !searching; query = "" },
            onQuery = { query = it },
            onMenu = onMenu,
            onChip = { if (it != filter) { filter = it; scope.launch { state.scrollToItem(0) } } },
            onRow = { i -> plays.getOrNull(i)?.let { playRow(ctx, listOf(it), 0, "Dinleme geçmişi", openPlayer) } },
            onMore = { i -> plays.getOrNull(i)?.let { menus.track(MenuTarget(it)) } },
            onLink = { i -> plays.getOrNull(i)?.let { menus.link(LinkTarget.song(it)) } },
        ),
        state = state,
    )
}

/**
 * "Özet": the month or the year, from the server signed in (every device) and from this device's history otherwise or
 * when the server cannot be reached. The local numbers show at once and the server's replace them when they come.
 */
@Composable
private fun StatsRoute(c: AppContainer, signedIn: Boolean, onBack: () -> Unit) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.Month) }
    val historyVersion by c.historyVersion.collectAsState()
    val now = remember(period) { ZonedDateTime.now() }
    val history = remember(historyVersion) { c.engine.history() }
    val local = remember(history, period) { StatsMapping.local(history, period, now) }
    val summary by produceState(local, period, historyVersion) { value = c.stats(period, now).first }
    val ui = remember(summary, history, period) {
        StatsMapping.ui(summary, StatsMapping.newArtists(history, period, now), period, now, signedIn, StatsMapping.coverIndex(history))
    }
    com.muratkirazkaya.meiro.ui.screens.StatsScreen(ui, com.muratkirazkaya.meiro.ui.screens.StatsActions(onBack = onBack, onPeriod = { period = it }))
}

/**
 * "Ayarlar": each row reads the setting or the store it shows (sizes again every few seconds) and changes it. The rows
 * whose feature the phone does not have (YouTube Music sign-in, Last.fm, the wallpaper colour and the swatches,
 * loudness, the source order, the quality on mobile data) show the mock's text and do nothing.
 */
@Composable
private fun SettingsRoute(
    c: AppContainer, email: String, signedIn: Boolean, onBack: () -> Unit, onHistory: () -> Unit, onExit: () -> Unit, onSnack: (String) -> Unit,
    onYtmLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ytmSession by c.ytmAccount.session.collectAsState()
    val prefs by c.prefs.collectAsState()
    val mixBusy by c.mixBusy.collectAsState()
    val historyVersion by c.historyVersion.collectAsState()
    val libsVersion by c.libraries.version.collectAsState()
    val ytv by c.ytCache.version.collectAsState()
    val scv by c.scCache.version.collectAsState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
    var explore by remember(prefs.explore) { mutableStateOf(prefs.explore / 100f) }
    var ytdlp by remember { mutableStateOf("") }
    var ytdlpBusy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { ytdlp = c.ytdlpVersion().orEmpty() }
    val sizes by produceState(Triple("", "", "") to "", ytv, scv, tick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val limit = com.muratkirazkaya.meiro.downloads.AudioCache.DEFAULT_LIMIT_BYTES
            val (covers, coverCount) = c.coverCacheUsage()
            Triple(
                "${com.muratkirazkaya.meiro.ui.components.sizeText(c.ytCache.usage())} / ${com.muratkirazkaya.meiro.ui.components.sizeText(limit)} · ${StatsMapping.number(c.ytCache.count())} şarkı",
                "${com.muratkirazkaya.meiro.ui.components.sizeText(c.scCache.usage())} / ${com.muratkirazkaya.meiro.ui.components.sizeText(limit)} · ${StatsMapping.number(c.scCache.count())} şarkı",
                "${com.muratkirazkaya.meiro.ui.components.sizeText(covers)} · ${StatsMapping.number(coverCount)} kapak",
            ) to "sanatçı 30 gün, şarkı 1 gün · ${com.muratkirazkaya.meiro.ui.components.sizeText(c.mbCacheUsage())}"
        }
    }
    val ui = remember(prefs, mixBusy, historyVersion, libsVersion, sizes, tick, explore, ytdlp, ytdlpBusy, email, signedIn, ytmSession) {
        val (ytmSub, ytmButton) = AccountMapping.ytmLine(ytmSession)
        val mix = HomeMapping.dailyMix(c.libraries.list(), c.engine.settings.dailyMixId)
        val history = c.engine.history()
        com.muratkirazkaya.meiro.ui.screens.SettingsUi(
            accountSub = AccountMapping.settingsLine(c, email, signedIn),
            accountButton = if (signedIn) "Çıkış" else "Giriş",
            server = AppContainer.displayServer(c.engine.settings.syncUrl),
            ytmSub = ytmSub, ytmButton = ytmButton,
            theme = prefs.theme, coverColor = prefs.coverColor, recommend = prefs.suggestions, explore = explore,
            dailyMix = AccountMapping.dailyMixLine(
                mix?.items?.maxOfOrNull { it.updatedAt }, c.engine.settings.dailyMixDate, mixBusy, prefs.suggestions, java.time.Instant.now(), java.time.ZoneId.systemDefault(),
            ),
            dailyMixBusy = mixBusy,
            wifiOnly = prefs.wifiOnly,
            ytCache = sizes.first.first, scCache = sizes.first.second, coverCache = sizes.first.third, mbCache = sizes.second,
            history = "${StatsMapping.number(history.size)} çalma · 30 sn ya da yarısı sayılır",
            version = "Meiro ${c.appVersion()}".trim(),
            ytdlp = (if (ytdlp.isEmpty()) "" else "$ytdlp · ") + "her şarkının sesini bulur",
            ytdlpBusy = ytdlpBusy,
        )
    }
    com.muratkirazkaya.meiro.ui.screens.SettingsScreen(
        ui,
        com.muratkirazkaya.meiro.ui.screens.SettingsActions(
            onBack = onBack,
            onAccount = onExit,
            onServer = { if (signedIn) onSnack("Sunucuyu değiştirmek için önce çıkış yap") else onExit() },
            // M6f: signed out the row and "Giriş" open the sign-in page; signed in "Çıkış" signs out at once (desktop `signOut`).
            onYtm = {
                if (ytmSession == null) onYtmLogin()
                else scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.ytmAccount.signOut() }; onSnack("YouTube Music oturumu kapatıldı") }
            },
            onYtmRow = if (ytmSession == null) onYtmLogin else null,
            onTheme = { t -> c.updatePrefs { it.copy(theme = t) } },
            onCoverColor = { c.updatePrefs { it.copy(coverColor = !it.coverColor) } },
            onRecommend = {
                val on = !prefs.suggestions
                c.updatePrefs { it.copy(suggestions = on) }
                // On again: the desktop's `recommendTick` right away.
                if (on) scope.launch { c.recommendTick("settings") }
            },
            onExplore = { explore = it },
            onExploreDone = { val v = Math.round(explore * 100); c.updatePrefs { it.copy(explore = v) } },
            onDailyMix = {
                if (prefs.suggestions && !mixBusy) scope.launch {
                    val wrote = c.recommendTick("settings: make it now", remake = true)
                    onSnack(if (wrote) "Daily mix yenilendi" else "Daily mix yapılamadı")
                }
            },
            onWifiOnly = { c.updatePrefs { it.copy(wifiOnly = !it.wifiOnly) } },
            onClearYt = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { c.ytCache.clear() }; onSnack("YouTube Music önbelleği temizlendi") },
            onClearSc = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { c.scCache.clear() }; onSnack("SoundCloud önbelleği temizlendi") },
            onClearCovers = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { c.clearCoverCache(); tick++ }; onSnack("Kapak önbelleği temizlendi") },
            onClearMb = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { c.clearMbCache(); tick++ }; onSnack("MusicBrainz önbelleği temizlendi") },
            onHistory = onHistory,
            onYtdlp = {
                if (!ytdlpBusy) {
                    ytdlpBusy = true
                    scope.launch {
                        val msg = c.updateYtdlp()
                        ytdlp = c.ytdlpVersion().orEmpty()
                        ytdlpBusy = false
                        onSnack(msg)
                    }
                }
            },
        ),
    )
}

/**
 * M6f, "YouTube Music girişi": Google's sign-in page in a WebView whose cookies and storage are wiped before it opens and
 * again as soon as the session is read or the page is left (the desktop's throwaway browser profile). Once the page has
 * a SAPISID cookie for music.youtube.com for 3 s (`waitSignIn`), its `Cookie:` header is read, the WebView's cookies and
 * storage are wiped, YouTube Music is asked whom it signs in as, and a session it takes is kept in the encrypted store;
 * [onDone] goes back to Ayarlar with the snack. A session it does not take leaves a snack and a fresh page.
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled") // Google's sign-in page needs JavaScript.
@Composable
private fun YtmLoginRoute(c: AppContainer, onBack: () -> Unit, onDone: (String) -> Unit, onSnack: (String) -> Unit) {
    val attempt = remember { c.ytmAccount.beginSignIn() }
    var host by remember { mutableStateOf(com.muratkirazkaya.meiro.youtube.YtmSignIn.host(com.muratkirazkaya.meiro.youtube.YtmSignIn.SIGN_IN_URL)) }
    // A new page (and a new wipe) after a sign-in YouTube Music did not take.
    var generation by remember { mutableStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { c.ytmAccount.dismissSignIn(attempt); wipeWebData {} }
    }
    LaunchedEffect(generation) {
        ready = false
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { k -> wipeWebData { if (k.isActive) k.resumeWith(Result.success(Unit)) } }
        ready = true
        val header = com.muratkirazkaya.meiro.youtube.YtmSignIn.waitForSession(
            read = { android.webkit.CookieManager.getInstance().getCookie(com.muratkirazkaya.meiro.youtube.YtmSignIn.COOKIE_URL) },
            now = { android.os.SystemClock.elapsedRealtime() }, sleep = { delay(it) },
        )
        android.util.Log.i(AppContainer.YTM_TAG, "ytm: the sign-in page is signed in; reading the session and wiping the page")
        ready = false
        wipeWebData {}
        try {
            val s = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.ytmAccount.completeSignIn(header, attempt) }
            onDone(AccountMapping.ytmSignedIn(s))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.i(AppContainer.YTM_TAG, "ytm: sign-in not taken (${e.javaClass.simpleName})")
            onSnack(
                "YouTube Music girişi olmadı: " +
                    if (e is com.muratkirazkaya.meiro.youtube.YtmSessionGone) "oturum tanınmadı." else "YouTube Music'e ulaşılamadı.",
            )
            generation++
        }
    }
    com.muratkirazkaya.meiro.ui.screens.YtmLoginScreen(host, onBack) {
        if (ready) androidx.compose.runtime.key(generation) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                com.muratkirazkaya.meiro.youtube.YtmSignIn.host(url).takeIf { it.isNotEmpty() }?.let { host = it }
                            }
                        }
                        loadUrl(com.muratkirazkaya.meiro.youtube.YtmSignIn.SIGN_IN_URL)
                    }
                },
                onRelease = { it.stopLoading(); it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Wipes every cookie and all web storage of the app's WebViews (the phone's WebView is used for nothing else). */
private fun wipeWebData(done: () -> Unit) {
    android.webkit.WebStorage.getInstance().deleteAllData()
    val cm = android.webkit.CookieManager.getInstance()
    cm.removeAllCookies { cm.flush(); done() }
}
