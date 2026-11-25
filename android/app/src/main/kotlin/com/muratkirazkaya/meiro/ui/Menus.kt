package com.muratkirazkaya.meiro.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.muratkirazkaya.meiro.ui.components.SheetPresence
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.muratkirazkaya.meiro.AppContainer
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.playback.PlaybackHost
import com.muratkirazkaya.meiro.ui.TrackMenuMapping.toListItem
import com.muratkirazkaya.meiro.ui.screens.AddToSheet
import com.muratkirazkaya.meiro.ui.screens.DeleteListSheet
import com.muratkirazkaya.meiro.ui.screens.ListMenuActions
import com.muratkirazkaya.meiro.ui.screens.ListMenuSheet
import com.muratkirazkaya.meiro.ui.screens.ListMenuUi
import com.muratkirazkaya.meiro.ui.screens.NewListSheet
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.screens.TrackMenuActions
import com.muratkirazkaya.meiro.ui.screens.TrackMenuSheet
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.ui.screens.LinkSheet
import com.muratkirazkaya.meiro.ui.screens.LinkSheetActions
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * What a list ⋮ opened on: one of the user's lists ([listId]), or an album page (copy only). [copyName], [copyCover]
 * and [copyItems] are what "Meiro listesi olarak kopyala" makes a new list of.
 */
data class ListMenuTarget(
    val listId: String?, val ui: ListMenuUi, val copyName: String, val copyCover: String, val copyItems: List<Item>,
    /**
     * M6g: a list of the YouTube Music account opened from the Library's row, whose tracks are not read yet: copy,
     * download and link read them first (`withPageTracks`), then act on them.
     */
    val load: (suspend () -> Pair<String, List<Item>>)? = null,
) {
    companion object {
        fun own(lib: Library) = ListMenuTarget(lib.id, TrackMenuMapping.listMenu(lib), lib.name, lib.cover, lib.items)
    }
}

/** What the link sheet links: a song (to a MusicBrainz recording), or an artist by name. */
data class LinkTarget(val song: QItem? = null, val artist: String? = null) {
    companion object {
        fun song(item: QItem) = LinkTarget(song = item)
        fun artist(name: String) = LinkTarget(artist = name)
    }
}

/** Opens the track menu, the list menu and the link sheet from any screen (provided by the shell). */
class MenuOpeners(val track: (MenuTarget) -> Unit, val list: (ListMenuTarget) -> Unit, val link: (LinkTarget) -> Unit = {}, val sort: (SortTarget) -> Unit = {})

/** M6h: the playlist page's order sheet: the order shown, and what a pick does. */
class SortTarget(val sort: PlSort, val onPick: (PlSortField) -> Unit)

internal val LocalMenus = staticCompositionLocalOf { MenuOpeners({}, {}) }

/** Which menu sheet is open, at most one of each kind at a time; the shell draws them with [MenuLayer]. */
class MenuState {
    var track by mutableStateOf<MenuTarget?>(null)
    var list by mutableStateOf<ListMenuTarget?>(null)
    var addTo by mutableStateOf<MenuTarget?>(null)
    /** The new-list sheet opened from the add-to sheet: the new list will hold this track. */
    var newFor by mutableStateOf<MenuTarget?>(null)
    var newName by mutableStateOf("")
    /** The rename sheet (list id and the name being typed). */
    var rename by mutableStateOf<Pair<String, String>?>(null)
    /** The link sheet (`SHEET.link`). */
    var link by mutableStateOf<LinkTarget?>(null)
    /** "Listeyi sil" asks first (`SHEET.dellist`): the list id and its name. */
    var delete by mutableStateOf<Pair<String, String>?>(null)
    /** M6h: the playlist page's order sheet (`SHEET.plsort`). */
    var sort by mutableStateOf<SortTarget?>(null)

    val open get() = track != null || list != null || addTo != null || newFor != null || rename != null || link != null || delete != null || sort != null

    /** Back closes the sheet on top. */
    fun back() {
        when {
            link != null -> link = null
            sort != null -> sort = null
            delete != null -> delete = null
            rename != null -> rename = null
            newFor != null -> newFor = null
            addTo != null -> addTo = null
            track != null -> track = null
            list != null -> list = null
        }
    }
}

/** Where a menu item leads outside the sheets: the artist and album pages, and the full player closing on the way. */
class MenuNav(val openArtist: (String) -> Unit, val openAlbum: (String) -> Unit, val closePlayer: () -> Unit)

/**
 * The track menu, the add-to sheet, the new-list sheet a track is added to, the list menu and the rename sheet, over
 * everything else, and the link sheet (M6a). Every item does what the desktop does (`songMenu`, `libraryRow`'s menu,
 * `libraryPickerDialog`); "MusicBrainz'e bağla" opens the link sheet and "Hepsini MusicBrainz'e bağla" auto-links the
 * list's tracks; "İndir" and "Hepsini indir" download (M5), the heart likes on YouTube Music (M6h; nothing signed out);
 * "Bunu önerme" (the desktop has no such feature) does nothing.
 */
