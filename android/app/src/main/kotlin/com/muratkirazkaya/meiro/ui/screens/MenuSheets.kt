package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.Cover
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.IconButton
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.MIcon
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SourceLogo
import com.muratkirazkaya.meiro.ui.components.clickableNoIndication
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.icons.MeiroLogos
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/**
 * What the track menu (`SHEET.menu`) shows for one track: its cover, title and artist, the MusicBrainz state on the
 * right of "MusicBrainz'e bağla" ("eşleşti", "diğer" or "unknown", the mock's words), the providers it is held by (the
 * logos right of "Kaynağı değiştir"), whether it is downloaded ("İndirildi · kaldır" in place of "İndir"), whether it
 * names an album ("Albüme git" is shown only then, as in the mock) and whether the menu was opened from a row of one of
 * the user's lists ("Listeden çıkar", which the mock does not have).
 */
data class TrackMenuUi(
    val title: String,
    val artist: String,
    val cover: CoverSpec,
    val link: String = "unknown",
    val sources: List<Provider> = listOf(Provider.Ytm),
    val downloaded: Boolean = false,
    val album: Boolean = false,
    val inList: Boolean = false,
)

class TrackMenuActions(
    val onDismiss: () -> Unit = {},
    val onLike: () -> Unit = {},
    val onPlayNext: () -> Unit = {},
    val onQueue: () -> Unit = {},
    val onAddTo: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onLink: () -> Unit = {},
    val onSource: () -> Unit = {},
    val onArtist: () -> Unit = {},
    val onAlbum: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onNoRecommend: () -> Unit = {},
    val onRemove: () -> Unit = {},
)

/**
 * The mock's bottom sheet frame (`.scrim` + `.sheet`): the scrim (a tap closes), the sheet in surface-container-low with
 * 28dp top corners, at most 88% of the screen high ([tall]: always 88%, the mock's `.sheet.tall`), the handle, and
 * [content]. [p] prefixes the inventory keys.
 */
@Composable
fun MenuSheetFrame(p: String, bars: SystemBars, onDismiss: () -> Unit, tall: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = Meiro.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(c.scrim).clickableNoIndication(onDismiss))
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .then(if (tall) Modifier.height(maxHeight * 0.88f) else Modifier.heightIn(max = maxHeight * 0.88f))
                .inv("$p.sheet", bg = c.surfaceContainerLow, radiusDp = 28f).clip(shape).background(c.surfaceContainerLow)
                .clickableNoIndication {}.padding(bottom = maxOf(20.dp, bars.bottom)),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 8.dp).size(width = 32.dp, height = 4.dp)
                    .inv("$p.handle", bg = c.outline, radiusDp = 2f).clip(RoundedCornerShape(2.dp)).background(c.outline),
            )
            content()
        }
    }
}

/** The mock's `.sheet .div`: a 1dp outline-variant line with 4dp above and below. */
@Composable
fun SheetDivider(key: String) {
    val c = Meiro.colors
    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).inv(key, bg = c.outlineVariant).background(c.outlineVariant))
}

/** The mock's `.shead` with an `h3` title, and the close button when [onClose] is set. */
@Composable
fun SheetTitleHead(p: String, title: String, onClose: (() -> Unit)? = null) {
    val c = Meiro.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        MText(title, MeiroType.SheetTitle, c.onSurface, "$p.title", Modifier.weight(1f))
        if (onClose != null) IconButton("$p.closeBtn", "$p.close.icon", MeiroIcons.Close, c.onSurfaceVariant, onClose)
    }
}

/**
 * The mock's `.mi`: 52dp, 20dp sides, the 24dp glyph and the 15px label 16dp apart, an optional right part (`.r`,
 * pushed to the end); [warn] draws it in the error colour (`.mi.warn`).
 */
@Composable
fun MenuItem(
    key: String, icon: ImageVector, label: String, onClick: () -> Unit, warn: Boolean = false,
    right: (@Composable () -> Unit)? = null,
) {
    val c = Meiro.colors
    val fg = if (warn) c.error else c.onSurface
    Row(
        Modifier.fillMaxWidth().height(52.dp).inv(key).clickableNoIndication(onClick).padding(horizontal = 20.dp),
        Arrangement.spacedBy(16.dp), Alignment.CenterVertically,
    ) {
        MIcon(icon, if (warn) c.error else c.onSurfaceVariant, "$key.icon")
        MText(label, MeiroType.MenuItem, fg, "$key.label", Modifier.weight(1f), maxLines = 1)
        right?.invoke()
    }
}

/**
 * The track menu (`SHEET.menu`), the same for every track ⋮ (rows of every list, the full player's ⋮): the head (cover,
 * title, artist, the heart), then "Sıradaki olarak çal", "Kuyruğa ekle", "Listeye ekle", "İndir", "MusicBrainz'e bağla",
 * "Kaynağı değiştir"; a line; "Sanatçıya git", "Albüme git" (when the track names an album), "Bağlantıyı paylaş",
 * "Bunu önerme". Opened from a row of one of the user's lists it also offers "Listeden çıkar" last (not in the mock).
 */
