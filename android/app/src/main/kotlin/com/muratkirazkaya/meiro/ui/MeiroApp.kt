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
private const val MIX_ROUTE = "mix"
private const val ALBUM_ROUTE = "album/{id}?tab={tab}"
private const val ARTIST_ROUTE = "artist/{id}?tab={tab}"

/** The shell with the four tab routes; Library lists the lists, and a list opens as `playlist/<id>`. */
@Composable
private fun MainShell(c: AppContainer, bars: SystemBars, email: String) {
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
        ALBUM_ROUTE, ARTIST_ROUTE -> entry?.arguments?.getString("tab")
        else -> route
    }
    val selected = Tab.entries.firstOrNull { it.route == from } ?: if (route == PLAYLIST_ROUTE) Tab.Library else Tab.Home
    val openAlbum = { id: String -> nav.navigate("album/$id?tab=${selected.route}") }
    val openArtist = { id: String -> nav.navigate("artist/$id?tab=${selected.route}") }
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
    val openers = remember { MenuOpeners({ menus.track = it }, { menus.list = it }) }

    CompositionLocalProvider(LocalAvatarInitial provides avatarInitial(email), LocalOpenPlayer provides openPlayer, LocalMenus provides openers) { Box(Modifier.fillMaxSize()) {
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
                    HomeRoute(c, email, onOpen = { nav.navigate("playlist/$it") }, onMix = { nav.navigate(MIX_ROUTE) }, onSnack = { snack = it })
                }
                composable(Tab.Search.route) { SearchRoute(c, openAlbum, openArtist) }
                composable(Tab.Library.route) {
                    LibraryRoute(c, onOpen = { nav.navigate("playlist/$it") }, onMix = { nav.navigate(MIX_ROUTE) }, onNew = openSheet)
                }
                composable(Tab.Downloads.route) { PlaceholderScreen("İndirilenler") }
                composable(ALBUM_ROUTE, listOf(navArgument("tab") { defaultValue = Tab.Search.route })) { e ->
                    AlbumRoute(c, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, onSnack = { snack = it })
                }
                composable(ARTIST_ROUTE, listOf(navArgument("tab") { defaultValue = Tab.Search.route })) { e ->
                    ArtistRoute(c, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, openAlbum, openArtist)
                }
                composable(MIX_ROUTE) { MixRoute(c, onBack = { nav.popBackStack() }) }
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
        snack?.let { m ->
            LaunchedEffect(m) { delay(2600); if (snack == m) snack = null }
            Snack(m, { snack = null }, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 160.dp))
        }
    } }
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
private fun HomeRoute(c: AppContainer, email: String, onOpen: (String) -> Unit, onMix: () -> Unit, onSnack: (String) -> Unit) {
    val ctx = LocalContext.current
    val menus = LocalMenus.current
    val openPlayer = LocalOpenPlayer.current
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
private fun MixRoute(c: AppContainer, onBack: () -> Unit) {
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
                with(SearchMapping) { playRow(ctx, items.map { it.toQItem() }, i, "\u201C${s.submitted}\u201D araması", openPlayer) }
            },
            onSongMore = { i -> with(SearchMapping) { SearchMapping.songs(ctl.state.value).getOrNull(i)?.let { menus.track(MenuTarget(it.toQItem())) } } },
            onEnd = ctl::loadMore,
            onArtist = { i -> ui.artists.getOrNull(i)?.let { onArtist(it.key) } },
            onAlbum = { i -> ui.albums.getOrNull(i)?.let { onAlbum(it.key) } },
        ),
    )
}

@Composable
private fun PlaylistRoute(c: AppContainer, id: String, onBack: () -> Unit) {
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
    val playingKey = now?.item?.key
    val data = remember(loaded, playingKey, version) {
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
            onListMenu = {
                data?.let { d -> menus.list(ListMenuTarget(null, ListMenuUi(d.tracks.size, own = false), d.ui.title, d.cover, d.listItems())) }
            },
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
    val playingKey = now?.item?.key
    val data = remember(loaded, playingKey, version) {
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
        ),
        error = loaded?.error,
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
    val linkOf: (String, String) -> String? = remember(version) {
        val libs = c.libraries.list();
        { src: String, key: String ->
            c.links.song(src, key)?.state?.takeIf { it.isNotEmpty() }
                ?: libs.asSequence().flatMap { it.items }.firstOrNull { it.activeRef() == (src to key) }?.state
        }
    }
    val background = com.muratkirazkaya.meiro.ui.components.rememberCoverBrush(p.item.thumbnail.takeIf { it.isNotBlank() })
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
        val (related, relatedQueue) = remember(key, relatedState, version) { NowPlayingMapping.related(key, relatedState, key, linkOf) }
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
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    androidx.compose.runtime.DisposableEffect(view, dark) {
        val window = (view.context as? android.app.Activity)?.window
        val ctl = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        ctl?.isAppearanceLightStatusBars = false
        onDispose { ctl?.isAppearanceLightStatusBars = !dark }
    }
}
