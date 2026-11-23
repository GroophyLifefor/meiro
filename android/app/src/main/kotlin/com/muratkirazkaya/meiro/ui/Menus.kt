package com.muratkirazkaya.meiro.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
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
import com.muratkirazkaya.meiro.ui.screens.ListMenuActions
import com.muratkirazkaya.meiro.ui.screens.ListMenuSheet
import com.muratkirazkaya.meiro.ui.screens.ListMenuUi
import com.muratkirazkaya.meiro.ui.screens.NewListSheet
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.screens.TrackMenuActions
import com.muratkirazkaya.meiro.ui.screens.TrackMenuSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What a list ⋮ opened on: one of the user's lists ([listId]), or an album page (copy only). [copyName], [copyCover]
 * and [copyItems] are what "Meiro listesi olarak kopyala" makes a new list of.
 */
data class ListMenuTarget(
    val listId: String?, val ui: ListMenuUi, val copyName: String, val copyCover: String, val copyItems: List<Item>,
) {
    companion object {
        fun own(lib: Library) = ListMenuTarget(lib.id, TrackMenuMapping.listMenu(lib), lib.name, lib.cover, lib.items)
    }
}

/** Opens the track menu and the list menu from any screen (provided by the shell). */
class MenuOpeners(val track: (MenuTarget) -> Unit, val list: (ListMenuTarget) -> Unit)

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

    val open get() = track != null || list != null || addTo != null || newFor != null || rename != null

    /** Back closes the sheet on top. */
    fun back() {
        when {
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
 * everything else. Every item does what the desktop does (`songMenu`, `libraryRow`'s menu, `libraryPickerDialog`);
 * "İndir" (downloads, M5), "MusicBrainz'e bağla" and "Hepsini MusicBrainz'e bağla" (link sheet, M6), the heart (YouTube
 * Music likes need a YouTube Music sign-in), "Hepsini indir" (M5) and "Bunu önerme" (the desktop has no such feature) do
 * nothing yet.
 */
@Composable
fun MenuLayer(c: AppContainer, m: MenuState, bars: SystemBars, nav: MenuNav, snack: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by c.libraries.version.collectAsState()
    BackHandler(enabled = m.open) { m.back() }

    fun launch(what: String, block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.i(AppContainer.TAG, "menu: $what failed: $e")
                snack(MENU_FAILED)
            }
        }
    }

    m.track?.let { t ->
        val libs = remember(version) { c.libraries.list() }
        val item = t.item
        val held = t.listId?.let { id -> libs.firstOrNull { it.id == id }?.items?.firstOrNull { it.activeRef() == (item.provider() to item.key) } }
        val state = c.links.song(item.provider(), item.key)?.state?.takeIf { it.isNotEmpty() }
            ?: held?.state ?: libs.asSequence().flatMap { it.items }.firstOrNull { it.activeRef() == (item.provider() to item.key) }?.state
        TrackMenuSheet(
            TrackMenuMapping.menu(t, state), bars,
            TrackMenuActions(
                onDismiss = { m.track = null },
                onPlayNext = { PlaybackHost.enqueue(ctx, item, true); m.track = null; snack("Sıradaki olarak eklendi") },
                onQueue = { PlaybackHost.enqueue(ctx, item, false); m.track = null; snack("Kuyruğun sonuna eklendi") },
                onAddTo = { m.track = null; m.addTo = t },
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

    m.addTo?.let { t ->
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

    m.newFor?.let { t ->
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

    m.list?.let { l ->
        ListMenuSheet(
            l.ui, bars,
            ListMenuActions(
                onDismiss = { m.list = null },
                onCopy = {
                    m.list = null
                    if (l.copyItems.isNotEmpty()) launch("copy") {
                        c.copyAsList(l.copyName, l.copyCover, l.copyItems)
                        snack("“${l.copyName}” Meiro listesi olarak kopyalandı")
                    }
                },
                onRename = { m.list = null; l.listId?.let { m.rename = it to l.copyName } },
                onDelete = {
                    m.list = null
                    l.listId?.let { id -> launch("delete") { c.deleteList(id); snack("“${l.copyName}” silindi") } }
                },
            ),
        )
    }

    m.rename?.let { (id, name) ->
        val save = {
            val n = name.trim()
            if (n.isNotEmpty()) {
                m.rename = null
                launch("rename") { c.renameList(id, n); snack("“$n” olarak yeniden adlandırıldı") }
            }
        }
        NewListSheet(name, { m.rename = id to it }, { m.rename = null }, save, bars, title = "Yeniden adlandır", action = "Kaydet", note = false)
    }
}

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
private fun share(ctx: Context, title: String, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, link)
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
