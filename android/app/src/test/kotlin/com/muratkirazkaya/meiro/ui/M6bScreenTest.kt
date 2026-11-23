package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.AccountActions
import com.muratkirazkaya.meiro.ui.screens.AccountSheet
import com.muratkirazkaya.meiro.ui.screens.AccountUi
import com.muratkirazkaya.meiro.ui.screens.ClearHistorySheet
import com.muratkirazkaya.meiro.ui.screens.HistoryActions
import com.muratkirazkaya.meiro.ui.screens.HistoryMenuActions
import com.muratkirazkaya.meiro.ui.screens.HistoryMenuSheet
import com.muratkirazkaya.meiro.ui.screens.HistoryScreen
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.PlaylistScreen
import com.muratkirazkaya.meiro.ui.screens.SettingsActions
import com.muratkirazkaya.meiro.ui.screens.SettingsScreen
import com.muratkirazkaya.meiro.ui.screens.SettingsUi
import com.muratkirazkaya.meiro.ui.screens.StatsActions
import com.muratkirazkaya.meiro.ui.screens.StatsScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.screens.ThemeChoice
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val Bars = SystemBars(36.dp, 16.dp)

private fun play(key: String, title: String, artist: String, ytm: Boolean, sc: Boolean, activeYtm: Boolean, hue: Int, note: String,
                 link: LinkBadge = LinkBadge.None, dl: Boolean = false, now: Boolean = false, dim: Boolean = false, preview: Boolean = false) =
    HistoryLine.Play(SongRowData(key, title, artist, ytm, sc, activeYtm, CoverSpec(null, hue), dl, link, now, preview, note = note, dim = dim), HistoryMapping.item(com.muratkirazkaya.meiro.data.HistoryEntry(videoId = key, title = title)))

/** The mock's history (`HIST`): three days, the playing row first, the skipped plays faded. */
val MockHistory = HistoryUi(
    chips = listOf(
        HistoryChip(HistoryMapping.ALL, "Hepsi"), HistoryChip(HistoryMapping.COUNTED, "Sayılanlar"), HistoryChip(HistoryMapping.SKIPPED, "Geçilenler"),
        HistoryChip(HistoryMapping.DEVICE + "telefon", "Telefon"), HistoryChip(HistoryMapping.DEVICE + "masaüstü", "Masaüstü"),
    ),
    filter = HistoryMapping.ALL,
    lines = listOf(
        HistoryLine.Day("Bugün", "5 çalma · ~16 dk"),
        play("s10", "Bu Kalp Seni Unutur mu", "Fikret Kızılok", true, true, true, 10, "14:48 · telefon · tamamı", now = true),
        play("s9", "Son Arzum", "Nilüfer", true, false, true, 24, "14:44 · telefon · tamamı", LinkBadge.Unknown, dl = true),
        play("s12", "Yolla", "Tarkan", true, false, true, 270, "14:40 · telefon · geçildi · 0:12", dim = true),
        play("s4", "Nightcall", "Kavinsky", true, true, true, 300, "14:31 · masaüstü · 2:05 / 4:18"),
        play("s5", "Stolen Dance", "Milky Chance", false, true, false, 150, "10:02 · masaüstü · tamamı", dl = true),
        HistoryLine.Day("Dün", "3 çalma · ~8 dk"),
        play("s6", "After Dark", "Mr.Kitty", true, false, true, 330, "23:17 · masaüstü · tamamı"),
        play("s7", "Gece Yarısı (canlı)", "Anıl Kaya", false, true, false, 220, "23:12 · masaüstü · geçildi · 0:08", LinkBadge.Unknown, dim = true),
        play("s11", "Cesaretin Var mı Aşka", "Gülay", true, false, true, 170, "22:58 · telefon · tamamı", LinkBadge.Unknown),
        HistoryLine.Day("27 Ekim Salı", "2 çalma · ~8 dk"),
        play("s1", "Get Lucky", "Daft Punk, Pharrell Williams", true, true, true, 36, "19:40 · telefon · tamamı"),
        play("s13", "Starboy", "The Weeknd, Daft Punk", false, true, false, 0, "19:36 · telefon · 0:30", preview = true),
    ),
    note = HistoryMapping.note(true),
)

