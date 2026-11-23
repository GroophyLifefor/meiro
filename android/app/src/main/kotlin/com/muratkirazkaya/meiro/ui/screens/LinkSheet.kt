package com.muratkirazkaya.meiro.ui.screens

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.Cover
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.IconButton
import com.muratkirazkaya.meiro.ui.components.MIcon
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.Spinner
import com.muratkirazkaya.meiro.ui.components.clickableNoIndication
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroText
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** One record the search found, as the mock's `.res` shows it: a cover, the name, "Kayıt · artist · 3:40 · 2019", "%97". */
data class LinkRowUi(val title: String, val detail: String, val percent: String, val cover: CoverSpec)

/**
 * What the link sheet shows: [title] ("MusicBrainz kaydı", or "MusicBrainz sanatçısı" for an artist, not in the mock),
 * the [note] above the field, the field's [query], and either the [rows] with the [selected] one, or (not in the mock)
 * [loading], or a [message] (nothing found, MusicBrainz did not answer).
 */
data class LinkSheetUi(
    val title: String,
    val note: String,
    val query: String,
    val rows: List<LinkRowUi> = emptyList(),
    val selected: Int = 0,
    val loading: Boolean = false,
    val message: String? = null,
)

class LinkSheetActions(
    val onClose: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onSelect: (Int) -> Unit = {},
    val onOther: () -> Unit = {},
    val onLink: () -> Unit = {},
)

// `.res .t b` 14/500, `.res .t span` and `.res .sim` 12, all on the button's 20px line with normal letter-spacing.
private val ResTitle = MeiroText(14f, 500, 20f, 0f)
private val ResSub = MeiroText(12f, 400, 20f, 0f)

/**
 * Mock screen `link` (`SHEET.link`): the tall sheet with "MusicBrainz kaydı" and the close button, the note
 * ("“title” · artist. Popülerlik ve benzerliğe göre sıralı, doğru olanı seç."), the search field holding the query, the
 * results (radio, cover, name, line, percent fit; the chosen one tinted), and at the bottom "Hiçbiri: "diğer"" and
 * "Bağla".
 */
@Composable
fun LinkSheet(ui: LinkSheetUi, bars: SystemBars, actions: LinkSheetActions = LinkSheetActions()) {
    val c = Meiro.colors
    MenuSheetFrame("link", bars, actions.onClose, tall = true) {
        SheetTitleHead("link", ui.title, actions.onClose)
        MText(
            ui.note, MeiroType.Note, c.onSurfaceVariant, "link.note",
            // `.note` with `margin-top:-6px`: it starts 6dp into the head's bottom padding.
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp).layoutUp(6),
        )
        LinkField(ui.query, actions.onQuery, actions.onSearch)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.loading -> Spinner("link.spinner", 32, 4f, c.outlineVariant, c.primary, Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
                ui.message != null -> MText(ui.message, MeiroType.Note, c.onSurfaceVariant, "link.message", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    itemsIndexed(ui.rows, key = { i, _ -> i }) { i, r -> ResultRow("link.res${i + 1}", r, i == ui.selected) { actions.onSelect(i) } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().imePadding().padding(start = 16.dp, end = 16.dp, top = 14.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
            SheetButton("link.other", "Hiçbiri: \"diğer\"", filled = false, onClick = actions.onOther)
            Spacer(Modifier.weight(1f))
            SheetButton("link.ok", "Bağla", filled = true, onClick = actions.onLink)
        }
    }
}

/** A negative top margin of [dp]: the content is placed [dp] higher and takes that much less room. */
private fun Modifier.layoutUp(dp: Int): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val up = dp.dp.roundToPx()
        layout(p.width, (p.height - up).coerceAtLeast(0)) { p.place(0, -up) }
    },
)

/** The mock's `.searchbar` with `margin:10px 16px`: the search glyph and the field; the keyboard's search key searches. */
@Composable
private fun LinkField(value: String, onValue: (String) -> Unit, onSearch: () -> Unit) {
    val c = Meiro.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).height(56.dp)
            .inv("link.field", bg = c.surfaceContainerHigh, radiusDp = 28f).clip(shape).background(c.surfaceContainerHigh)
            .clickableNoIndication { focus.requestFocus() }.padding(start = 16.dp, end = 4.dp),
        Arrangement.spacedBy(4.dp), Alignment.CenterVertically,
    ) {
        MIcon(MeiroIcons.Search, c.onSurfaceVariant, "link.field.icon")
        Box(Modifier.padding(start = 8.dp).weight(1f).height(22.dp).inv("link.field.input"), Alignment.CenterStart) {
            BasicTextField(
                value, onValue, Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = MeiroType.FieldInput.toStyle(c.onSurface), singleLine = true,
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSearch() }),
            )
        }
    }
}

/** The mock's `.res`: 10dp/16dp padding, the 20dp radio, the 44dp cover, the two lines and the percent, 12dp apart. */
@Composable
private fun ResultRow(p: String, r: LinkRowUi, selected: Boolean, onClick: () -> Unit) {
    val c = Meiro.colors
    val tint = c.primary.copy(alpha = 0.10f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).inv(p, bg = if (selected) tint else null)
            .then(if (selected) Modifier.background(tint) else Modifier)
            .clickableNoIndication(onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).inv("$p.radio", radiusDp = 10f).border(2.dp, if (selected) c.primary else c.outline, CircleShape),
            Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).inv("$p.dot", bg = c.primary, radiusDp = 5f).clip(CircleShape).background(c.primary))
        }
        Cover(r.cover, 8.dp, "$p.cover", Modifier.size(44.dp))
        Column(Modifier.weight(1f)) {
            MText(r.title, ResTitle, c.onSurface, "$p.title", Modifier.fillMaxWidth(), maxLines = 1)
            MText(r.detail, ResSub, c.onSurfaceVariant, "$p.detail", Modifier.fillMaxWidth(), maxLines = 1)
        }
        MText(r.percent, ResSub, c.onSurfaceVariant, "$p.sim", maxLines = 1)
    }
}

/** The mock's `.btn.outlined` and `.btn.filled` without a glyph: 40dp, 20dp radius, 20dp sides (CSS adds the 1dp border outside). */
@Composable
private fun SheetButton(key: String, label: String, filled: Boolean, onClick: () -> Unit) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(20.dp)
    val fg = if (filled) c.onPrimary else c.primary
    Box(
        Modifier.height(40.dp).inv(key, bg = if (filled) c.primary else null, radiusDp = 20f).clip(shape)
            .then(if (filled) Modifier.background(c.primary) else Modifier.border(1.dp, c.outline, shape))
            .clickableNoIndication(onClick).padding(horizontal = if (filled) 20.dp else 21.dp),
        Alignment.Center,
    ) { MText(label, MeiroType.ButtonLabel, fg, "$key.label", maxLines = 1) }
}
