package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.components.ArtistRowData
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.SearchActions
import com.muratkirazkaya.meiro.ui.screens.SearchScreen
import com.muratkirazkaya.meiro.ui.screens.SearchUi
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.muratkirazkaya.meiro.ui.screens.SearchChip
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mock's demo data for screen `search` (no search run yet: the recent searches). */
val MockSearch = SearchUi(recent = listOf("daft punk", "hidra simülasyon", "fikret kızılok"))

/** The mock's demo data for screen `search-done` ("daft punk", filter "Hepsi", both providers). */
val MockSearchDone = SearchUi(
    query = "daft punk",
    results = true,
    artists = listOf(
        ArtistRowData("mb-daft-punk", "Daft Punk", "MusicBrainz · Fransa · elektronik", CoverSpec(null, 36)),
        ArtistRowData("sc-anil-kaya", "Anıl Kaya", "yalnızca SoundCloud", CoverSpec(null, 220), Provider.Sc, link = true),
    ),
    songs = listOf(
        SongRowData("s1", "Get Lucky", "Daft Punk, Pharrell Williams · 4:08", true, true, true, CoverSpec(null, 36)),
        SongRowData("s2", "Instant Crush", "Daft Punk, Julian Casablancas · 5:37", true, false, true, CoverSpec(null, 36)),
        SongRowData("s3", "Around the World (remix)", "DJ KB · 4:06", false, true, false, CoverSpec(null, 190), link = LinkBadge.Unknown),
        SongRowData("s13", "Starboy", "The Weeknd, Daft Punk · 0:30", false, true, false, CoverSpec(null, 0), preview = true),
    ),
    albums = listOf(
        TileData("ram", "Random Access Memories", "Daft Punk · 2013", CoverSpec(null, 36, 80)),
        TileData("discovery", "Discovery", "Daft Punk · 2001", CoverSpec(null, 210, 80)),
    ),
    lists = listOf(
        ListRowData("essentials", "Daft Punk Essentials", "YouTube Music listesi", CoverSpec(null, 60, 80), Provider.Ytm),
        ListRowData("french-touch", "french touch mix", "SoundCloud seti", CoverSpec(null, 120, 80), Provider.Sc),
    ),
)

/** Mock screen `search` in both themes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class SearchScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) = checkScreen(rule, "search", theme) {
        MeiroShell(Tab.Search, {}, SystemBars(36.dp, 16.dp), SampleMini) { SearchScreen(MockSearch, true, SearchActions()) }
    }
}

/** Mock screen `search-done` in both themes; the rows below the visible area are checked by [SearchDoneScrolledTest]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class SearchDoneScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) = checkScreen(rule, "search-done", theme) {
        MeiroShell(Tab.Search, {}, SystemBars(36.dp, 16.dp), SampleMini) { SearchScreen(MockSearchDone, true, SearchActions()) }
    }

    /** The mock's fifth filter chip, "Listeler", lies right of the screen (inventory: offscreen); the chip row scrolls to it. */
    @Test fun listsChipIsReachedByScrollingTheChips() {
        var chosen: SearchChip? = null
        rule.setContent {
            MeiroTheme(darkTheme = false) {
                MeiroShell(Tab.Search, {}, SystemBars(36.dp, 16.dp), SampleMini) { SearchScreen(MockSearchDone, true, SearchActions(onChip = { chosen = it })) }
            }
        }
        rule.onNodeWithTag("chip.lists").assertDoesNotExist()
        rule.onNodeWithTag("chip.albums").performTouchInput { swipeLeft() }
        rule.onNodeWithTag("chip.lists.label", useUnmergedTree = true).assertTextEquals("Listeler")
        rule.onNodeWithTag("chip.lists").performClick()
        assertEquals(SearchChip.Lists, chosen)
    }
}

/**
 * Mock screen `search-done` scrolled to its end: the SoundCloud preview row, the "Albümler" shelf and "Listeler" come into
 * view. The inventory is read as scrolled by [SCROLL] px; elements cut by the viewport are skipped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class SearchDoneScrolledTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) {
        val state = LazyListState()
        checkScreen(
            rule, "search-done", theme, keysName = "search-done-scrolled", shiftY = SCROLL.toDouble(), variant = "-scrolled",
            beforeCapture = { rule.runOnIdle { runBlocking { state.scrollBy(SCROLL.toFloat()) } } },
        ) {
            MeiroShell(Tab.Search, {}, SystemBars(36.dp, 16.dp), SampleMini) { SearchScreen(MockSearchDone, true, SearchActions(), state) }
        }
    }

    companion object { const val SCROLL = 532 }
}
