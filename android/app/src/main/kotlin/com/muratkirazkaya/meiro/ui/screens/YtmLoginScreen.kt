package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.MIcon
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

const val YTM_LOGIN_NOTE = "Giriş Google'ın kendi sayfasında olur. Meiro şifreni görmez, yalnızca oturum çerezini bu telefonda saklar."
const val YTM_LOGIN_FOOT = "Giriş isteğe bağlıdır: kütüphane, beğeniler ve YouTube Music listeleri için gerekir."

/** The page frame's own colours (`.webview`): a white page with Chrome's address strip, the same in both themes. */
private val WebWhite = Color(0xFFFFFFFF)
private val UrlStrip = Color(0xFFF1F3F4)
private val UrlText = Color(0xFF5F6368)
private val UrlLock = Color(0xFF188038)

/**
 * Mock screen `ytmlogin` ("YouTube Music girişi", M6f): the note, the frame of Google's own sign-in page (`.webview`:
 * 12dp margins, 16dp corners, a 1dp outline-variant border, white) with its address strip (lock and the page's host)
 * and the page itself, 296dp tall as the mock draws it, then the note under it. [page] is the page: a WebView in the
 * app, nothing in the render tests (Google's page is not the app's; `mock-keys/ytmlogin.json` marks its elements web).
 */
@Composable
fun YtmLoginScreen(host: String, onBack: () -> Unit, page: @Composable ColumnScope.() -> Unit) {
    val c = Meiro.colors
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxSize()) {
        TopBar("YouTube Music girişi", onBack = onBack, avatar = false)
        MText(YTM_LOGIN_NOTE, MeiroType.Note, c.onSurfaceVariant, "note", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        Column(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(296.dp).inv("web", bg = WebWhite, radiusDp = 16f)
                .clip(shape).background(WebWhite).border(1.dp, c.outlineVariant, shape).padding(1.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).inv("web.url", bg = UrlStrip).background(UrlStrip).padding(horizontal = 12.dp),
                Arrangement.spacedBy(8.dp), Alignment.CenterVertically,
            ) {
                MIcon(MeiroIcons.Lock, UrlLock, "web.url.icon", size = 14)
                MText(host, MeiroType.WebUrl, UrlText, "web.url.label", Modifier.weight(1f), maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().weight(1f)) { Column(Modifier.fillMaxSize()) { page() } }
        }
        MText(YTM_LOGIN_FOOT, MeiroType.Note, c.onSurfaceVariant, "foot", Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp))
    }
}
