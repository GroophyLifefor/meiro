package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import com.muratkirazkaya.meiro.ui.components.LocalSheetProgress
import com.muratkirazkaya.meiro.ui.components.sheetScrim
import com.muratkirazkaya.meiro.ui.components.sheetSlide
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.clickableNoIndication
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType
import androidx.compose.runtime.remember

/**
 * The mock's `SHEET.newlist`, opened by "Yeni liste" (FAB and the empty state's button): scrim, bottom sheet (28dp top
 * radius, surface-container-low) with the handle, the "Yeni liste" title, the focused "Ad" field, the note and the
 * "İptal" / "Oluştur" buttons. The mock's field holds the sample name "Yaz akşamları"; here it starts empty.
 */
@Composable
fun NewListSheet(
    name: String,
    onName: (String) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
    bars: SystemBars,
    /** The rename sheet (not in the mock) reuses this one: its own title and button, no note. */
    title: String = "Yeni liste",
    action: String = "Oluştur",
    note: Boolean = true,
) {
    val c = Meiro.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val prog = LocalSheetProgress.current
    Box(Modifier.fillMaxSize().sheetScrim(prog).background(c.scrim).clickableNoIndication(onCancel)) {
        Column(
            Modifier.align(Alignment.BottomCenter).imePadding().fillMaxWidth().sheetSlide(prog)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surfaceContainerLow)
                .clickableNoIndication {}
                .padding(bottom = maxOf(20.dp, bars.bottom)),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp, bottom = 8.dp).size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.outline))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                MText(title, MeiroType.SheetTitle, c.onSurface, "sheet.title", Modifier.weight(1f))
            }
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)) {
                Box(
                    Modifier.fillMaxWidth().border(BorderStroke(2.dp, c.primary), RoundedCornerShape(6.dp))
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
                ) {
                    BasicTextField(
                        name, onName, Modifier.fillMaxWidth().focusRequester(focus),
                        textStyle = MeiroType.FieldInput.toStyle(c.onSurface),
                        singleLine = true,
                        cursorBrush = SolidColor(c.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onCreate() }),
                    )
                }
                Box(Modifier.offset(x = 12.dp, y = (-7).dp).background(c.surfaceContainerLow).padding(horizontal = 4.dp)) {
                    MText("Ad", MeiroType.FieldLabel, c.primary, "sheet.label")
                }
            }
            if (note) MText(
                "Meiro hesabında tutulur. İçine YouTube Music ve SoundCloud şarkıları birlikte eklenebilir.", MeiroType.Note, c.onSurfaceVariant,
                "sheet.note", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).clickableNoIndication(onCancel).padding(horizontal = 12.dp), Alignment.Center) {
                    MText("İptal", MeiroType.ButtonLabel, c.primary, "sheet.cancel")
                }
                Box(
                    Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(c.primary).clickableNoIndication(onCreate).padding(horizontal = 20.dp),
                    Alignment.Center,
                ) { MText(action, MeiroType.ButtonLabel, c.onPrimary, "sheet.create") }
            }
        }
    }
}
