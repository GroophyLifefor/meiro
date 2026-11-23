package com.muratkirazkaya.meiro.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.downloads.DownloadBatch
import com.muratkirazkaya.meiro.downloads.DownloadItem
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.LocalDownloaded
import com.muratkirazkaya.meiro.ui.components.LocalOffline
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.SongRow
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.components.sizeText
import com.muratkirazkaya.meiro.ui.screens.DownloadsActions
import com.muratkirazkaya.meiro.ui.screens.DownloadsScreen
import com.muratkirazkaya.meiro.ui.screens.DownloadsUi
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.MeterUi
import com.muratkirazkaya.meiro.ui.screens.PendingRowUi
import com.muratkirazkaya.meiro.ui.screens.SearchActions
import com.muratkirazkaya.meiro.ui.screens.SearchScreen
import com.muratkirazkaya.meiro.ui.screens.SearchUi
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val Bars = SystemBars(36.dp, 16.dp)

/**
 * The mock's demo data for screen `downloads`. The bar's parts are the mock's own widths (28%, 9%, 5%); the third row
 * (Nightcall) has no downloaded mark in the mock (its `DB.s4.dl` is false), so the test leaves it off too.
 */
val MockDownloads = DownloadsUi(
    meter = MeterUi(
        "2,1 GB", "kullanılıyor · telefonda 41 GB boş", 0.28f, 0.09f, 0.05f,
        listOf("İndirilen 1,4 GB · 212", "YT önbellek 480 MB", "SC önbellek 230 MB"),
    ),
    pending = listOf(PendingRowUi("b1", "Daily mix", "12 / 30 şarkı · Wi-Fi bekleniyor", 0.4f, CoverSpec(null, 270))),
    rows = listOf(
        SongRowData("s5", "Stolen Dance", "Milky Chance · 9,1 MB", false, true, false, CoverSpec(null, 150), downloaded = true),
        SongRowData("s9", "Son Arzum", "Nilüfer · 6,8 MB", true, false, true, CoverSpec(null, 24), downloaded = true, link = LinkBadge.Unknown),
        SongRowData("s4", "Nightcall", "Kavinsky · 8,2 MB", true, true, true, CoverSpec(null, 300)),
    ),
)

/** The mock's offline state (`offline`): Ara with the banner, "gece" searched, the note and two tracks from the downloads. */
val MockOffline = SearchUi(
    query = "gece", results = true, note = SearchMapping.LOCAL_NOTE,
    songs = listOf(
        SongRowData("s5", "Stolen Dance", "Milky Chance · 5:13", false, true, false, CoverSpec(null, 150), downloaded = true),
        SongRowData("s9", "Son Arzum", "Nilüfer · 3:47", true, false, true, CoverSpec(null, 24), downloaded = true, link = LinkBadge.Unknown),
    ),
)

