package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.screens.HomeActions
import com.muratkirazkaya.meiro.ui.screens.HomeScreen
import com.muratkirazkaya.meiro.ui.screens.HomeUi
import com.muratkirazkaya.meiro.ui.screens.LibraryEmptyScreen
import com.muratkirazkaya.meiro.ui.screens.LibraryScreen
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.PlaylistScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.screens.YTM_LIBRARY_NOTE
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import com.muratkirazkaya.meiro.youtube.BrowsePage
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.YtmLibrary
import com.muratkirazkaya.meiro.youtube.YtmListEntry
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

/** The mock's YouTube Music library (`V.library`, `libTab === 'ytm'`) as the app's model; ids are made up. */
val MockYtmLibrary = YtmLibrary(
    liked = YtmListEntry("LM", "Liked Music", count = 1204),
    playlists = listOf(YtmListEntry("PLfakeRoad", "Hareketli Yol Şarkıları", count = 52)),
    albums = listOf(YtmListEntry("MPREb_fakeSim", "Simülasyon", album = true, artist = "Hidra")),
)

/** The rows the app maps from [MockYtmLibrary], with the mock's cover hues (350, 60, 200) for the comparison. */
val MockYtmRows: List<ListRowData> = YtmMapping.libraryRows(MockYtmLibrary).zip(listOf(350, 60, 200)) { r, h -> r.copy(cover = CoverSpec(null, h, 80)) }

/** M6g: the mapping of the account's library, liked songs and new releases to the mock's text. */
class M6gMappingTest {
    @Test fun libraryRowsAreTheMocks() {
        val rows = YtmMapping.libraryRows(MockYtmLibrary)
        assertEquals(listOf("Beğenilenler", "Hareketli Yol Şarkıları", "Simülasyon"), rows.map { it.name })
        assertEquals(listOf("YouTube Music · 1.204 parça", "YouTube Music listesi · 52", "Hidra · Albüm"), rows.map { it.meta })
        assertTrue(rows.all { it.logo == Provider.Ytm })
        assertEquals(listOf("ytm:pl:LM", "ytm:pl:PLfakeRoad", "ytm:al:MPREb_fakeSim"), rows.map { it.id })
    }

    @Test fun countsTheLibraryDoesNotNameAreLeftOut() {
        val rows = YtmMapping.libraryRows(YtmLibrary(YtmListEntry("LM", "Liked Music"), listOf(YtmListEntry("PLx", "x")), listOf(YtmListEntry("MPx", "y", album = true))))
        assertEquals(listOf("YouTube Music", "YouTube Music listesi", "Albüm"), rows.map { it.meta })
        assertTrue(YtmMapping.libraryRows(null).isEmpty())
    }

    @Test fun likedTileIsSecondAmongTheQuickPicks() {
        val meiro = listOf(MockHome.picks[0], MockHome.picks[2], MockHome.picks[3])
        val picks = YtmMapping.picks(meiro, MockYtmLibrary.liked)
        assertEquals(MockHome.picks.map { it.title to it.meta }, picks.map { it.title to it.meta })
        assertEquals(Provider.Ytm, picks[1].logo)
        assertEquals("ytm:pl:LM", picks[1].key)
        // No other picks: the liked songs alone; signed out (no liked): the picks as they were.
        assertEquals(listOf("Beğenilenler"), YtmMapping.picks(emptyList(), MockYtmLibrary.liked).map { it.title })
        assertEquals(meiro, YtmMapping.picks(meiro, null))
    }

    @Test fun releaseTilesAreTheMocks() {
        val tiles = listOf(
            MusicItem(kind = com.muratkirazkaya.meiro.youtube.ItemKind.MusicItem, browseId = "MPREb_a", title = "Random Access Memories", subtitle = "Album • Daft Punk"),
            MusicItem(kind = com.muratkirazkaya.meiro.youtube.ItemKind.MusicItem, browseId = "MPREb_b", title = "OutRun", subtitle = "Album • Kavinsky • 2013"),
            MusicItem(kind = com.muratkirazkaya.meiro.youtube.ItemKind.MusicItem, browseId = "MPREb_c", title = "Time", subtitle = "Single • Mr.Kitty"),
        ).map { YtmMapping.releaseTile(it) }
        assertEquals(listOf("Daft Punk · Albüm", "Kavinsky · Albüm", "Mr.Kitty · Single"), tiles.map { it.meta })
        assertEquals(MockHome.releases.take(2).map { it.title to it.meta }, tiles.take(2).map { it.title to it.meta })
        assertEquals(listOf("MPREb_a", "MPREb_b", "MPREb_c"), tiles.map { it.key })
    }

