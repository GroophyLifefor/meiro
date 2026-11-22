package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.MiniState
import com.muratkirazkaya.meiro.ui.components.Provider
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val TITLE_150 = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978, yeniden"
private const val ARTIST_LONG = "Sanatçı Bir, Sanatçı İki, Sanatçı Üç, Çok Uzun İsimli Konuk Sanatçı ve Orkestrası"
private const val ALBUM_LONG = "Çok uzun bir albüm adı: yaz akşamları denize karşı dinlenecek sakin, hafif ve biraz hüzünlü şarkılar (Genişletilmiş 25. Yıl Baskısı)"

/**
 * The album and artist pages with no mock counterpart, judged by the structural rules only: a 130-character album
 * name, a long artist in the kind line, 150-character titles, three-digit track numbers, a one-letter row, long album
 * and artist tiles, the mock's short artist name (its hero buttons on a narrow screen), at 370dp, 320dp, font scale 1.3 and both, light and dark, from the top and scrolled to the end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class DetailStressTest {
    @get:Rule val rule = createComposeRule()

    private val mini = MiniState(TITLE_150, ARTIST_LONG, CoverSpec(null, 10), listOf(Provider.Ytm, Provider.Sc), Provider.Ytm, 0.38f)

    private val album = AlbumUi(
        title = ALBUM_LONG,
        cover = CoverSpec(null, 36, 80),
        kind = "Albüm · $ARTIST_LONG · 2013 · 128 parça",
        rows = listOf(
            SongRowData("a", TITLE_150, "$ARTIST_LONG · 1:04:06", true, false, true, CoverSpec(null, 36), link = LinkBadge.Unknown, now = true),
            SongRowData("b", "Ö", "a · 0:05", true, false, true, CoverSpec(null, 36), link = LinkBadge.Other),
            SongRowData("c", TITLE_150, ARTIST_LONG, true, true, true, CoverSpec(null, 36)),
        ) + (4..13).map { SongRowData("r$it", "Şarkı $it", "Daft Punk · 4:0$it", true, false, true, CoverSpec(null, 36), link = LinkBadge.Unknown) },
        // A whole album's worth of rows, so the end of the list scrolls the hero out of view.
        nums = listOf("1", "99", "128") + (4..13).map { "$it" },
    )

    private val artist = ArtistUi(
        name = ALBUM_LONG,
        cover = CoverSpec(null, 36),
        kind = "YouTube Music sanatçısı · $ARTIST_LONG",
        popular = listOf(
            SongRowData("a", TITLE_150, "$ARTIST_LONG · 1:04:06", true, true, true, CoverSpec(null, 36), link = LinkBadge.Unknown, now = true),
            SongRowData("b", "Ö", "a", true, false, true, CoverSpec(null, 36)),
        ),
        albums = listOf(TileData("x", ALBUM_LONG, "2013", CoverSpec(null, 36, 80)), TileData("y", "Ö", "1", CoverSpec(null, 90, 80))),
        similar = listOf(TileData("z", ARTIST_LONG, "YouTube Music", CoverSpec(null, 200, 80)), TileData("w", "A", "YouTube Music", CoverSpec(null, 20, 80))),
    )

    private fun run(screen: String, theme: String, tag: String, w: Int, fs: Float, end: Boolean) {
        val state = LazyListState()
        val name = "$screen${if (end) "-end" else ""}-$tag"
        val keys = if (screen == "artist" && end) "artist-scrolled" else screen.removeSuffix("-short")
        val fails = stressScreen(rule, name, keys, theme, w, fs) {
            if (end) androidx.compose.runtime.LaunchedEffect(Unit) { state.scrollBy(100000f) }
            MeiroShell(Tab.Search, {}, SystemBars(36.dp, 16.dp), mini) {
                when (screen) {
                    "album" -> AlbumScreen(album, true, DetailActions(), state = state)
                    // The mock's short name: the hero's buttons stay on the first screen, where a narrow one squeezes them.
                    "artist-short" -> ArtistScreen(MockArtist, true, DetailActions(), state = state)
                    else -> ArtistScreen(artist, true, DetailActions(), state = state)
                }
            }
        }
        assertTrue("stress $name $theme (${fails.size} failures):\n" + fails.joinToString("\n") { " - $it" }, fails.isEmpty())
    }

    @Test fun album_top_light_w370() = run("album", "light", "w370", 370, 1.0f, false)
    @Test fun album_top_light_w320() = run("album", "light", "w320", 320, 1.0f, false)
    @Test fun album_top_light_w370_f1_3() = run("album", "light", "w370-f1.3", 370, 1.3f, false)
    @Test fun album_top_light_w320_f1_3() = run("album", "light", "w320-f1.3", 320, 1.3f, false)
    @Test fun album_top_dark_w370() = run("album", "dark", "w370", 370, 1.0f, false)
    @Test fun album_top_dark_w320() = run("album", "dark", "w320", 320, 1.0f, false)
    @Test fun album_top_dark_w370_f1_3() = run("album", "dark", "w370-f1.3", 370, 1.3f, false)
    @Test fun album_top_dark_w320_f1_3() = run("album", "dark", "w320-f1.3", 320, 1.3f, false)
    @Test fun album_end_light_w370() = run("album", "light", "w370", 370, 1.0f, true)
    @Test fun album_end_light_w320() = run("album", "light", "w320", 320, 1.0f, true)
    @Test fun album_end_light_w370_f1_3() = run("album", "light", "w370-f1.3", 370, 1.3f, true)
    @Test fun album_end_light_w320_f1_3() = run("album", "light", "w320-f1.3", 320, 1.3f, true)
    @Test fun album_end_dark_w370() = run("album", "dark", "w370", 370, 1.0f, true)
    @Test fun album_end_dark_w320() = run("album", "dark", "w320", 320, 1.0f, true)
    @Test fun album_end_dark_w370_f1_3() = run("album", "dark", "w370-f1.3", 370, 1.3f, true)
    @Test fun album_end_dark_w320_f1_3() = run("album", "dark", "w320-f1.3", 320, 1.3f, true)
    @Test fun artist_top_light_w370() = run("artist", "light", "w370", 370, 1.0f, false)
    @Test fun artist_top_light_w320() = run("artist", "light", "w320", 320, 1.0f, false)
    @Test fun artist_top_light_w370_f1_3() = run("artist", "light", "w370-f1.3", 370, 1.3f, false)
    @Test fun artist_top_light_w320_f1_3() = run("artist", "light", "w320-f1.3", 320, 1.3f, false)
    @Test fun artist_top_dark_w370() = run("artist", "dark", "w370", 370, 1.0f, false)
    @Test fun artist_top_dark_w320() = run("artist", "dark", "w320", 320, 1.0f, false)
    @Test fun artist_top_dark_w370_f1_3() = run("artist", "dark", "w370-f1.3", 370, 1.3f, false)
    @Test fun artist_top_dark_w320_f1_3() = run("artist", "dark", "w320-f1.3", 320, 1.3f, false)
    @Test fun artist_end_light_w370() = run("artist", "light", "w370", 370, 1.0f, true)
    @Test fun artist_end_light_w320() = run("artist", "light", "w320", 320, 1.0f, true)
    @Test fun artist_end_light_w370_f1_3() = run("artist", "light", "w370-f1.3", 370, 1.3f, true)
    @Test fun artist_end_light_w320_f1_3() = run("artist", "light", "w320-f1.3", 320, 1.3f, true)
    @Test fun artist_end_dark_w370() = run("artist", "dark", "w370", 370, 1.0f, true)
    @Test fun artist_end_dark_w320() = run("artist", "dark", "w320", 320, 1.0f, true)
    @Test fun artist_end_dark_w370_f1_3() = run("artist", "dark", "w370-f1.3", 370, 1.3f, true)
    @Test fun artist_short_light_w320_f1_3() = run("artist-short", "light", "w320-f1.3", 320, 1.3f, false)
    @Test fun artist_short_dark_w320() = run("artist-short", "dark", "w320", 320, 1.0f, false)
    @Test fun artist_end_dark_w320_f1_3() = run("artist", "dark", "w320-f1.3", 320, 1.3f, true)
}
