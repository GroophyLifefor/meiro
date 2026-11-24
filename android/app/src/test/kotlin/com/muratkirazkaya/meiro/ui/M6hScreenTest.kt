package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.AlbumScreen
import com.muratkirazkaya.meiro.ui.screens.DetailActions
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.NowPlayingActions
import com.muratkirazkaya.meiro.ui.screens.NowPlayingScreen
import com.muratkirazkaya.meiro.ui.screens.PlaylistScreen
import com.muratkirazkaya.meiro.ui.screens.PlaylistSortSheet
import com.muratkirazkaya.meiro.ui.screens.PlaylistToolActions
import com.muratkirazkaya.meiro.ui.screens.PlaylistTools
import com.muratkirazkaya.meiro.ui.screens.SearchActions
import com.muratkirazkaya.meiro.ui.screens.SearchScreen
import com.muratkirazkaya.meiro.ui.screens.SettingsActions
import com.muratkirazkaya.meiro.ui.screens.SettingsScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.screens.TrackMenuActions
import com.muratkirazkaya.meiro.ui.screens.TrackMenuSheet
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

/** The mock's `plsearch`: "ka" typed keeps Nightcall (Kavinsky), Gece Yarısı (Anıl Kaya), Bedroom Demo 3 (kendi kaydım), Bu Kalp… and Yolla (Tarkan). */
val MockPlSearch = MockPlaylist.copy(rows = listOf(0, 3, 4, 5, 6).map { MockPlaylist.rows[it] })

