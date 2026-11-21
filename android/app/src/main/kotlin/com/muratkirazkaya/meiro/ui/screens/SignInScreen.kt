package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.Snack
import com.muratkirazkaya.meiro.ui.components.clickableNoIndication
import com.muratkirazkaya.meiro.ui.inv.InvText
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.inv.invOverflow
import com.muratkirazkaya.meiro.ui.inv.invText
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType
import kotlinx.coroutines.delay

enum class SignInField { Server, Email, Password }

/**
 * Mock screen `signin` (`V.signin`, `.welcome`): logo, title, intro, three outlined fields (server, email,
 * password), "Giriş yap", "Hesapsız devam et" and the note at the bottom. No bottom bar, no mini player.
 *
 * [focused] draws the focused field the way the mock does (2dp primary border, label in primary). A message
 * shows in the mock's snack, 160dp above the bottom edge, and closes itself after 2.6 s like the mock's.
 */
@Composable
fun SignInScreen(
    server: String, email: String, password: String,
    onServer: (String) -> Unit, onEmail: (String) -> Unit, onPassword: (String) -> Unit,
    focused: SignInField?, onFocus: (SignInField, Boolean) -> Unit,
    bars: SystemBars,
    onSubmit: () -> Unit, onSkip: () -> Unit,
    message: String? = null, onMessageDismiss: () -> Unit = {},
) {
    val c = Meiro.colors
    Box(Modifier.fillMaxSize().background(c.surface)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(bars.top))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().imePadding()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                        .padding(start = 24.dp, end = 24.dp, top = 48.dp, bottom = bars.bottom + 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Box(
                            Modifier.size(72.dp).inv("signin.logo", bg = c.primary, radiusDp = 22f)
                                .clip(RoundedCornerShape(22.dp)).background(c.primary),
                            Alignment.Center,
                        ) { MText("M", MeiroType.SignInLogo, c.onPrimary, "signin.logo.label") }
                        Spacer(Modifier.height(24.dp))
                        MText("Meiro'ya hoş geldin", MeiroType.SignInTitle, c.onSurface, "signin.title", Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        MText(
                            "Listelerin, indirilenlerin ve dinleme özetin Meiro hesabında. Masaüstüyle aynı hesap.",
                            MeiroType.Body, c.onSurfaceVariant, "signin.intro", Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(26.dp))
                        OutlinedField(
                            "signin.server", "Sunucu", server, onServer, focused == SignInField.Server,
                            { onFocus(SignInField.Server, it) },
                            KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        )
                        OutlinedField(
                            "signin.email", "E-posta", email, onEmail, focused == SignInField.Email,
                            { onFocus(SignInField.Email, it) },
                            KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        )
                        OutlinedField(
                            "signin.password", "Şifre", password, onPassword, focused == SignInField.Password,
                            { onFocus(SignInField.Password, it) },
                            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            PasswordVisualTransformation(), onDone = onSubmit,
                        )
                        Box(
                            Modifier.fillMaxWidth().height(48.dp).inv("signin.submit", bg = c.primary, radiusDp = 24f)
                                .background(c.primary, RoundedCornerShape(24.dp)).clickableNoIndication(onSubmit),
                            Alignment.Center,
                        ) { MText("Giriş yap", MeiroType.ButtonLabel, c.onPrimary, "signin.submit.label") }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.fillMaxWidth().height(48.dp).inv("signin.skip", radiusDp = 24f)
                                .clip(RoundedCornerShape(24.dp)).clickableNoIndication(onSkip),
                            Alignment.Center,
                        ) { MText("Hesapsız devam et", MeiroType.ButtonLabel, c.primary, "signin.skip.label") }
                    }
                    Box(Modifier.fillMaxWidth().inv("signin.note")) {
                        MText(
                            "Hesapsız: arama ve çalma çalışır, listeler yalnız bu telefonda kalır.",
                            MeiroType.FieldLabel, c.onSurfaceVariant, "signin.note.text",
                            Modifier.fillMaxWidth().padding(top = 24.dp),
                        )
                    }
                }
            }
        }
        if (message != null) {
            LaunchedEffect(message) { delay(2600); onMessageDismiss() }
            Snack(message, onMessageDismiss, Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 160.dp))
        }
    }
}

/**
 * The mock's `.welcome .field`: 1dp outline border (2dp primary when focused, and the box grows with it, as
 * in CSS), 6dp radius, padding 10/14/8, a 16px input, and the label sitting on the top border (-9px, 10px in,
 * 4px side padding, surface background). The label is a sibling of the border box so it may lie outside it.
 */
@Composable
private fun OutlinedField(
    key: String, label: String, value: String, onChange: (String) -> Unit, focused: Boolean,
    onFocusChange: (Boolean) -> Unit, keyboard: KeyboardOptions,
    transform: VisualTransformation = VisualTransformation.None,
    onDone: () -> Unit = {},
) {
    val c = Meiro.colors
    val bw = if (focused) 2 else 1
    var overflow by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp)) {
        Box(
            Modifier.fillMaxWidth().inv("$key.field", radiusDp = 6f)
                .border(BorderStroke(bw.dp, if (focused) c.primary else c.outline), RoundedCornerShape(6.dp))
                .padding(start = (bw + 14).dp, end = (bw + 14).dp, top = (bw + 10).dp, bottom = (bw + 8).dp),
        ) {
            BasicTextField(
                value, onChange,
                Modifier.fillMaxWidth().height(22.dp).inv("$key.input").semantics {
                    invText = InvText(MeiroType.FieldInput, c.onSurface)
                    invOverflow = overflow
                }.onFocusChanged { onFocusChange(it.isFocused) },
                textStyle = MeiroType.FieldInput.toStyle(c.onSurface),
                singleLine = true,
                cursorBrush = SolidColor(c.primary),
                keyboardOptions = keyboard,
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                visualTransformation = transform,
                onTextLayout = { overflow = false },
                decorationBox = { inner -> Box(Modifier.padding(horizontal = 2.dp, vertical = 1.dp)) { inner() } },
            )
        }
        Box(
            Modifier.offset(x = (bw + 10).dp, y = (bw - 9).dp).inv("$key.label", bg = c.surface)
                .background(c.surface).padding(horizontal = 4.dp),
        ) {
            MText(label, MeiroType.FieldLabel, if (focused) c.primary else c.onSurfaceVariant, "$key.label.text")
        }
    }
}