/** The mock's stats for "Ekim 2026" (`V.stats` with `statsPeriod` month). */
val MockStats = StatsUi(
    monthLabel = "Ekim 2026", yearLabel = "2026", period = StatsPeriod.Month,
    tiles = listOf("41 sa" to "dinleme", "612" to "çalma", "184" to "farklı şarkı", "73" to "sanatçı · 19 yeni"),
    bars = listOf(30, 45, 20, 60, 80, 55, 40, 70, 90, 65, 35, 50, 75, 85).mapIndexed { i, v -> StatBar(v / 100f, i % 3 == 0) },
    artists = listOf(RankRow("Fikret Kızılok", "96 çalma", CoverSpec(null, 10)), RankRow("Kavinsky", "71 çalma", CoverSpec(null, 300)), RankRow("Hidra", "58 çalma", CoverSpec(null, 200))),
    tracks = listOf(
        RankRow("Bu Kalp Seni Unutur mu", "Fikret Kızılok · 42 çalma", CoverSpec(null, 10)),
        RankRow("Nightcall", "Kavinsky · 31 çalma", CoverSpec(null, 300)),
        RankRow("Son Arzum", "Nilüfer · 27 çalma", CoverSpec(null, 24)),
    ),
    note = "Bütün cihazlardaki dinlemeler, Meiro hesabından. Aynı özet dashboard'da da var.",
)

/** The mock's settings (`V.settings`, YouTube Music signed in as in the reference). */
val MockSettings = SettingsUi(
    accountSub = "murat@… · eşitleme güncel, 2 dk önce", accountButton = "Çıkış", server = "meiro.muratkirazkaya.com",
    ytmSub = "Girişli · Murat K. · kanal: kişisel", ytmButton = "Çıkış", loudness = true,
    dailyMix = "Bugün 07:12'de yapıldı", ytCache = "480 MB / 1 GB · 112 şarkı", scCache = "230 MB / 1 GB · 51 şarkı",
    coverCache = "64 MB · 3.120 kapak", mbCache = "sanatçı 30 gün, şarkı 1 gün · 2,4 MB", history = "1.204 çalma · 30 sn ya da yarısı sayılır",
    version = "Meiro 0.5.0", ytdlp = "2026.10.12 · her şarkının sesini bulur",
)

val MockAccount = AccountUi("M", "murat@… · eşitleme güncel", "2 dk önce", true)