    @Test fun turkishDigitGrouping() {
        assertEquals("1.204", YtmMapping.count(1204))
        assertEquals("52", YtmMapping.count(52))
        assertEquals("1.000.000", YtmMapping.count(1_000_000))
    }

    @Test fun aListOfTheAccountOnThePlaylistScreen() {
        val page = BrowsePage(
            "Liked Music", "https://lh3.googleusercontent.com/fake-liked",
            items = listOf(
                MusicItem(videoId = "v1", title = "Nightcall", artists = "Kavinsky", artist = "Kavinsky", duration = "4:18"),
                MusicItem(videoId = "v2", title = "After Dark", artists = "Mr.Kitty", artist = "Mr.Kitty", duration = "4:19"),
                MusicItem(videoId = "v1", title = "Nightcall", artists = "Kavinsky", artist = "Kavinsky", duration = "4:18"),
            ),
        )
        val lib = YtmMapping.asList("LM", page)
        assertEquals("Beğenilenler", lib.name)
        assertEquals(listOf("v1", "v2"), lib.items.map { it.key })
        assertTrue(lib.items.all { it.source == "ytm" && it.state == "unknown" })
        val ui = YtmMapping.playlistUi(lib, null, null)
        assertEquals("YouTube Music · 2 parça · 8 dk", ui.kind)
        assertEquals(Provider.Ytm, ui.provider)
        val other = YtmMapping.playlistUi(YtmMapping.asList("PLx", page.copy(title = "Yol")), null, null)
        assertEquals("Yol", other.title)
        assertEquals("YouTube Music listesi · 2 parça · 8 dk", other.kind)
    }

    @Test fun rowKeysLeadBackToTheirEntries() {
        assertEquals("LM", YtmMapping.entry(MockYtmLibrary, "ytm:pl:LM")?.id)
        assertEquals("Simülasyon", YtmMapping.entry(MockYtmLibrary, "ytm:al:MPREb_fakeSim")?.title)
        assertEquals(null, YtmMapping.entry(MockYtmLibrary, "ytm:pl:nope"))
        assertEquals(null, YtmMapping.entry(null, "ytm:pl:LM"))
    }
}