@Composable
fun MenuLayer(c: AppContainer, m: MenuState, bars: SystemBars, nav: MenuNav, snack: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by c.libraries.version.collectAsState()
    val linkVersion by c.linker.version.collectAsState()
    BackHandler(enabled = m.open) { m.back() }

    fun launch(what: String, block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.i(AppContainer.TAG, "menu: $what failed: $e")
                snack(MENU_FAILED)
            }
        }
    }

    SheetPresence(m.track) { t ->
        val libs = remember(version) { c.libraries.list() }
        val item = t.item
        val held = t.listId?.let { id -> libs.firstOrNull { it.id == id }?.items?.firstOrNull { it.activeRef() == (item.provider() to item.key) } }
        val state = remember(version, linkVersion, item) { c.linker.stateOf(item).ifEmpty { held?.state.orEmpty() } }
        val keptVersion by c.downloads.version.collectAsState()
        val kept = remember(keptVersion, item) { c.downloads.has(item) }
        // M6h: the heart is the account's YouTube Music like of the track's video (`like-in`: it toggles and closes the
        // sheet). Signed out, or for a track with no YouTube Music video, it does nothing.
        val likes by c.ytmLikes.state.collectAsState()
        val videoId = item.refFor(com.muratkirazkaya.meiro.data.Source.YTM)
        LaunchedEffect(videoId) { c.ytmLikes.learn(videoId) }
        TrackMenuSheet(
            TrackMenuMapping.menu(t, state).copy(downloaded = kept, liked = videoId != null && likes[videoId] == com.muratkirazkaya.meiro.youtube.LikeStatus.Liked), bars,
            TrackMenuActions(
                onDismiss = { m.track = null },
                onLike = { if (c.ytmLikes.toggle(videoId) { like, _ -> snack(likeFailed(item.title, like)) }) m.track = null },
                onPlayNext = { PlaybackHost.enqueue(ctx, item, true); m.track = null; snack("Sıradaki olarak eklendi") },
                onQueue = { PlaybackHost.enqueue(ctx, item, false); m.track = null; snack("Kuyruğun sonuna eklendi") },
                onAddTo = { m.track = null; m.addTo = t },
                // "İndir" asks for the track's download (`startDownload`); "İndirildi · kaldır" takes it out (`removeDownloadItem`).
                onDownload = {
                    m.track = null
                    if (kept) launch("remove download") {
                        withContext(Dispatchers.IO) { c.removeDownload(item.provider(), item.key) }
                        snack("“${item.title}” indirilenlerden kaldırıldı")
                    } else {
                        val target = if (item.thumbnail.isEmpty() && t.albumArt.isNotEmpty()) item.copy(thumbnail = t.albumArt) else item
                        val ask = c.downloader.download(listOf(target), null)
                        snack(if (ask.started > 0) DOWNLOAD_STARTED else "“${item.title}” zaten iniyor")
                    }
                },
                onLink = { m.track = null; m.link = LinkTarget.song(item) },
                onSource = { source(ctx, c, t, held, m, snack, ::launch) },
                onArtist = {
                    m.track = null
                    launch("artist") {
                        val id = c.findArtist(TrackMenuMapping.firstArtist(item.artist))
                        if (id == null) snack("Sanatçı bulunamadı") else { nav.closePlayer(); nav.openArtist(id) }
                    }
                },
                onAlbum = {
                    m.track = null
                    launch("album") {
                        val id = t.albumId ?: c.findAlbum(item.album, TrackMenuMapping.firstArtist(item.artist))
                        if (id == null) snack("Albüm bulunamadı") else { nav.closePlayer(); nav.openAlbum(id) }
                    }
                },
                onShare = {
                    TrackMenuMapping.shareLink(item)?.let { share(ctx, item.title, it) }
                    m.track = null
                },
                onRemove = {
                    val listId = t.listId
                    val lib = libs.firstOrNull { it.id == listId }
                    m.track = null
                    if (lib != null && held != null) launch("remove") {
                        c.removeFromList(lib.id, held.key)
                        snack("“${item.title}” “${lib.name}” listesinden çıkarıldı")
                    }
                },
            ),
        )
    }

    SheetPresence(m.addTo) { t ->
        val rows = remember(version) { TrackMenuMapping.addToRows(c.libraries.list(), c.engine.settings.dailyMixId) }
        AddToSheet(
            rows, bars,
            onDismiss = { m.addTo = null },
            onNew = { m.addTo = null; m.newName = ""; m.newFor = t },
            onPick = { id ->
                m.addTo = null
                val name = rows.firstOrNull { it.id == id }?.name.orEmpty()
                launch("add") {
                    val added = c.addToList(id, t.item.toListItem(t.albumArt))
                    snack(if (added) "“$name” listesine eklendi" else "“${t.item.title}” zaten “$name” listesinde")
                }
            },
        )
    }

    SheetPresence(m.newFor) { t ->
        val create = {
            val n = m.newName.trim()
            if (n.isNotEmpty()) {
                m.newFor = null
                launch("new list") {
                    val id = c.createList(n)
                    c.addToList(id, t.item.toListItem(t.albumArt))
                    snack("“$n” listesine eklendi")
                }
            }
        }
        NewListSheet(m.newName, { m.newName = it }, { m.newFor = null }, create, bars)
    }

    SheetPresence(m.list) { l ->
        ListMenuSheet(
            l.ui, bars,
            ListMenuActions(
                onDismiss = { m.list = null },
                onCopy = {
                    m.list = null
                    if (l.load != null) launch("copy") {
                        val (cover, items) = l.load.invoke()
                        if (items.isNotEmpty()) {
                            c.copyAsList(l.copyName, l.copyCover.ifEmpty { cover }, items)
                            snack("“${l.copyName}” Meiro listesi olarak kopyalandı")
                        }
                    } else if (l.copyItems.isNotEmpty()) launch("copy") {
                        c.copyAsList(l.copyName, l.copyCover, l.copyItems)
                        snack("“${l.copyName}” Meiro listesi olarak kopyalandı")
                    }
                },
                onRename = { m.list = null; l.listId?.let { m.rename = it to l.copyName } },
                onDownload = {
                    m.list = null
                    if (l.load != null) launch("download") {
                        val (cover, items) = l.load.invoke()
                        snack(downloadAll(c, items.map { it.toQItem() }, l.copyName, l.copyCover.ifEmpty { cover }))
                    } else snack(downloadAll(c, l.copyItems.map { it.toQItem() }, l.copyName, l.copyCover))
                },
                onLinkAll = {
                    m.list = null
                    launch("link all") {
                        val items = (l.load?.invoke()?.second ?: l.copyItems).map { it.toQItem() }
                        val n = withContext(Dispatchers.IO) { c.linker.autoLinkAll(items, ignoreRecent = true) }
                        snack(if (n == 0) "Bağlanacak parça yok" else "$n parça MusicBrainz'de aranıyor")
                    }
                },
                onDelete = { m.list = null; l.listId?.let { m.delete = it to l.copyName } },
            ),
        )
    }

    SheetPresence(m.delete) { (id, name) ->
        DeleteListSheet(name, bars, onCancel = { m.delete = null }) {
            m.delete = null
            launch("delete") { c.deleteList(id); snack("“$name” silindi") }
        }
    }

    SheetPresence(m.rename) { (id, name) ->
        val save = {
            val n = name.trim()
            if (n.isNotEmpty()) {
                m.rename = null
                launch("rename") { c.renameList(id, n); snack("“$n” olarak yeniden adlandırıldı") }
            }
        }
        NewListSheet(name, { m.rename = id to it }, { m.rename = null }, save, bars, title = "Yeniden adlandır", action = "Kaydet", note = false)
    }

    SheetPresence(m.link) { t -> LinkLayer(c, t, bars, close = { m.link = null }, snack = snack, launch = ::launch) }

    SheetPresence(m.sort) { t -> com.muratkirazkaya.meiro.ui.screens.PlaylistSortSheet(t.sort, bars, onDismiss = { m.sort = null }) { f -> m.sort = null; t.onPick(f) } }
}