/** M6h: mock screens `plsearch` and `plsort`, and the controls made to work (heart, search lists, share, sort, swatches). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6hScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun plsearch(theme: String) = checkScreen(rule, "plsearch", theme) {
        MeiroShell(Tab.Downloads, {}, Bars, SampleMini) { PlaylistScreen(MockPlSearch, true, {}, {}, {}, {}, tools = PlaylistTools(searching = true, query = "ka")) }
    }
    @Test fun plsearchLight() = plsearch("light")
    @Test fun plsearchDark() = plsearch("dark")

    private fun plsort(theme: String) = checkScreen(rule, "plsort", theme) {
        Box(Modifier.fillMaxSize()) {
            MeiroShell(Tab.Downloads, {}, Bars, SampleMini) { PlaylistScreen(MockPlaylist, true, {}, {}, {}, {}) }
            PlaylistSortSheet(PlSort(), Bars, {}, {})
        }
    }
    @Test fun plsortLight() = plsort("light")
    @Test fun plsortDark() = plsort("dark")

    @Test fun thePlaylistsSearchSortAndShareCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                PlaylistScreen(
                    MockPlSearch, false, {}, {}, {}, {},
                    tools = PlaylistTools(searching = true, query = "ka", sortLabel = "Sanatçı · azalan", share = true),
                    toolActions = PlaylistToolActions(onSearch = { calls += "search" }, onQuery = { calls += "q $it" }, onSort = { calls += "sort" }, onShare = { calls += "share" }),
                )
            }
        }
        rule.onNodeWithTag("tools.sort.label", useUnmergedTree = true).assertTextEquals("Sanatçı · azalan")
        for (t in listOf("topbar.searchBtn", "plsearch.clearBtn", "tools.sort", "hero.shareBtn")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("search", "search", "sort", "share"), calls)
    }

    @Test fun aMeiroListsShareDoesNothing() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { PlaylistScreen(MockPlaylist, false, {}, {}, {}, {}, toolActions = PlaylistToolActions(onShare = { calls += "share" })) } }
        rule.onNodeWithTag("hero.shareBtn", useUnmergedTree = true).performClick()
        assertTrue(calls.isEmpty())
    }

    @Test fun theSortSheetPicksAndShowsTheDirection() {
        val picks = mutableListOf<PlSortField>()
        rule.setContent { MeiroTheme { PlaylistSortSheet(PlSort(PlSortField.Artist, desc = true), Bars, {}) { picks += it } } }
        rule.onNodeWithTag("plsort.artist.dir", useUnmergedTree = true).assertTextEquals("azalan")
        rule.onNodeWithTag("plsort.added.dir", useUnmergedTree = true).assertDoesNotExist()
        for (t in listOf("plsort.added", "plsort.name", "plsort.artist", "plsort.length")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(PlSortField.entries.toList(), picks)
    }

    @Test fun theMenusHeartCallsItsAction() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { TrackMenuSheet(MockMenu.copy(liked = true), Bars, TrackMenuActions(onLike = { calls += "menu" })) } }
        rule.onNodeWithTag("menu.likeBtn", useUnmergedTree = true).performClick()
        assertEquals(listOf("menu"), calls)
    }

    @Test fun thePlayersHeartCallsItsAction() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { NowPlayingScreen(MockNp.copy(liked = true), Bars, NowPlayingActions(onLike = { calls += "np" })) } }
        rule.onNodeWithTag("np.likeBtn", useUnmergedTree = true).performClick()
        assertEquals(listOf("np"), calls)
    }

    @Test fun searchListRowsOpenTheirPageAndMenu() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { SearchScreen(MockSearchDone.copy(artists = emptyList(), songs = emptyList(), albums = emptyList()), false, SearchActions(onList = { calls += "open $it" }, onListMore = { calls += "menu $it" })) } }
        rule.onNodeWithTag("list1.title", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("list1.moreBtn", useUnmergedTree = true).performClick()
        assertEquals(listOf("open 0", "menu 0"), calls)
    }

    @Test fun theAlbumsShareCallsItsAction() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { AlbumScreen(MockAlbum, false, DetailActions(onShare = { calls += "share" })) } }
        rule.onNodeWithTag("hero.shareBtn", useUnmergedTree = true).performClick()
        assertEquals(listOf("share"), calls)
    }

    @Test fun swatchesAndTheWallpaperSwitchCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { SettingsScreen(MockSettings.copy(wallpaper = true, swatch = -1), SettingsActions(onWallpaper = { calls += "wall" }, onSwatch = { calls += "swatch $it" })) } }
        rule.onNodeWithTag("wallpaper.switch", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("swatch3", useUnmergedTree = true).performClick()
        assertEquals(listOf("wall", "swatch 2"), calls)
    }
}

private const val LONG = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978)"

/** M6h stress: the playlist's search bar and order with 150-char names and query, the sort sheet; 370/320dp, font 1.0/1.3, light+dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6hStressTest {
    @get:Rule val rule = createComposeRule()

    private fun run(name: String, keys: String, theme: String, width: Int, font: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val fails = stressScreen(rule, "$name-${width}dp-$font", keys, theme, width, font, content)
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    private val longList = MockPlSearch.copy(
        title = LONG, kind = "Meiro listesi · 123.456 parça · 1234 sa 59 dk", summary = "YT 123456 · SC 123456 · eşleşen 123456",
        rows = MockPlSearch.rows.map { it.copy(title = LONG, subtitle = "$LONG · 59:59") },
    )
    private fun search() = @androidx.compose.runtime.Composable {
        MeiroShell(Tab.Library, {}, Bars, SampleMini) { PlaylistScreen(longList, true, {}, {}, {}, {}, tools = PlaylistTools(searching = true, query = LONG, sortLabel = "Ekleme sırası · azalan", share = true)) }
    }
    private fun sort() = @androidx.compose.runtime.Composable {
        Box(Modifier.fillMaxSize()) {
            MeiroShell(Tab.Library, {}, Bars, SampleMini) { PlaylistScreen(longList, true, {}, {}, {}, {}) }
            PlaylistSortSheet(PlSort(PlSortField.Original, desc = true), Bars, {}, {})
        }
    }

    @Test fun searchLight370() = run("plsearch-long", "plsearch", "light", 370, 1f, search())
    @Test fun searchDark370() = run("plsearch-long", "plsearch", "dark", 370, 1f, search())
    @Test fun searchLight320Font13() = run("plsearch-long", "plsearch", "light", 320, 1.3f, search())
    @Test fun searchDark320Font13() = run("plsearch-long", "plsearch", "dark", 320, 1.3f, search())
    @Test fun sortLight370() = run("plsort-long", "plsort", "light", 370, 1f, sort())
    @Test fun sortDark370() = run("plsort-long", "plsort", "dark", 370, 1f, sort())
    @Test fun sortLight320Font13() = run("plsort-long", "plsort", "light", 320, 1.3f, sort())
    @Test fun sortDark320Font13() = run("plsort-long", "plsort", "dark", 320, 1.3f, sort())
}
