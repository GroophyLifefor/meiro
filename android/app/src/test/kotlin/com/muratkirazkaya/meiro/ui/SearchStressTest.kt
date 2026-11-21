package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.ArtistRowData
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.MiniState
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.SearchActions
import com.muratkirazkaya.meiro.ui.screens.SearchScreen
import com.muratkirazkaya.meiro.ui.screens.SearchUi
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val TITLE_150 = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978, yeniden"
private const val QUERY_LONG = "daft punk random access memories get lucky pharrell williams nile rodgers radio edit 2013 canlı kayıt remix"
private const val ARTIST_LONG = "Sanatçı Bir, Sanatçı İki, Sanatçı Üç, Çok Uzun İsimli Konuk Sanatçı ve Orkestrası"

/**
 * The search screen with no mock counterpart, judged by the structural rules only: a long query in the field and in the
 * recent list, a 150-character track title, long artist, album and list names, at 370dp, 320dp, font scale 1.3 and
 * both, light and dark; the result list from the top and scrolled to its end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class SearchStressTest {
    @get:Rule val rule = createComposeRule()

    private val mini = MiniState(TITLE_150, ARTIST_LONG, CoverSpec(null, 10), listOf(Provider.Ytm, Provider.Sc), Provider.Ytm, 0.38f)

    private val recent = SearchUi(query = QUERY_LONG, recent = listOf(QUERY_LONG, "a", TITLE_150))

    private val done = SearchUi(
        query = QUERY_LONG, results = true,
        artists = listOf(
            ArtistRowData("1", ARTIST_LONG, "MusicBrainz · Amerika Birleşik Devletleri · elektronik, house, disco, funk", CoverSpec(null, 36)),
            ArtistRowData("2", ARTIST_LONG, "YouTube Music sanatçısı", CoverSpec(null, 220), Provider.Ytm, link = true),
        ),
        songs = listOf(
            SongRowData("s1", TITLE_150, "$ARTIST_LONG · 4:08", true, true, true, CoverSpec(null, 36)),
            SongRowData("s2", "Kısa", "Ö · 0:05", true, false, true, CoverSpec(null, 36)),
            SongRowData("s3", TITLE_150, "$ARTIST_LONG · 1:04:06", false, true, false, CoverSpec(null, 190), link = LinkBadge.Unknown),
            SongRowData("s4", TITLE_150, "$ARTIST_LONG · 0:30", false, true, false, CoverSpec(null, 0), link = LinkBadge.Other, preview = true),
        ),
        albums = listOf(
            TileData("a1", TITLE_150, "$ARTIST_LONG · 2013", CoverSpec(null, 36, 80)),
            TileData("a2", "Discovery", "Daft Punk · 2001", CoverSpec(null, 210, 80)),
        ),
        lists = listOf(
            ListRowData("l1", TITLE_150, "YouTube Music listesi", CoverSpec(null, 60, 80), Provider.Ytm),
            ListRowData("l2", "mix", ARTIST_LONG, CoverSpec(null, 120, 80), Provider.Sc),
        ),
    )

    /** [which]: 0 recent searches, 1 results from the top, 2 results scrolled to the end. */
    private fun run(theme: String, tag: String, w: Int, fs: Float, which: Int) {
        val state = LazyListState()
        val name = listOf("search", "search-done", "search-done-end")[which] + "-$tag"
        val keys = listOf("search", "search-done", "search-done-scrolled")[which]
        val fails = stressScreen(rule, name, keys, theme, w, fs) {
            if (which == 2) androidx.compose.runtime.LaunchedEffect(Unit) { state.scrollBy(100000f) }
            MeiroShell(Tab.Search, {}, SystemBars(36.dp, 16.dp), mini) {
                SearchScreen(if (which == 0) recent else done, true, SearchActions(), state)
            }
        }
        assertTrue("stress $name $theme (${fails.size} failures):\n" + fails.joinToString("\n") { " - $it" }, fails.isEmpty())
    }

    @Test fun recent_light_w370() = run("light", "w370", 370, 1.0f, 0)
    @Test fun done_light_w370() = run("light", "w370", 370, 1.0f, 1)
    @Test fun end_light_w370() = run("light", "w370", 370, 1.0f, 2)
    @Test fun recent_light_w320() = run("light", "w320", 320, 1.0f, 0)
    @Test fun done_light_w320() = run("light", "w320", 320, 1.0f, 1)
    @Test fun end_light_w320() = run("light", "w320", 320, 1.0f, 2)
    @Test fun recent_light_w370_f1_3() = run("light", "w370-f1.3", 370, 1.3f, 0)
    @Test fun done_light_w370_f1_3() = run("light", "w370-f1.3", 370, 1.3f, 1)
    @Test fun end_light_w370_f1_3() = run("light", "w370-f1.3", 370, 1.3f, 2)
    @Test fun recent_light_w320_f1_3() = run("light", "w320-f1.3", 320, 1.3f, 0)
    @Test fun done_light_w320_f1_3() = run("light", "w320-f1.3", 320, 1.3f, 1)
    @Test fun end_light_w320_f1_3() = run("light", "w320-f1.3", 320, 1.3f, 2)
    @Test fun recent_dark_w370() = run("dark", "w370", 370, 1.0f, 0)
    @Test fun done_dark_w370() = run("dark", "w370", 370, 1.0f, 1)
    @Test fun end_dark_w370() = run("dark", "w370", 370, 1.0f, 2)
    @Test fun recent_dark_w320() = run("dark", "w320", 320, 1.0f, 0)
    @Test fun done_dark_w320() = run("dark", "w320", 320, 1.0f, 1)
    @Test fun end_dark_w320() = run("dark", "w320", 320, 1.0f, 2)
    @Test fun recent_dark_w370_f1_3() = run("dark", "w370-f1.3", 370, 1.3f, 0)
    @Test fun done_dark_w370_f1_3() = run("dark", "w370-f1.3", 370, 1.3f, 1)
    @Test fun end_dark_w370_f1_3() = run("dark", "w370-f1.3", 370, 1.3f, 2)
    @Test fun recent_dark_w320_f1_3() = run("dark", "w320-f1.3", 320, 1.3f, 0)
    @Test fun done_dark_w320_f1_3() = run("dark", "w320-f1.3", 320, 1.3f, 1)
    @Test fun end_dark_w320_f1_3() = run("dark", "w320-f1.3", 320, 1.3f, 2)
}