@Composable
fun TrackMenuSheet(ui: TrackMenuUi, bars: SystemBars, actions: TrackMenuActions = TrackMenuActions()) {
    val c = Meiro.colors
    MenuSheetFrame("menu", bars, actions.onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
        ) {
            Cover(ui.cover, 8.dp, "menu.head.cover", Modifier.size(48.dp))
            Column(Modifier.weight(1f)) {
                MText(ui.title, MeiroType.SheetHeadTitle, c.onSurface, "menu.head.title", Modifier.fillMaxWidth(), maxLines = 1)
                MText(ui.artist, MeiroType.SheetHeadSub, c.onSurfaceVariant, "menu.head.artist")
            }
            IconButton("menu.likeBtn", "menu.like.icon", MeiroIcons.Heart, c.onSurfaceVariant, actions.onLike)
        }
        SheetDivider("menu.div1")
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            MenuItem("menu.next", MeiroIcons.Playnext, "Sıradaki olarak çal", actions.onPlayNext)
            MenuItem("menu.queue", MeiroIcons.Queue, "Kuyruğa ekle", actions.onQueue)
            MenuItem("menu.addto", MeiroIcons.Add, "Listeye ekle", actions.onAddTo)
            MenuItem(
                "menu.download", if (ui.downloaded) MeiroIcons.Done else MeiroIcons.Download,
                if (ui.downloaded) "İndirildi · kaldır" else "İndir", actions.onDownload,
            )
            MenuItem("menu.link", MeiroIcons.Link, "MusicBrainz'e bağla", actions.onLink) {
                MText(ui.link, MeiroType.MenuRight, c.onSurfaceVariant, "menu.link.state", maxLines = 1)
            }
            MenuItem("menu.source", MeiroIcons.Sync, "Kaynağı değiştir", actions.onSource) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (s in ui.sources) SourceLogo24("menu.source.${s.name.lowercase()}", s)
                }
            }
            SheetDivider("menu.div2")
            MenuItem("menu.artist", MeiroIcons.Person, "Sanatçıya git", actions.onArtist)
            if (ui.album) MenuItem("menu.album", MeiroIcons.Album, "Albüme git", actions.onAlbum)
            MenuItem("menu.share", MeiroIcons.Share, "Bağlantıyı paylaş", actions.onShare)
            MenuItem("menu.noRec", MeiroIcons.Close, "Bunu önerme", actions.onNoRecommend, warn = true)
            if (ui.inList) MenuItem("menu.remove", MeiroIcons.Del, "Listeden çıkar", actions.onRemove, warn = true)
        }
    }
}

/** A source logo at the `.mi svg` size (24dp): the mock's rule for the menu's glyphs reaches the logos too. */
@Composable
private fun SourceLogo24(key: String, source: Provider) {
    val icon = when (source) { Provider.Ytm -> MeiroLogos.Ytm; Provider.Sc -> MeiroLogos.Sc; Provider.Meiro -> MeiroLogos.Meiro }
    androidx.compose.foundation.Image(icon, null, Modifier.size(24.dp).inv(key))
}

/**
 * The list menu (`SHEET.listmenu`) for a Meiro list or an album: "Liste", a line, "Meiro listesi olarak kopyala",
 * "Hepsini indir", "Yeniden adlandır", "Hepsini MusicBrainz'e bağla" (with "N unknown" on the right), "Listeyi sil".
 * An album (not the user's own list) cannot be renamed or deleted, so [own] false leaves those two out.
 */
data class ListMenuUi(val unknown: Int, val own: Boolean)

class ListMenuActions(
    val onDismiss: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onLinkAll: () -> Unit = {},
    val onDelete: () -> Unit = {},
)

@Composable
fun ListMenuSheet(ui: ListMenuUi, bars: SystemBars, actions: ListMenuActions = ListMenuActions()) {
    val c = Meiro.colors
    MenuSheetFrame("lmenu", bars, actions.onDismiss) {
        SheetTitleHead("lmenu", "Liste")
        SheetDivider("lmenu.div")
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            MenuItem("lmenu.copy", MeiroIcons.Copy, "Meiro listesi olarak kopyala", actions.onCopy)
            MenuItem("lmenu.download", MeiroIcons.Download, "Hepsini indir", actions.onDownload)
            if (ui.own) MenuItem("lmenu.rename", MeiroIcons.Edit, "Yeniden adlandır", actions.onRename)
            MenuItem("lmenu.link", MeiroIcons.Link, "Hepsini MusicBrainz'e bağla", actions.onLinkAll) {
                MText("${ui.unknown} unknown", MeiroType.MenuRight, c.onSurfaceVariant, "lmenu.link.state", maxLines = 1)
            }
            if (ui.own) MenuItem("lmenu.delete", MeiroIcons.Del, "Listeyi sil", actions.onDelete, warn = true)
        }
    }
}

/**
 * The add-to sheet (`SHEET.addto`, a tall sheet): "Listeye ekle" with the close button, "Yeni liste", a line, and the
 * user's lists (cover, name, the Meiro logo and "N parça"). A tap on a list adds the track to it.
 */
@Composable
fun AddToSheet(
    lists: List<ListRowData>, bars: SystemBars, onDismiss: () -> Unit, onNew: () -> Unit, onPick: (String) -> Unit,
) {
    val c = Meiro.colors
    MenuSheetFrame("addto", bars, onDismiss, tall = true) {
        SheetTitleHead("addto", "Listeye ekle", onDismiss)
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item("new") {
                MenuItem("addto.new", MeiroIcons.Add, "Yeni liste", onNew)
                SheetDivider("addto.div")
            }
            items(lists.size, key = { lists[it].id }) { i ->
                val l = lists[i]
                val p = "addto.row${i + 1}"
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickableNoIndication { onPick(l.id) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
                ) {
                    Cover(l.cover, 8.dp, "$p.cover", Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        MText(l.name, MeiroType.ListTitle, c.onSurface, "$p.title", Modifier.fillMaxWidth(), maxLines = 1)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            SourceLogo("$p.logo", l.logo, false)
                            MText(l.meta, MeiroType.ListMeta, c.onSurfaceVariant, "$p.meta", Modifier.weight(1f, fill = false), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