/** Mock screens `downloads` and `offline`, light and dark, and what the downloads tab's controls call. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class DownloadsScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun downloads(theme: String) = checkScreen(rule, "downloads", theme) {
        MeiroShell(Tab.Downloads, {}, Bars, SampleMini) { DownloadsScreen(MockDownloads, true) }
    }

    private fun offline(theme: String) = checkScreen(rule, "offline", theme) {
        CompositionLocalProvider(LocalOffline provides true) {
            MeiroShell(Tab.Search, {}, Bars, SampleMini) { SearchScreen(MockOffline, true, SearchActions()) }
        }
    }

    @Test fun downloadsLight() = downloads("light")
    @Test fun downloadsDark() = downloads("dark")
    @Test fun offlineLight() = offline("light")
    @Test fun offlineDark() = offline("dark")

    @Test fun controlsCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                DownloadsScreen(
                    MockDownloads, false,
                    DownloadsActions(
                        onStopAll = { calls += "all" }, onStop = { calls += "stop $it" }, onSort = { calls += "sort" },
                        onRow = { calls += "row $it" }, onMore = { calls += "more $it" }, onLink = { calls += "link $it" },
                    ),
                )
            }
        }
        for (t in listOf("pending.stopBtn", "batch1.closeBtn", "songs.sortBtn", "song2.title", "song1.moreBtn", "song2.link")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("all", "stop b1", "sort", "row 1", "more 0", "link 1"), calls)
    }

    /** Sections with nothing in them are left out; the banner shows while offline. */
    @Test fun emptySectionsAndTheBanner() {
        rule.setContent { MeiroTheme { DownloadsScreen(MockDownloads.copy(pending = emptyList(), rows = emptyList(), offline = true), false) } }
        rule.onNodeWithTag("pending.title", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("songs.title", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("banner", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("note", useUnmergedTree = true).assertExists()
    }

    /** A row of any screen shows the downloaded mark when the store holds the track it plays (the shell provides the set). */
    @Test fun aRowShowsTheMarkWhenTheStoreHoldsItsTrack() {
        val row = SongRowData("vid1", "T", "A · 3:00", true, false, true, CoverSpec(null, 1))
        rule.setContent {
            MeiroTheme {
                CompositionLocalProvider(LocalDownloaded provides setOf("ytm:vid1")) {
                    androidx.compose.foundation.layout.Column {
                        SongRow("song1", row, false, {})
                        SongRow("song2", row.copy(key = "vid2"), false, {})
                    }
                }
            }
        }
        rule.onNodeWithTag("song1.dl", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("song2.dl", useUnmergedTree = true).assertDoesNotExist()
    }
}

/** The downloads mapping: sizes in the mock's form, the meter, the rows of "İniyor", the sort orders. */
class DownloadsMappingTest {
    @Test fun sizesAreWrittenAsTheMockWritesThem() {
        assertEquals("480 MB", sizeText(480L shl 20))
        assertEquals("9,1 MB", sizeText((9.1 * (1 shl 20)).toLong()))
        assertEquals("2,1 GB", sizeText((2.1 * (1L shl 30)).toLong()))
        assertEquals("41 GB", sizeText(41L shl 30))
        assertEquals("512 B", sizeText(512))
    }

    @Test fun meterAddsTheCachesToTheDownloadsAndScalesTheBar() {
        val m = DownloadsMapping.meter(1L shl 30, 3, 256L shl 20, 0, 41L shl 30, cacheLimit = 512L shl 20)
        assertEquals("1,3 GB", m.used)
        assertEquals("kullanılıyor · telefonda 41 GB boş", m.sub)
        assertEquals(listOf("İndirilen 1,0 GB · 3", "YT önbellek 256 MB", "SC önbellek 0 B"), m.legend)
        assertEquals(0.5f, m.downloaded, 0.001f); assertEquals(0.125f, m.ytCache, 0.001f); assertEquals(0f, m.scCache, 0.001f)
    }

    @Test fun pendingRowsSayHowFarTheyAre() {
        val list = DownloadBatch("b1", "Gece Sürüşü", "", 30, 12, 1, 2, 0.43f, single = false)
        assertEquals("12 / 30 şarkı · 1 indirilemedi", DownloadsMapping.pending(list).meta)
        assertEquals("12 / 30 şarkı · sırada", DownloadsMapping.pending(list.copy(failed = 0, running = 0)).meta)
        val one = DownloadBatch("b2", "Get Lucky", "", 1, 0, 0, 1, 0.37f, single = true, artist = "Daft Punk")
        assertEquals("Daft Punk · %37", DownloadsMapping.pending(one).meta)
        assertEquals("Daft Punk · sırada", DownloadsMapping.pending(one.copy(running = 0)).meta)
    }

    @Test fun sortOrdersAndRows() {
        val a = DownloadItem(key = "a", title = "Şarkı", artist = "X", sizeBytes = 5, downloadedAt = 1)
        val b = DownloadItem(key = "b", title = "Ceviz", artist = "Y", sizeBytes = 9, downloadedAt = 2, source = "soundcloud", refs = mapOf("soundcloud" to "b", "ytm" to "vb"))
        assertEquals(listOf("b", "a"), DownloadsMapping.sorted(listOf(a, b), DownloadSort.Newest).map { it.key })
        assertEquals(listOf("b", "a"), DownloadsMapping.sorted(listOf(a, b), DownloadSort.Title).map { it.key })
        assertEquals(listOf("b", "a"), DownloadsMapping.sorted(listOf(a, b), DownloadSort.Size).map { it.key })
        val r = DownloadsMapping.row(b, "matched", "b")
        assertTrue(r.ytm && r.sc && !r.activeYtm && r.downloaded && r.now)
        assertEquals("Y · 9 B", r.subtitle)
        assertEquals(LinkBadge.None, r.link)
        assertEquals(LinkBadge.Unknown, DownloadsMapping.row(a, null, null).link)
    }
}

private const val LONG = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978)"

/** The downloads tab and the offline search under long text, 320dp, font 1.3, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class DownloadsStressTest {
    @get:Rule val rule = createComposeRule()

    private val long = MockDownloads.copy(
        meter = MockDownloads.meter.copy(used = "1023 GB", sub = "kullanılıyor · telefonda 1023 GB boş", downloaded = 0.9f, ytCache = 0.08f, scCache = 0.05f,
            legend = listOf("İndirilen 1023 GB · 12345", "YT önbellek 1023 MB", "SC önbellek 1023 MB")),
        pending = listOf(
            PendingRowUi("b1", LONG, "1234 / 5678 şarkı · 12 indirilemedi · sırada", 0.99f, CoverSpec(null, 1)),
            PendingRowUi("b2", "A", "B · %1", 0.01f, CoverSpec(null, 2)),
        ),
        rows = (1..12).map { SongRowData("k$it", if (it % 2 == 0) LONG else "Ş", "$LONG · 1023 MB", true, true, it % 3 != 0, CoverSpec(null, it * 20), downloaded = true, link = if (it % 4 == 0) LinkBadge.Unknown else LinkBadge.None) },
        offline = true,
    )

    private fun run(name: String, ui: DownloadsUi, theme: String, width: Int, font: Float) {
        val fails = stressScreen(rule, "$name-${width}dp-$font", "downloads", theme, width, font) {
            MeiroShell(Tab.Downloads, {}, Bars, SampleMini) { DownloadsScreen(ui, true) }
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    private fun runOffline(theme: String, width: Int, font: Float) {
        val ui = MockOffline.copy(query = LONG, songs = (1..6).map { SongRowData("k$it", LONG, "$LONG · 3:47", it % 2 == 0, true, it % 2 == 0, CoverSpec(null, it), downloaded = true, link = LinkBadge.Unknown) })
        val fails = stressScreen(rule, "offline-${width}dp-$font", "offline", theme, width, font) {
            CompositionLocalProvider(LocalOffline provides true) { MeiroShell(Tab.Search, {}, Bars, SampleMini) { SearchScreen(ui, true, SearchActions()) } }
        }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun longLight370() = run("downloads-long", long, "light", 370, 1f)
    @Test fun longDark320() = run("downloads-long", long, "dark", 320, 1f)
    @Test fun longLight370Font13() = run("downloads-long", long, "light", 370, 1.3f)
    @Test fun longDark320Font13() = run("downloads-long", long, "dark", 320, 1.3f)
    @Test fun longLight320Font13() = run("downloads-long", long, "light", 320, 1.3f)
    @Test fun mockDark320Font13() = run("downloads-mock", MockDownloads, "dark", 320, 1.3f)
    @Test fun mockLight320() = run("downloads-mock", MockDownloads, "light", 320, 1f)
    @Test fun offlineLight370() = runOffline("light", 370, 1f)
    @Test fun offlineDark320Font13() = runOffline("dark", 320, 1.3f)
    @Test fun offlineLight320Font13() = runOffline("light", 320, 1.3f)
}
