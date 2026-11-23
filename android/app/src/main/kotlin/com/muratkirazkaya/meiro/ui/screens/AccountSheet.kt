package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** What the account sheet shows: the avatar's letter, the line under "Meiro hesabı", when the last sync was, and whether there is an account. */
data class AccountUi(val initial: String, val sub: String, val lastSync: String, val signedIn: Boolean)

class AccountActions(
    val onDismiss: () -> Unit = {},
    val onHistory: () -> Unit = {},
    val onStats: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onSync: () -> Unit = {},
    val onSignOut: () -> Unit = {},
)

/**
 * The mock's `SHEET.account` (the avatar of every tab's top bar): the head (avatar, "Meiro hesabı", the address and
 * the sync state), "Dinleme geçmişi", "Özet", "Ayarlar", "Şimdi eşitle" with when the last sync was, a line and
 * "Çıkış yap" ("Giriş yap" without an account: back to the sign-in screen).
 */
@Composable
fun AccountSheet(ui: AccountUi, bars: SystemBars, actions: AccountActions = AccountActions()) {
    val c = Meiro.colors
    MenuSheetFrame("account", bars, actions.onDismiss, scrimKey = true) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            Arrangement.spacedBy(12.dp), Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).inv("account.avatar", bg = c.primaryContainer, radiusDp = 24f).clip(CircleShape).background(c.primaryContainer),
                Alignment.Center,
            // The mock's `.shead span` rule reaches the avatar too: its letter is on-surface-variant (not the top bar's on-primary-container).
            ) { MText(ui.initial, MeiroType.AvatarLarge, c.onSurfaceVariant, "account.avatar.label") }
            Column(Modifier.weight(1f)) {
                MText("Meiro hesabı", MeiroType.SheetHeadTitle, c.onSurface, "account.name", Modifier.fillMaxWidth(), maxLines = 1)
                MText(ui.sub, MeiroType.SheetHeadSub, c.onSurfaceVariant, "account.sub", maxLines = 1)
            }
        }
        SheetDivider("account.div1")
        MenuItem("account.history", MeiroIcons.History, "Dinleme geçmişi", actions.onHistory)
        MenuItem("account.stats", MeiroIcons.Stats, "Özet", actions.onStats)
        MenuItem("account.settings", MeiroIcons.Settings, "Ayarlar", actions.onSettings)
        MenuItem("account.sync", MeiroIcons.Sync, "Şimdi eşitle", actions.onSync) {
            MText(ui.lastSync, MeiroType.MenuRight, c.onSurfaceVariant, "account.sync.state", maxLines = 1)
        }
        SheetDivider("account.div2")
        MenuItem("account.signout", MeiroIcons.Back, if (ui.signedIn) "Çıkış yap" else "Giriş yap", actions.onSignOut)
    }
}

class HistoryMenuActions(
    val onDismiss: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onStats: () -> Unit = {},
    val onClear: () -> Unit = {},
)

/**
 * The mock's `SHEET.histmenu` (the history's ⋮): "Kaydı duraklat" (while paused "Kaydı sürdür"), "Özet'i aç" and
 * "Geçmişi temizle" ("bütün cihazlarda" with an account, "bu cihazda" without), which asks first ([ClearHistorySheet]).
 */
@Composable
fun HistoryMenuSheet(paused: Boolean, signedIn: Boolean, bars: SystemBars, actions: HistoryMenuActions = HistoryMenuActions()) {
    val c = Meiro.colors
    MenuSheetFrame("histmenu", bars, actions.onDismiss, scrimKey = true) {
        SheetTitleHead("histmenu", "Dinleme geçmişi")
        SheetDivider("histmenu.div")
        if (paused) MenuItem("histmenu.pause", MeiroIcons.Play, "Kaydı sürdür", actions.onPause)
        else MenuItem("histmenu.pause", MeiroIcons.Pause, "Kaydı duraklat", actions.onPause)
        MenuItem("histmenu.stats", MeiroIcons.Stats, "Özet'i aç", actions.onStats)
        MenuItem("histmenu.clear", MeiroIcons.Del, "Geçmişi temizle", actions.onClear, warn = true) {
            MText(if (signedIn) "bütün cihazlarda" else "bu cihazda", MeiroType.MenuRight, c.onSurfaceVariant, "histmenu.clear.where", maxLines = 1)
        }
    }
}

/** The mock's `SHEET.clearhist`: clearing the history cannot be undone, so it asks first (the desktop asks twice). */
@Composable
fun ClearHistorySheet(signedIn: Boolean, bars: SystemBars, onCancel: () -> Unit, onClear: () -> Unit) = ConfirmSheet(
    "clearhist", "Geçmişi temizle",
    if (signedIn) "Bütün cihazlardaki çalmalar Meiro hesabından silinir, bu geri alınamaz." else "Bu cihazdaki çalmalar silinir, bu geri alınamaz.",
    "Temizle", bars, onCancel, onClear,
)