/** Mock screens `history`, `stats`, `settings` (each with scrolled states, so every element of the mock is compared), `account`, `histmenu`, `clearhist`. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6bScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun scrolled(screen: String, keys: String, theme: String, by: Int, content: @androidx.compose.runtime.Composable (LazyListState) -> Unit) {
        val state = LazyListState()
        checkScreen(
            rule, screen, theme, keysName = keys, shiftY = by.toDouble(), variant = "-" + keys.removePrefix("$screen-"),
            beforeCapture = { rule.runOnIdle { runBlocking { state.scrollBy(by.toFloat()) } } },
        ) { content(state) }
    }

    private val history = @androidx.compose.runtime.Composable { s: LazyListState -> MeiroShell(Tab.Downloads, {}, Bars, SampleMini) { HistoryScreen(MockHistory, true, state = s) } }
    private val stats = @androidx.compose.runtime.Composable { s: LazyListState -> MeiroShell(Tab.Downloads, {}, Bars, SampleMini) { StatsScreen(MockStats, state = s) } }
    private val settings = @androidx.compose.runtime.Composable { s: LazyListState -> MeiroShell(Tab.Downloads, {}, Bars, SampleMini) { SettingsScreen(MockSettings, state = s) } }

    @Test fun historyLight() = checkScreen(rule, "history", "light") { history(rememberLazyListState()) }
    @Test fun historyDark() = checkScreen(rule, "history", "dark") { history(rememberLazyListState()) }
    @Test fun historyScrolledLight() = scrolled("history", "history-scrolled", "light", 440, history)
    @Test fun historyScrolledDark() = scrolled("history", "history-scrolled", "dark", 440, history)

    @Test fun statsLight() = checkScreen(rule, "stats", "light") { stats(rememberLazyListState()) }
    @Test fun statsDark() = checkScreen(rule, "stats", "dark") { stats(rememberLazyListState()) }
    @Test fun statsScrolledLight() = scrolled("stats", "stats-scrolled", "light", 370, stats)
    @Test fun statsScrolledDark() = scrolled("stats", "stats-scrolled", "dark", 370, stats)

    @Test fun settingsLight() = checkScreen(rule, "settings", "light") { settings(rememberLazyListState()) }
    @Test fun settingsDark() = checkScreen(rule, "settings", "dark") { settings(rememberLazyListState()) }
    @Test fun settingsScrolled1Light() = scrolled("settings", "settings-scrolled1", "light", 500, settings)
    @Test fun settingsScrolled1Dark() = scrolled("settings", "settings-scrolled1", "dark", 500, settings)
    @Test fun settingsScrolled2Light() = scrolled("settings", "settings-scrolled2", "light", 1000, settings)
    @Test fun settingsScrolled2Dark() = scrolled("settings", "settings-scrolled2", "dark", 1000, settings)
    @Test fun settingsScrolled3Light() = scrolled("settings", "settings-scrolled3", "light", 1464, settings)
    @Test fun settingsScrolled3Dark() = scrolled("settings", "settings-scrolled3", "dark", 1464, settings)

    /** The account sheet over the playlist page (the mock opens it there, Kütüphane selected). */
    private fun account(theme: String) = checkScreen(rule, "account", theme) {
        Box(Modifier.fillMaxSize()) {
            MeiroShell(Tab.Library, {}, Bars, SampleMini) { PlaylistScreen(MockPlaylist, true, {}, {}, {}, {}) }
            AccountSheet(MockAccount, Bars)
        }
    }
    @Test fun accountLight() = account("light")
    @Test fun accountDark() = account("dark")

    private fun histmenu(theme: String) = checkScreen(rule, "histmenu", theme) {
        Box(Modifier.fillMaxSize()) { MeiroShell(Tab.Home, {}, Bars, SampleMini) { HistoryScreen(MockHistory, true) }; HistoryMenuSheet(false, true, Bars) }
    }
    @Test fun histmenuLight() = histmenu("light")
    @Test fun histmenuDark() = histmenu("dark")

    private fun clearhist(theme: String) = checkScreen(rule, "clearhist", theme) {
        Box(Modifier.fillMaxSize()) { MeiroShell(Tab.Home, {}, Bars, SampleMini) { HistoryScreen(MockHistory, true) }; ClearHistorySheet(true, Bars, {}, {}) }
    }
    @Test fun clearhistLight() = clearhist("light")
    @Test fun clearhistDark() = clearhist("dark")

    @Test fun historyControlsCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                HistoryScreen(
                    MockHistory, false,
                    HistoryActions(
                        onBack = { calls += "back" }, onSearch = { calls += "search" }, onMenu = { calls += "menu" }, onChip = { calls += "chip $it" },
                        onRow = { calls += "row $it" }, onMore = { calls += "more $it" }, onLink = { calls += "link $it" },
                    ),
                )
            }
        }
        for (t in listOf("topbar.backBtn", "topbar.searchBtn", "topbar.moreBtn", "chip.counted", "song2.title", "song3.moreBtn", "song2.link")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("back", "search", "menu", "chip counted", "row 1", "more 2", "link 1"), calls)
    }

    @Test fun theSearchBarShowsWhileSearchingAndClosesWithItsButton() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { HistoryScreen(MockHistory.copy(searching = true), false, HistoryActions(onSearch = { calls += "close" })) } }
        rule.onNodeWithTag("hsearch", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("hsearch.clearBtn", useUnmergedTree = true).performClick()
        assertEquals(listOf("close"), calls)
    }

    @Test fun statsPeriodSwitch() {
        val calls = mutableListOf<StatsPeriod>()
        rule.setContent { MeiroTheme { StatsScreen(MockStats, StatsActions(onPeriod = { calls += it })) } }
        rule.onNodeWithTag("seg.year", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("seg.month", useUnmergedTree = true).performClick()
        assertEquals(listOf(StatsPeriod.Year, StatsPeriod.Month), calls)
    }

    @Test fun settingsControlsCallTheirActions() {
        val calls = mutableListOf<String>()
        val state = LazyListState()
        rule.setContent {
            MeiroTheme {
                Box(Modifier.fillMaxSize()) {
                    SettingsScreen(
                        MockSettings, state = state, actions =
                        SettingsActions(
                            onAccount = { calls += "account" }, onServer = { calls += "server" }, onTheme = { calls += "theme $it" },
                            onCoverColor = { calls += "cover" }, onRecommend = { calls += "recommend" }, onExplore = { calls += "explore" },
                            onDailyMix = { calls += "mix" }, onWifiOnly = { calls += "wifi" }, onClearYt = { calls += "yt" }, onClearSc = { calls += "sc" },
                            onClearCovers = { calls += "covers" }, onClearMb = { calls += "mb" }, onHistory = { calls += "history" }, onYtdlp = { calls += "ytdlp" },
                        ),
                    )
                }
            }
        }
        val tags = listOf(
            "account.btn", "server.btn", "theme.dark", "coverColor.switch", "recommend.switch", "dailyMix.btn", "wifi.switch",
            "ytCache.btn", "scCache.btn", "coverCache.btn", "mbCache.btn", "history.btn", "ytdlp.btn",
        )
        for (t in tags) {
            // The list is scrolled until the control lies well inside the screen, then it is tapped.
            var tries = 0
            while (tries++ < 30) {
                val n = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull { it.layoutInfo.isPlaced }
                if (n != null && n.boundsInRoot.top > 120f && n.boundsInRoot.bottom < 600f) break
                val up = n != null && n.boundsInRoot.height > 0f && n.boundsInRoot.top <= 120f
                rule.runOnIdle { runBlocking { state.scrollBy(if (up) -150f else 150f) } }
            }
            rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        }
        assertEquals(listOf("account", "server", "theme Dark", "cover", "recommend", "mix", "wifi", "yt", "sc", "covers", "mb", "history", "ytdlp"), calls)
    }

    @Test fun accountItemsCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                AccountSheet(MockAccount, Bars, AccountActions(
                    onDismiss = { calls += "dismiss" }, onHistory = { calls += "history" }, onStats = { calls += "stats" },
                    onSettings = { calls += "settings" }, onSync = { calls += "sync" }, onSignOut = { calls += "signout" },
                ))
            }
        }
        for (t in listOf("account.history", "account.stats", "account.settings", "account.sync", "account.signout", "account.scrim")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("history", "stats", "settings", "sync", "signout", "dismiss"), calls)
    }

    @Test fun historyMenuItemsAndTheQuestion() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                Box(Modifier.fillMaxSize()) {
                    HistoryMenuSheet(true, false, Bars, HistoryMenuActions(onPause = { calls += "pause" }, onStats = { calls += "stats" }, onClear = { calls += "clear" }))
                }
            }
        }
        rule.onNodeWithTag("histmenu.pause.label", useUnmergedTree = true).assertTextEquals("Kaydı sürdür")
        rule.onNodeWithTag("histmenu.clear.where", useUnmergedTree = true).assertTextEquals("bu cihazda")
        for (t in listOf("histmenu.pause", "histmenu.stats", "histmenu.clear")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("pause", "stats", "clear"), calls)
    }

    @Test fun clearingAsksAndOnlyTemizleClears() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { ClearHistorySheet(true, Bars, { calls += "cancel" }, { calls += "clear" }) } }
        rule.onNodeWithTag("clearhist.cancel", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("clearhist.scrim", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("clearhist.delete", useUnmergedTree = true).performClick()
        assertEquals(listOf("cancel", "cancel", "clear"), calls)
    }
}


