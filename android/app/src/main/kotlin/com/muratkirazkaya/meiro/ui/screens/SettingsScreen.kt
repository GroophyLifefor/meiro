package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.components.MIcon
import com.muratkirazkaya.meiro.ui.components.MText
import com.muratkirazkaya.meiro.ui.components.MeiroSlider
import com.muratkirazkaya.meiro.ui.components.MeiroSwitch
import com.muratkirazkaya.meiro.ui.components.Segmented
import com.muratkirazkaya.meiro.ui.components.TextButton
import com.muratkirazkaya.meiro.ui.components.TopBar
import com.muratkirazkaya.meiro.ui.components.clickableNoIndication
import com.muratkirazkaya.meiro.ui.icons.MeiroIcons
import com.muratkirazkaya.meiro.ui.icons.MeiroLogos
import com.muratkirazkaya.meiro.ui.inv.inv
import com.muratkirazkaya.meiro.ui.theme.Meiro
import com.muratkirazkaya.meiro.ui.theme.MeiroType

/** The theme the app uses: the system's, or always light or dark (desktop `settings.Theme`). */
enum class ThemeChoice { System, Light, Dark }

/** Mock screen `settings`: every row's lines and state. Rows whose feature the app does not have show the mock's text and do nothing. */
data class SettingsUi(
    val accountSub: String,
    /** "Çıkış" signed in, "Giriş" without an account. */
    val accountButton: String,
    val server: String,
    val ytmSub: String = "Giriş yok. Arama ve çalma hesapsız çalışır.",
    val ytmButton: String = "Giriş",
    val lastfmSub: String = "Bağlı değil",
    val lastfmButton: String = "Bağla",
    val theme: ThemeChoice = ThemeChoice.System,
    val wallpaper: Boolean = false,
    val swatch: Int = 0,
    val coverColor: Boolean = true,
    val loudness: Boolean = false,
    val sourceOrder: String = "İki sağlayıcıda olan şarkı önce: YouTube Music, sonra SoundCloud. Önce indirilen dosya çalar.",
    val quality: String = "Düşük (≈ 70 kbps). Wi-Fi'de en yüksek.",
    val recommend: Boolean = true,
    /** "Yeni sanatçılar": the share of the picks by artists never played, 0..1. */
    val explore: Float = 0.3f,
    val dailyMix: String = "",
    val dailyMixBusy: Boolean = false,
    val wifiOnly: Boolean = true,
    val ytCache: String = "",
    val scCache: String = "",
    val coverCache: String = "",
    val mbCache: String = "",
    val history: String = "",
    val version: String = "Meiro",
    val versionSub: String = "Android · APK",
    val ytdlp: String = "",
    val ytdlpBusy: Boolean = false,
) {
    val exploreSub: String get() = "Önerilerin %${Math.round(explore * 100)}'${percentSuffix(Math.round(explore * 100))} hiç dinlemediğin sanatçılardan"

    companion object {
        /** The Turkish suffix after a percentage read out ("%30'u", "%40'ı", "%25'i", "%100'ü"). */
        fun percentSuffix(n: Int): String {
            val last = when {
                n == 0 -> "sıfır"
                n % 10 != 0 -> listOf("", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz")[n % 10]
                n % 100 != 0 -> listOf("", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan")[(n / 10) % 10]
                else -> "yüz"
            }
            val v = last.last { it in "aeıioöuü" }
            return when (v) { 'a', 'ı' -> "ı"; 'e', 'i' -> "i"; 'o', 'u' -> "u"; else -> "ü" }
        }
    }
}

class SettingsActions(
    val onBack: () -> Unit = {},
    val onAccount: () -> Unit = {},
    val onServer: () -> Unit = {},
    val onYtm: () -> Unit = {},
    /** M6f: a tap anywhere on the YouTube Music row (signed out it opens the sign-in page, as the mock's row does); null: none. */
    val onYtmRow: (() -> Unit)? = null,
    val onLastfm: () -> Unit = {},
    val onTheme: (ThemeChoice) -> Unit = {},
    val onWallpaper: () -> Unit = {},
    val onSwatch: (Int) -> Unit = {},
    val onCoverColor: () -> Unit = {},
    val onLoudness: () -> Unit = {},
    val onSourceOrder: () -> Unit = {},
    val onQuality: () -> Unit = {},
    val onRecommend: () -> Unit = {},
    val onExplore: (Float) -> Unit = {},
    val onExploreDone: () -> Unit = {},
    val onDailyMix: () -> Unit = {},
    val onWifiOnly: () -> Unit = {},
    val onClearYt: () -> Unit = {},
    val onClearSc: () -> Unit = {},
    val onClearCovers: () -> Unit = {},
    val onClearMb: () -> Unit = {},
    val onHistory: () -> Unit = {},
    val onYtdlp: () -> Unit = {},
)

/** The mock's five colour swatches (`.swatches i`). */
val Swatches = listOf(Color(0xFF7C5CFF), Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEF4444))

private val LastFmRed = Color(0xFFD51007)

/**
 * Mock screen `settings` ("Ayarlar"): Hesaplar (Meiro account, server, YouTube Music, Last.fm), Görünüm (theme, the
 * wallpaper colour and the swatches, the colour from the cover), Çalma (loudness, source order, quality on mobile data),
 * Öneriler (recommend songs, new artists, Daily mix), İndirme ve depolama (Wi-Fi only, the four caches), Geçmiş and
 * Hakkında (credit, version, yt-dlp).
 */
@Composable
fun SettingsScreen(ui: SettingsUi, actions: SettingsActions = SettingsActions(), state: LazyListState = rememberLazyListState()) {
    val c = Meiro.colors
    Column(Modifier.fillMaxSize()) {
        TopBar("Ayarlar", onBack = actions.onBack, avatar = false)
        LazyColumn(Modifier.fillMaxSize(), state) {
            item(key = "g1") { Group("group1", "Hesaplar") }
            item(key = "account") { SettingRow("account", MeiroIcons.Person, "Meiro hesabı", ui.accountSub) { TextButton("account.btn", ui.accountButton, actions.onAccount) } }
            item(key = "server") { SettingRow("server", MeiroIcons.Sync, "Sunucu", ui.server) { TextButton("server.btn", "Değiştir", actions.onServer) } }
            item(key = "ytm") {
                SettingRow("ytm", null, "YouTube Music", ui.ytmSub, lead = { LogoBox { Image(MeiroLogos.Ytm, null, Modifier.size(14.dp).inv("ytm.icon")) } }, tag = "isteğe bağlı", onClick = actions.onYtmRow) {
                    TextButton("ytm.btn", ui.ytmButton, actions.onYtm)
                }
            }
            item(key = "lastfm") {
                SettingRow("lastfm", null, "Last.fm", ui.lastfmSub, lead = {
                    LogoBox { Canvas(Modifier.size(14.dp).inv("lastfm.icon", tint = Color.Black)) { drawCircle(LastFmRed, radius = size.minDimension * 11f / 24f) } }
                }) { TextButton("lastfm.btn", ui.lastfmButton, actions.onLastfm) }
            }
            item(key = "g2") { Group("group2", "Görünüm") }
            item(key = "theme") { SettingRow("theme", MeiroIcons.Palette, "Tema", null) {} }
            item(key = "theme.seg") {
                Segmented(
                    "theme.seg", listOf("Sistem", "Açık", "Koyu"), listOf("theme.system", "theme.light", "theme.dark"), ui.theme.ordinal,
                    { actions.onTheme(ThemeChoice.entries[it]) }, Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
            item(key = "wallpaper") {
                SettingRow("wallpaper", null, "Duvar kağıdından renk", "Android 12+ için. Kapalıyken aşağıdaki renk.") { MeiroSwitch("wallpaper.switch", ui.wallpaper, actions.onWallpaper) }
            }
            item(key = "swatches") {
                Row(Modifier.padding(start = 54.dp, end = 16.dp, top = 4.dp, bottom = 10.dp), Arrangement.spacedBy(10.dp)) {
                    Swatches.forEachIndexed { i, col ->
                        Box(
                            Modifier.size(36.dp).inv("swatch${i + 1}", bg = col, radiusDp = 18f).clip(CircleShape).background(col)
                                .border(3.dp, if (i == ui.swatch) c.onSurface else Color.Transparent, CircleShape)
                                .clickableNoIndication { actions.onSwatch(i) },
                        )
                    }
                }
            }
            item(key = "coverColor") {
                SettingRow("coverColor", null, "Rengi kapaktan al", "Oynatıcı çalan şarkının kapağına göre renklenir.") { MeiroSwitch("coverColor.switch", ui.coverColor, actions.onCoverColor) }
            }
            item(key = "g3") { Group("group3", "Çalma") }
            item(key = "loudness") { SettingRow("loudness", MeiroIcons.Volume, "Ses seviyesini dengele", null) { MeiroSwitch("loudness.switch", ui.loudness, actions.onLoudness) } }
            item(key = "sourceOrder") { SettingRow("sourceOrder", null, "Kaynak sırası", ui.sourceOrder) { TextButton("sourceOrder.btn", "Düzenle", actions.onSourceOrder) } }
            item(key = "quality") { SettingRow("quality", null, "Mobil veride ses kalitesi", ui.quality) { TextButton("quality.btn", "Değiştir", actions.onQuality) } }
            item(key = "g4") { Group("group4", "Öneriler") }
            item(key = "recommend") {
                SettingRow("recommend", MeiroIcons.Spark, "Şarkı öner", "Daily mix ve kuyruğa öneri. Meiro hesabındaki tüm dinlemelerden öğrenir.") {
                    MeiroSwitch("recommend.switch", ui.recommend, actions.onRecommend)
                }
            }
            item(key = "explore") { SettingRow("explore", null, "Yeni sanatçılar", ui.exploreSub) { MeiroSlider("explore.slider", ui.explore, actions.onExplore, actions.onExploreDone) } }
            item(key = "dailyMix") { SettingRow("dailyMix", null, "Daily mix", ui.dailyMix) { TextButton("dailyMix.btn", "Şimdi yap", actions.onDailyMix, enabled = ui.recommend && !ui.dailyMixBusy) } }
            item(key = "g5") { Group("group5", "İndirme ve depolama") }
            item(key = "wifi") { SettingRow("wifi", MeiroIcons.Wifi, "Yalnız Wi-Fi'de indir", null) { MeiroSwitch("wifi.switch", ui.wifiOnly, actions.onWifiOnly) } }
            item(key = "ytCache") { SettingRow("ytCache", MeiroIcons.Storage, "YouTube Music önbelleği", ui.ytCache) { TextButton("ytCache.btn", "Temizle", actions.onClearYt) } }
            item(key = "scCache") { SettingRow("scCache", null, "SoundCloud önbelleği", ui.scCache) { TextButton("scCache.btn", "Temizle", actions.onClearSc) } }
            item(key = "coverCache") { SettingRow("coverCache", null, "Kapak önbelleği", ui.coverCache) { TextButton("coverCache.btn", "Temizle", actions.onClearCovers) } }
            item(key = "mbCache") { SettingRow("mbCache", null, "MusicBrainz önbelleği", ui.mbCache) { TextButton("mbCache.btn", "Temizle", actions.onClearMb) } }
            item(key = "g6") { Group("group6", "Geçmiş") }
            item(key = "history") { SettingRow("history", MeiroIcons.History, "Dinleme geçmişi", ui.history) { TextButton("history.btn", "Aç", actions.onHistory) } }
            item(key = "g7") { Group("group7", "Hakkında") }
            item(key = "credit") { SettingRow("credit", MeiroIcons.Person, "Meiro fork'u üzerine Murat Kirazkaya tarafından geliştirilmiştir", null) {} }
            item(key = "version") { SettingRow("version", MeiroIcons.Info, ui.version, ui.versionSub) {} }
            item(key = "ytdlp") { SettingRow("ytdlp", null, "yt-dlp", ui.ytdlp) { TextButton("ytdlp.btn", "Güncelle", actions.onYtdlp, enabled = !ui.ytdlpBusy) } }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** `.sgroup`: 22/16/8 padding, 13/500 primary; the whole block is the element. */
@Composable
private fun Group(p: String, title: String) {
    Box(Modifier.fillMaxWidth().inv(p).padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp)) {
        MText(title, MeiroType.SettingsGroup, Meiro.colors.primary, "$p.label", maxLines = 1)
    }
}

/** The `span.src` (24dp wide) that centres a 14dp logo where a row's glyph would be. */
@Composable
private fun LogoBox(content: @Composable () -> Unit) {
    Box(Modifier.width(24.dp), Alignment.Center) { content() }
}

/**
 * `.srow`: at least 64dp, 10dp/16dp padding, 14dp apart: the glyph (or [lead], or a 24dp space), the title (16/22) with
 * an optional [tag] and the line under it (13/18, wraps), and [trailing] (a button, a switch, the slider).
 */
@Composable
private fun SettingRow(
    p: String, icon: ImageVector?, title: String, sub: String?, lead: (@Composable () -> Unit)? = null, tag: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit,
) {
    val c = Meiro.colors
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickableNoIndication(onClick) else Modifier)
            .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        Arrangement.spacedBy(14.dp), Alignment.CenterVertically,
    ) {
        when {
            lead != null -> lead()
            icon != null -> MIcon(icon, c.onSurfaceVariant, "$p.icon")
            else -> Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            if (tag == null) MText(title, MeiroType.SettingTitle, c.onSurface, "$p.title", Modifier.fillMaxWidth())
            else Row(Modifier.fillMaxWidth().inv("$p.title"), verticalAlignment = Alignment.CenterVertically) {
                MText(title, MeiroType.SettingTitle, c.onSurface, "$p.title.label", maxLines = 1)
                Box(
                    Modifier.padding(start = 6.dp).inv("$p.tag", bg = c.surfaceContainerHighest, radiusDp = 6f).clip(RoundedCornerShape(6.dp))
                        .background(c.surfaceContainerHighest).padding(horizontal = 8.dp),
                ) { MText(tag, MeiroType.SettingSub, c.onSurfaceVariant, "$p.tag.label", maxLines = 1) }
            }
            if (sub != null) MText(sub, MeiroType.SettingSub, c.onSurfaceVariant, "$p.sub", Modifier.fillMaxWidth())
        }
        trailing()
    }
}
