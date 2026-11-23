package com.muratkirazkaya.meiro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/**
 * The mock's `.searchbar`: 56dp, 28dp radius, surface-container-high, the search glyph, the field (16px text, the
 * placeholder "Şarkı, sanatçı, albüm, liste") and, while the field holds text, the clear button. Tapping the bar
 * focuses the field; the keyboard's search key submits.
 */
@Composable
fun SearchField(value: String, onValue: (String) -> Unit, onSubmit: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val c = Meiro.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp)
            .inv("search", bg = c.surfaceContainerHigh, radiusDp = 28f).clip(shape).background(c.surfaceContainerHigh)
            .clickableNoIndication { focus.requestFocus() }.padding(start = 16.dp, end = 4.dp),
        Arrangement.spacedBy(4.dp), Alignment.CenterVertically,
    ) {
        MIcon(MeiroIcons.Search, c.onSurfaceVariant, "search.icon")
        Box(Modifier.padding(start = 8.dp).weight(1f).height(22.dp).inv("search.input"), Alignment.CenterStart) {
            if (value.isEmpty()) {
                BasicText(
                    "Şarkı, sanatçı, albüm, liste", style = MeiroType.FieldInput.toStyle(c.onSurfaceVariant),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value, onValue, Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = MeiroType.FieldInput.toStyle(c.onSurface), singleLine = true,
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSubmit() }),
            )
        }
        if (value.isNotEmpty()) IconButton("search.clearBtn", "search.clear.icon", MeiroIcons.Close, c.onSurfaceVariant, onClear)
    }
}

/** The mock's `.h-sec`: a 20/26 section title and, optionally, a 13px primary text button at the end ("Temizle"). */
@Composable
fun SectionHead(title: String, key: String, action: String? = null, actionKey: String = "", onAction: () -> Unit = {}) {
    val c = Meiro.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
        Arrangement.SpaceBetween, Alignment.CenterVertically,
    ) {
        MText(title, MeiroType.SectionTitle, c.onSurface, key, Modifier.weight(1f, fill = false), maxLines = 1)
        if (action != null) {
            Box(Modifier.inv(actionKey).clickableNoIndication(onAction).padding(horizontal = 6.dp, vertical = 1.dp)) {
                MText(action, MeiroType.SectionAction, c.primary, "$actionKey.label", maxLines = 1)
            }
        }
    }
}

/** The mock's `.recent`: 52dp, the history glyph, the query, and a button that takes the query out of the list. */
@Composable
fun RecentRow(p: String, query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val c = Meiro.colors
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickableNoIndication(onClick).padding(start = 20.dp, end = 8.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) {
        MIcon(MeiroIcons.History, c.onSurfaceVariant, "$p.icon", size = 22)
        MText(query, MeiroType.Body, c.onSurface, "$p.label", Modifier.weight(1f), maxLines = 1)
        IconButton("$p.removeBtn", "$p.remove.icon", MeiroIcons.Close, c.onSurfaceVariant, onRemove, iconSize = 22)
    }
}

/**
 * The mock's `.chips .chip`: 32dp, 8dp radius, 14dp side padding inside a 1dp border, 13px label; unselected it has a 1dp outline-variant
 * border, selected the secondary container. [leading] is the check of a selected filter or a provider's logo.
 */
@Composable
fun SearchChipBox(key: String, label: String, selected: Boolean, onClick: () -> Unit, leading: @Composable () -> Unit = {}) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.height(32.dp).inv(key, bg = if (selected) c.secondaryContainer else null, radiusDp = 8f).clip(shape)
            .then(if (selected) Modifier.background(c.secondaryContainer) else Modifier.border(1.dp, c.outlineVariant, shape))
            // 14dp padding plus the 1dp border, which CSS adds outside the padding (also on a selected chip, transparent).
            .clickableNoIndication(onClick).padding(horizontal = 15.dp),
        Arrangement.spacedBy(4.dp), Alignment.CenterVertically,
    ) {
        leading()
        MText(label, MeiroType.ChipLabel, if (selected) c.onSecondaryContainer else c.onSurfaceVariant, "$key.label", maxLines = 1)
    }
}

/** An artist in the results: [logo] and the MusicBrainz link button are optional, as the mock's two artist rows show. */
data class ArtistRowData(val key: String, val name: String, val meta: String, val cover: CoverSpec, val logo: Provider? = null, val link: Boolean = false)

/** The mock's artist row: a round 48dp cover, the name, the meta line, and the link button for an unlinked artist ([onLink]: the link sheet). */
@Composable
fun ArtistRow(p: String, row: ArtistRowData, onClick: () -> Unit, onLink: () -> Unit = {}) {
    val c = Meiro.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickableNoIndication(onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        Cover(row.cover, 24.dp, "$p.cover", Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            MText(row.name, MeiroType.ListTitle, c.onSurface, "$p.title", Modifier.fillMaxWidth(), maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (row.logo != null) SourceLogo("$p.logo", row.logo, false)
                MText(row.meta, MeiroType.ListMeta, c.onSurfaceVariant, "$p.meta", Modifier.weight(1f, fill = false), maxLines = 1)
            }
        }
        if (row.link) LinkButton(p, false, onLink)
    }
}

/** An album (or other collection) on a shelf: [meta] is "Artist · year". */
data class TileData(val key: String, val title: String, val meta: String, val cover: CoverSpec, val logo: Provider = Provider.Ytm)

/** The mock's `.tile`: 140dp art (14dp radius) with the provider badge, a two-line title and a one-line meta. */
@Composable
fun AlbumTile(p: String, tile: TileData, onClick: () -> Unit) {
    val c = Meiro.colors
    Column(Modifier.width(140.dp).clickableNoIndication(onClick)) {
        Cover(tile.cover, 14.dp, null, Modifier.size(140.dp)) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp).size(22.dp)
                    .inv("$p.badge", bg = BadgeColor, radiusDp = 11f).clip(CircleShape).background(BadgeColor),
                Alignment.Center,
            ) { SourceLogo("$p.logo", tile.logo, false) }
        }
        Spacer(Modifier.height(8.dp))
        MText(tile.title, MeiroType.TileTitle, c.onSurface, "$p.title", Modifier.fillMaxWidth(), maxLines = 2, ellipsis = true)
        MText(tile.meta, MeiroType.TileMeta, c.onSurfaceVariant, "$p.meta", Modifier.fillMaxWidth(), maxLines = 1)
    }
}

private val BadgeColor = Color.Black.copy(alpha = 0.55f)