private const val LONG = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978)"

/** History, stats, settings and the sheets under long text, 320dp, font 1.3, light and dark (structural rules only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6bStressTest {
    @get:Rule val rule = createComposeRule()

    private val longHistory = MockHistory.copy(
        chips = MockHistory.chips + HistoryChip(HistoryMapping.DEVICE + LONG, LONG),
        lines = listOf(HistoryLine.Day(LONG, "1234 çalma · ~98765 dk")) + (1..10).map {
            play("k$it", if (it % 2 == 0) LONG else "Ş", LONG, true, it % 3 == 0, true, it * 30, "23:59 · $LONG · 59:59 / 99:59",
                if (it % 4 == 0) LinkBadge.Unknown else LinkBadge.None, dl = it % 2 == 0, now = it == 1, dim = it % 3 == 1, preview = it == 5)
        },
        searching = true, query = LONG,
    )
    private val longStats = MockStats.copy(
        monthLabel = "Ağustos 2026", tiles = listOf("9999 sa" to "dinleme", "123.456" to "çalma", "99.999" to "farklı şarkı", "12.345" to "sanatçı · 1.234 yeni"),
        bars = (1..31).map { StatBar(if (it % 5 == 0) StatsMapping.QUIET else it / 31f, it % 5 == 0) },
        artists = (1..5).map { RankRow(LONG, "12.345 çalma", CoverSpec(null, it * 20)) },
        tracks = (1..5).map { RankRow(LONG, "$LONG · 12.345 çalma", CoverSpec(null, it * 40)) },
    )
    private val longSettings = MockSettings.copy(
        accountSub = "$LONG@… · 1234 kayıt bekliyor, 365 gün önce", server = "çok-uzun-bir-alt-alan-adı.$LONG.example.com".replace(" ", ""),
        dailyMix = LONG, ytCache = "1023 MB / 512 MB · 12.345 şarkı", ytdlp = "2026.10.12.123456 · $LONG", explore = 1f, theme = ThemeChoice.Dark,
    )

    private fun run(name: String, keys: String, theme: String, width: Int, font: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val fails = stressScreen(rule, "$name-${width}dp-$font", keys, theme, width, font, content)
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    private fun shell(content: @androidx.compose.runtime.Composable () -> Unit) = @androidx.compose.runtime.Composable { MeiroShell(Tab.Home, {}, Bars, SampleMini) { content() } }

    @Test fun historyLight370() = run("history-long", "history", "light", 370, 1f, shell { HistoryScreen(longHistory, true) })
    @Test fun historyDark320Font13() = run("history-long", "history", "dark", 320, 1.3f, shell { HistoryScreen(longHistory, true) })
    @Test fun historyMockLight320Font13() = run("history-mock", "history", "light", 320, 1.3f, shell { HistoryScreen(MockHistory, true) })
    @Test fun historyEmptyDark() = run("history-empty", "history", "dark", 320, 1.3f, shell { HistoryScreen(MockHistory.copy(lines = emptyList(), empty = true), false) })
    @Test fun statsLight370() = run("stats-long", "stats", "light", 370, 1f, shell { StatsScreen(longStats) })
    @Test fun statsDark320Font13() = run("stats-long", "stats", "dark", 320, 1.3f, shell { StatsScreen(longStats) })
    @Test fun statsMockLight320Font13() = run("stats-mock", "stats", "light", 320, 1.3f, shell { StatsScreen(MockStats) })
    @Test fun settingsLight370() = run("settings-long", "settings", "light", 370, 1f, shell { SettingsScreen(longSettings) })
    @Test fun settingsDark320Font13() = run("settings-long", "settings", "dark", 320, 1.3f, shell { SettingsScreen(longSettings) })
    @Test fun settingsMockLight320Font13() = run("settings-mock", "settings", "light", 320, 1.3f, shell { SettingsScreen(MockSettings) })
    @Test fun accountLongDark320Font13() = run("account-long", "account", "dark", 320, 1.3f) { AccountSheet(AccountUi("Ş", "$LONG@… · 1234 kayıt bekliyor", "365 gün önce", true), Bars) }
    @Test fun accountGuestLight() = run("account-guest", "account", "light", 370, 1f) { AccountSheet(AccountUi("M", "Hesap yok · bu cihazda", "hesap yok", false), Bars) }
    @Test fun histmenuDark320Font13() = run("histmenu", "histmenu", "dark", 320, 1.3f) { HistoryMenuSheet(true, false, Bars) }
    @Test fun clearhistLight320Font13() = run("clearhist", "clearhist", "light", 320, 1.3f) { ClearHistorySheet(false, Bars, {}, {}) }
}
