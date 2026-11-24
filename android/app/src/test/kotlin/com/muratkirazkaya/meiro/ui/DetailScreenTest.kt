package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.screens.AlbumScreen
import com.muratkirazkaya.meiro.ui.screens.AlbumUi
import com.muratkirazkaya.meiro.ui.screens.ArtistScreen
import com.muratkirazkaya.meiro.ui.screens.ArtistUi
import com.muratkirazkaya.meiro.ui.screens.DetailActions
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The mock's demo data for screen `album` ("Random Access Memories"; rows `s2` and `s1` with their numbers). */
val MockAlbum = AlbumUi(
    title = "Random Access Memories",
    cover = CoverSpec(null, 36, 80),
    kind = "Albüm · Daft Punk · 2013 · 13 parça",
    rows = listOf(
        SongRowData("s2", "Instant Crush", "Daft Punk · 5:37", true, false, true, CoverSpec(null, 36)),
        SongRowData("s1", "Get Lucky", "Daft Punk · 4:08", true, true, true, CoverSpec(null, 36)),
    ),
    nums = listOf("5", "8"),
)

/** The mock's demo data for screen `artist` ("Daft Punk", matched to MusicBrainz in the mock). */
val MockArtist = ArtistUi(
    name = "Daft Punk",
    cover = CoverSpec(null, 36, 80),
    kind = "MusicBrainz ile eşleşti · Fransa · 1993–2021",
    popular = listOf(
        SongRowData("s1", "Get Lucky", "Daft Punk, Pharrell Williams · 4:08", true, true, true, CoverSpec(null, 36)),
        SongRowData("s2", "Instant Crush", "Daft Punk, Julian Casablancas · 5:37", true, false, true, CoverSpec(null, 36)),
        // M6d: a recording with no link to play it from (the mock's `naRow`): faded, no source logos, no link button, no menu.
        SongRowData("mb:atw", "Around the World", "Daft Punk · 7:09 · çalma bağlantısı yok", false, false, true, CoverSpec(null, 160), dim = true, menu = false),
    ),
    albums = listOf(
        TileData("ram", "Random Access Memories", "2013", CoverSpec(null, 36, 80)),
        TileData("discovery", "Discovery", "2001", CoverSpec(null, 210, 80)),
        TileData("homework", "Homework", "1997", CoverSpec(null, 120, 80)),
    ),
    similar = listOf(
        TileData("justice", "Justice", "ListenBrainz", CoverSpec(null, 0, 80), Provider.Meiro),
        TileData("kavinsky", "Kavinsky", "ListenBrainz", CoverSpec(null, 300, 80), Provider.Meiro),
    ),
)

/**
 * Mock screens `album` and `artist` in both themes. As on `playlist`, the mock draws "İndirilenler" selected (it reaches
 * these pages from another tab); the app keeps the tab the page was opened from, and the comparison uses the mock's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class AlbumScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) = checkScreen(rule, "album", theme) {
        MeiroShell(Tab.Downloads, {}, SystemBars(36.dp, 16.dp), SampleMini) { AlbumScreen(MockAlbum, true, DetailActions()) }
    }

    /** The controls the album page wires: play, shuffle, a row, copy, back. */
    @Test fun controlsCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                AlbumScreen(
                    MockAlbum, true,
                    DetailActions(
                        onBack = { calls += "back" }, onPlay = { calls += "play" }, onShuffle = { calls += "shuffle" },
                        onRow = { calls += "row$it" }, onCopy = { calls += "copy" },
                    ),
                )
            }
        }
        for (t in listOf("hero.playBtn", "hero.shuffleBtn", "song2.title", "hero.copyBtn", "topbar.backBtn")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("play", "shuffle", "row1", "copy", "back"), calls)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class ArtistScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) = checkScreen(rule, "artist", theme) {
        MeiroShell(Tab.Downloads, {}, SystemBars(36.dp, 16.dp), SampleMini) { ArtistScreen(MockArtist, true, DetailActions()) }
    }

    /** Play, shuffle, a popular track, an album tile and a similar artist reach their actions; "Bağı değiştir" is inert. */
    @Test fun controlsCallTheirActions() {
        val calls = mutableListOf<String>()
        val state = LazyListState()
        rule.setContent {
            MeiroTheme {
                ArtistScreen(
                    MockArtist, true,
                    DetailActions(
                        onPlay = { calls += "play" }, onShuffle = { calls += "shuffle" }, onRow = { calls += "row$it" },
                        onAlbum = { calls += "album$it" }, onArtist = { calls += "artist$it" },
                    ),
                    state = state,
                )
            }
        }
        for (t in listOf("hero.playBtn", "hero.shuffleBtn", "hero.linkBtn", "song1.title")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        rule.runOnIdle { runBlocking { state.scrollBy(520f) } }
        for (t in listOf("album2.title", "similar1.title")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("play", "shuffle", "row0", "album1", "artist0"), calls)
    }
}

/**
 * Mock screen `artist` scrolled by [SCROLL] px: the third popular track (M6d), "Albümler" (the third tile lies right of the
 * screen in the mock too) and "Benzer sanatçılar" come into view.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class ArtistScrolledTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) {
        val state = LazyListState()
        checkScreen(
            rule, "artist", theme, keysName = "artist-scrolled", shiftY = SCROLL.toDouble(), variant = "-scrolled",
            beforeCapture = { rule.runOnIdle { runBlocking { state.scrollBy(SCROLL.toFloat()) } } },
        ) {
            MeiroShell(Tab.Downloads, {}, SystemBars(36.dp, 16.dp), SampleMini) { ArtistScreen(MockArtist, true, DetailActions(), state = state) }
        }
    }

    // 584 (520 before M6d): the mock's third popular row (64px) moved the shelves down; at 584 the scrolled and the
    // unscrolled comparison together cover 71 of the 73 elements (album3's title and meta lie right of the screen).
    companion object { const val SCROLL = 584 }
}