/**
 * The link sheet over everything (`linkDialog`): it searches MusicBrainz at once for the song's own names (or the
 * artist's name), again when the keyboard's search key is pressed; the first result is chosen. "Bağla" links the chosen
 * record (a recording also links its first artist), "Hiçbiri: "diğer"" marks the song or artist as one MusicBrainz does
 * not hold; both close the sheet with the mock's snack and go to the server with the next sync.
 */
@Composable
private fun LinkLayer(c: AppContainer, t: LinkTarget, bars: SystemBars, close: () -> Unit, snack: (String) -> Unit, launch: (String, suspend () -> Unit) -> Unit) {
    val initial = remember(t) { LinkMapping.initialQuery(t) }
    var query by remember(t) { mutableStateOf(initial) }
    var asked by remember(t) { mutableStateOf(initial to 0) }
    var found by remember(t) { mutableStateOf<List<com.muratkirazkaya.meiro.musicbrainz.LinkCandidate>?>(null) }
    var failed by remember(t) { mutableStateOf(false) }
    var selected by remember(t) { mutableStateOf(0) }
    LaunchedEffect(t, asked) {
        found = null; failed = false; selected = 0
        val q = asked.first.trim()
        if (q.isEmpty()) { found = emptyList(); return@LaunchedEffect }
        found = try {
            withContext(Dispatchers.IO) {
                withTimeout(com.muratkirazkaya.meiro.musicbrainz.LinkRules.TIMEOUT_MS) {
                    val song = t.song
                    if (song != null) c.linker.searchSong(song, LinkMapping.searchTitle(song, q, initial)) else c.linker.searchArtist(q)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.i(AppContainer.TAG, "link: search \"$q\" timed out"); failed = true; emptyList()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.i(AppContainer.TAG, "link: search \"$q\" failed: $e"); failed = true; emptyList()
        }
        Log.i(AppContainer.TAG, "link: search \"$q\" -> ${found?.size} results")
    }
    val shown = found?.take(com.muratkirazkaya.meiro.musicbrainz.LinkRules.RESULTS_SHOWN)
    fun answer(state: String, chosen: com.muratkirazkaya.meiro.musicbrainz.LinkCandidate?, message: String) {
        close()
        launch("link") {
            withContext(Dispatchers.IO) {
                val song = t.song
                if (song != null) c.linker.applySong(song, state, chosen) else c.linker.applyArtist(t.artist.orEmpty(), state, chosen)
            }
            snack(message)
        }
    }
    LinkSheet(
        LinkMapping.ui(t, query, shown, failed, selected), bars,
        LinkSheetActions(
            onClose = close,
            onQuery = { query = it },
            onSearch = { asked = query to asked.second + 1 },
            onSelect = { selected = it },
            onOther = { answer(com.muratkirazkaya.meiro.data.LinkState.OTHER, null, LINK_OTHER_SNACK) },
            onLink = { shown?.getOrNull(selected)?.let { answer(com.muratkirazkaya.meiro.data.LinkState.MATCHED, it, LINK_DONE_SNACK) } },
        ),
    )
}

/** The mock's snacks after "Bağla" and "Hiçbiri: "diğer"". */
const val LINK_DONE_SNACK = "Bağlandı · bütün cihazlarda görünür"
const val LINK_OTHER_SNACK = "\"Diğer\" olarak işaretlendi"

/** The snack a menu action that failed shows (YouTube Music or the store did not answer). */
const val MENU_FAILED = "Olmadı, biraz sonra tekrar dene"

/**
 * "Kaynağı değiştir": the track plays from its other provider from now on. The track playing switches at the place it
 * reached (and the lists holding it follow, as the player's source buttons do); a row of one of the user's lists
 * switches in that list. A track held by one provider only gets the mock's "… bu şarkı bulunamadı" snack. Elsewhere (a
 * search row held by both) there is nothing to keep the choice in, so nothing happens.
 */
private fun source(
    ctx: Context, c: AppContainer, t: MenuTarget, held: Item?, m: MenuState, snack: (String) -> Unit,
    launch: (String, suspend () -> Unit) -> Unit,
) {
    val item = t.item
    val other = TrackMenuMapping.otherSource(item)
    if (other == null) {
        val missing = if (item.provider() == com.muratkirazkaya.meiro.data.Source.YTM) com.muratkirazkaya.meiro.data.Source.SOUNDCLOUD else com.muratkirazkaya.meiro.data.Source.YTM
        m.track = null
        snack("${TrackMenuMapping.sourceName(missing)}'da bu şarkı bulunamadı")
        return
    }
    when {
        PlaybackHost.ui.value?.item?.key == item.key -> {
            m.track = null
            PlaybackHost.switchSource(ctx, other) { old -> launch("source") { c.switchListSource(old, other) } }
        }
        t.listId != null && held != null -> {
            m.track = null
            launch("source") { c.setListSource(t.listId, held.key, other) }
        }
        else -> {}
    }
}

/** "Bağlantıyı paylaş": Android's share sheet with the track's link. */
internal fun share(ctx: Context, title: String, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, link)
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** M6h: the snack when YouTube refused a like (the desktop's "Could not like “title”"; the mock has none). */
internal fun likeFailed(title: String, like: Boolean) = if (like) "“$title” YouTube Music'te beğenilemedi" else "“$title” beğenisi YouTube Music'te geri alınamadı"

/** M6h: an album's or a playlist's YouTube Music page (`youtube.BrowseURL`: the desktop's "Copy link"). */
internal fun browseUrl(browseId: String): String {
    // Go's `url.PathEscape`: unreserved characters and the path's own sub-delimiters stay, every other byte is %XX.
    val keep = "-._~$&+,:;=@"
    val out = StringBuilder("https://music.youtube.com/browse/")
    for (b in browseId.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in keep)) out.append(c.toChar()) else out.append("%%%02X".format(c))
    }
    return out.toString()
}