/**
 * Mock screen `library-ytm` (light, dark) from the app's mapping of the mock's library, and the signed-in/out states
 * of the Library's tabs and Home's YouTube Music parts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6gScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun libraryYtm(theme: String) = checkScreen(rule, "library-ytm", theme) {
        MeiroShell(Tab.Library, {}, Bars, SampleMini) { LibraryScreen(MockLibraryRows, {}, {}, ytmTab = true, ytmSelected = true, ytmRows = MockYtmRows) }
    }

    @Test fun libraryYtmLight() = libraryYtm("light")
    @Test fun libraryYtmDark() = libraryYtm("dark")

    @Test fun ytmTabShowsItsListsAndNoteWithoutTheFab() {
        val opened = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                MeiroShell(Tab.Library, {}, Bars, SampleMini) {
                    LibraryScreen(MockLibraryRows, {}, {}, ytmTab = true, ytmSelected = true, ytmRows = MockYtmRows, onYtmOpen = { opened += "open $it" }, onYtmMore = { opened += "more $it" })
                }
            }
        }
        rule.onNodeWithTag("row1.title", useUnmergedTree = true).assertTextEquals("Beğenilenler")
        rule.onNodeWithTag("row3.meta", useUnmergedTree = true).assertTextEquals("Hidra · Albüm")
        rule.onNodeWithTag("row4.title", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("note", useUnmergedTree = true).assertTextEquals(YTM_LIBRARY_NOTE)
        rule.onNodeWithTag("fab", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("tabs.ytm.indicator", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("tabs.meiro.indicator", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("row2.title", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("row3.moreBtn", useUnmergedTree = true).performClick()
        assertEquals(listOf("open ytm:pl:PLfakeRoad", "more ytm:al:MPREb_fakeSim"), opened)
    }

    @Test fun tabsSwitchBothWays() {
        val taps = mutableListOf<Boolean>()
        rule.setContent { MeiroTheme { LibraryScreen(MockLibraryRows, {}, {}, ytmTab = true, onTab = { taps += it }) } }
        // Meiro selected: its lists, its indicator and the FAB.
        rule.onNodeWithTag("row1.title", useUnmergedTree = true).assertTextEquals("Daily mix")
        rule.onNodeWithTag("tabs.meiro.indicator", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("fab", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("tabs.ytm", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("tabs.meiro", useUnmergedTree = true).performClick()
        assertEquals(listOf(true, false), taps)
    }

    /** Signed out: the one tab, as before M6g (the `library` screen's Meiro tab, the `empty` screen's single tab). */
    @Test fun signedOutHasNoYtmTab() {
        rule.setContent { MeiroTheme { LibraryScreen(MockLibraryRows, {}, {}, ytmTab = false, ytmSelected = true, ytmRows = MockYtmRows) } }
        rule.onNodeWithTag("tabs.ytm", useUnmergedTree = true).assertDoesNotExist()
        // A YouTube Music tab left selected falls back to the Meiro lists once signed out.
        rule.onNodeWithTag("row1.title", useUnmergedTree = true).assertTextEquals("Daily mix")
        rule.onNodeWithTag("tabs.meiro.indicator", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("fab", useUnmergedTree = true).assertExists()
    }

    @Test fun emptyLibraryShowsTheYtmTabWhenSignedIn() {
        var tapped = false
        rule.setContent { MeiroTheme { LibraryEmptyScreen(ytmTab = true, onTab = { tapped = it }) } }
        rule.onNodeWithTag("tabs.ytm.label", useUnmergedTree = true).assertTextEquals("YouTube Music")
        rule.onNodeWithTag("tabs.ytm", useUnmergedTree = true).performClick()
        assertTrue(tapped)
    }

    /** Signed out, Home is as before: no "Beğenilenler" tile and no YouTube Music shelf. */
    @Test fun homeSignedOutHasNoYtmParts() {
        val meiro = listOf(MockHome.picks[0], MockHome.picks[2], MockHome.picks[3])
        val state = LazyListState()
        rule.setContent {
            MeiroTheme {
                MeiroShell(Tab.Home, {}, Bars, SampleMini) {
                    HomeScreen(MockHome.copy(picks = YtmMapping.picks(meiro, null), releases = emptyList()), true, HomeActions(), state)
                }
            }
        }
        rule.onNodeWithTag("pick2.title", useUnmergedTree = true).assertTextEquals("Simülasyon")
        rule.runOnIdle { runBlocking { state.scrollBy(2000f) } }
        rule.onNodeWithTag("fresh.title", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("rel1.title", useUnmergedTree = true).assertDoesNotExist()
    }

    /** Signed in: the liked tile second, the shelf with its "Tümü" and album tiles that open their album. */
    @Test fun homeSignedInTapsReachTheirActions() {
        var pick = -1; var release = -1
        val state = LazyListState()
        rule.setContent {
            MeiroTheme {
                MeiroShell(Tab.Home, {}, Bars, SampleMini) { HomeScreen(MockHome, true, HomeActions(onPick = { pick = it }, onRelease = { release = it }), state) }
            }
        }
        rule.onNodeWithTag("pick2.title", useUnmergedTree = true).assertTextEquals("Beğenilenler")
        rule.onNodeWithTag("pick2.meta", useUnmergedTree = true).assertTextEquals("YouTube Music · 1.204")
        rule.onNodeWithTag("pick2.title", useUnmergedTree = true).performClick()
        assertEquals(1, pick)
        rule.runOnIdle { runBlocking { state.scrollBy(2000f) } }
        rule.onNodeWithTag("fresh.title", useUnmergedTree = true).assertTextEquals("YouTube Music: Yeni çıkanlar")
        rule.onNodeWithTag("rel2.title", useUnmergedTree = true).performClick()
        assertEquals(1, release)
    }

    /** A list of the account on the playlist screen: the YouTube Music logo, its kind line; loading and the error. */
    @Test fun ytmListOnThePlaylistScreen() {
        val ui = MockPlaylist.copy(kind = "YouTube Music listesi · 52 parça · 3 sa 1 dk", provider = Provider.Ytm)
        rule.setContent { MeiroTheme { PlaylistScreen(ui, false, {}, {}, {}, {}) } }
        rule.onNodeWithTag("hero.kind.label", useUnmergedTree = true).assertTextEquals("YouTube Music listesi · 52 parça · 3 sa 1 dk")
        rule.onNodeWithTag("hero.kind.logo", useUnmergedTree = true).assertExists()
    }

    @Test fun ytmListLoadingAndError() {
        var retry = 0
        val err = androidx.compose.runtime.mutableStateOf<String?>(null)
        rule.setContent { MeiroTheme { PlaylistScreen(null, false, {}, {}, {}, {}, error = err.value, onRetry = { retry++ }) } }
        rule.onNodeWithTag("hero.title", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("topbar.backBtn", useUnmergedTree = true).assertExists()
        err.value = "youtube: browse answered HTTP 500"
        rule.waitForIdle()
        rule.onNodeWithTag("empty.button", useUnmergedTree = true).performClick()
        assertEquals(1, retry)
    }
}

private const val LONG = "Bu bir ad değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978)"

/**
 * The YouTube Music tab, Home's liked tile and the new releases shelf under 150-character names, 320dp, font 1.3,
 * light and dark (structural rules of the key maps: rows, centring, no overlap, ellipsis).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6gStressTest {
    @get:Rule val rule = createComposeRule()

    private fun run(name: String, keys: String, theme: String, width: Int, font: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val fails = stressScreen(rule, "$name-${width}dp-$font", keys, theme, width, font, content)
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    private val longLib = YtmLibrary(
        YtmListEntry("LM", LONG, count = 123_456),
        listOf(YtmListEntry("PL1", LONG, count = 99_999), YtmListEntry("PL2", "A")),
        listOf(YtmListEntry("MP1", LONG, album = true, artist = LONG)),
    )
    private fun library() = @androidx.compose.runtime.Composable {
        MeiroShell(Tab.Library, {}, Bars, SampleMini) { LibraryScreen(MockLibraryRows, {}, {}, ytmTab = true, ytmSelected = true, ytmRows = YtmMapping.libraryRows(longLib)) }
    }
    private val longHome = HomeUi(
        "İyi akşamlar, Murat", "Bugün 47 dakika dinledin",
        picks = YtmMapping.picks(listOf(TileData("g", LONG, "Meiro listesi · 28", CoverSpec(null, 280, 80), Provider.Meiro)), YtmListEntry("LM", "x", count = 123_456)),
        releases = listOf(YtmMapping.releaseTile(MusicItem(kind = com.muratkirazkaya.meiro.youtube.ItemKind.MusicItem, browseId = "MPREb_l", title = LONG, subtitle = "Album • $LONG"))),
    )
    private fun home() = @androidx.compose.runtime.Composable { MeiroShell(Tab.Home, {}, Bars, SampleMini) { HomeScreen(longHome, true, HomeActions()) } }

    @Test fun libraryLight370() = run("library-ytm-long", "library-ytm", "light", 370, 1f, library())
    @Test fun libraryDark370() = run("library-ytm-long", "library-ytm", "dark", 370, 1f, library())
    @Test fun libraryLight320Font13() = run("library-ytm-long", "library-ytm", "light", 320, 1.3f, library())
    @Test fun libraryDark320Font13() = run("library-ytm-long", "library-ytm", "dark", 320, 1.3f, library())
    @Test fun homeLight370() = run("home-ytm-long", "home", "light", 370, 1f, home())
    @Test fun homeDark370() = run("home-ytm-long", "home", "dark", 370, 1f, home())
    @Test fun homeLight320Font13() = run("home-ytm-long", "home", "light", 320, 1.3f, home())
    @Test fun homeDark320Font13() = run("home-ytm-long", "home", "dark", 320, 1.3f, home())
}
