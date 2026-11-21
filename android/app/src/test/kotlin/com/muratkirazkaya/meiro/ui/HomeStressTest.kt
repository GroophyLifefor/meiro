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
import com.muratkirazkaya.meiro.ui.screens.HomeActions
import com.muratkirazkaya.meiro.ui.screens.HomeScreen
import com.muratkirazkaya.meiro.ui.screens.HomeUi
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.MixUi
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
private const val LIST_LONG = "Çok uzun bir liste adı: yaz akşamları denize karşı dinlenecek sakin, hafif ve biraz hüzünlü şarkılar, 2019 - 2024 arası"

/**
 * The home screen with no mock counterpart, judged by the structural rules only: a long account name in the greeting,
 * a long minutes count, a Daily mix with long artist names, 120-character list names, 150-character track titles and
 * long artists, a short one-letter row, at 370dp, 320dp, font scale 1.3 and both, light and dark; from the top and
 * scrolled to the end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class HomeStressTest {
    @get:Rule val rule = createComposeRule()

    private val mini = MiniState(TITLE_150, ARTIST_LONG, CoverSpec(null, 10), listOf(Provider.Ytm, Provider.Sc), Provider.Ytm, 0.38f)

    private val ui = HomeUi(
        greeting = "İyi akşamlar, Muhammedalikemalettinabdurrahmanoğlu",
        minutes = "Bugün 12345 dakika dinledin",
        mix = MixUi("Daily mix · 28 Eylül", "1234 şarkı", "$ARTIST_LONG, $ARTIST_LONG, Ö ve 987 yeni sanatçı", listOf(CoverSpec(null, 10))),
        picks = listOf(
            TileData("1", LIST_LONG, "Meiro listesi · 12345", CoverSpec(null, 280, 80), Provider.Meiro),
            TileData("2", "a", "Meiro listesi · 0", CoverSpec(null, 20, 80), Provider.Meiro),
            TileData("3", LIST_LONG, "Meiro listesi · 1", CoverSpec(null, 200, 80), Provider.Meiro),
        ),
        resume = listOf(
            SongRowData("s1", TITLE_150, "$ARTIST_LONG · 1:04:06", true, true, true, CoverSpec(null, 10), now = true),
            SongRowData("s2", "Ö", "a · 0:05", true, false, true, CoverSpec(null, 300), link = LinkBadge.Unknown),
            SongRowData("s3", TITLE_150, ARTIST_LONG, false, true, false, CoverSpec(null, 150), downloaded = true, link = LinkBadge.Other),
        ),
        releases = listOf(
            TileData("r1", TITLE_150, "$ARTIST_LONG · Albüm", CoverSpec(null, 36, 80)),
            TileData("r2", "Ö", "a", CoverSpec(null, 300, 80)),
        ),
    )

    private fun run(theme: String, tag: String, w: Int, fs: Float, end: Boolean) {
        val state = LazyListState()
        val name = (if (end) "home-end" else "home") + "-$tag"
        val fails = stressScreen(rule, name, if (end) "home-scrolled" else "home", theme, w, fs) {
            if (end) androidx.compose.runtime.LaunchedEffect(Unit) { state.scrollBy(100000f) }
            MeiroShell(Tab.Home, {}, SystemBars(36.dp, 16.dp), mini) { HomeScreen(ui, true, HomeActions(), state) }
        }
        assertTrue("stress $name $theme (${fails.size} failures):\n" + fails.joinToString("\n") { " - $it" }, fails.isEmpty())
    }

    @Test fun top_light_w370() = run("light", "w370", 370, 1.0f, false)
    @Test fun end_light_w370() = run("light", "w370", 370, 1.0f, true)
    @Test fun top_light_w320() = run("light", "w320", 320, 1.0f, false)
    @Test fun end_light_w320() = run("light", "w320", 320, 1.0f, true)
    @Test fun top_light_w370_f1_3() = run("light", "w370-f1.3", 370, 1.3f, false)
    @Test fun end_light_w370_f1_3() = run("light", "w370-f1.3", 370, 1.3f, true)
    @Test fun top_light_w320_f1_3() = run("light", "w320-f1.3", 320, 1.3f, false)
    @Test fun end_light_w320_f1_3() = run("light", "w320-f1.3", 320, 1.3f, true)
    @Test fun top_dark_w370() = run("dark", "w370", 370, 1.0f, false)
    @Test fun end_dark_w370() = run("dark", "w370", 370, 1.0f, true)
    @Test fun top_dark_w320() = run("dark", "w320", 320, 1.0f, false)
    @Test fun end_dark_w320() = run("dark", "w320", 320, 1.0f, true)
    @Test fun top_dark_w370_f1_3() = run("dark", "w370-f1.3", 370, 1.3f, false)
    @Test fun end_dark_w370_f1_3() = run("dark", "w370-f1.3", 370, 1.3f, true)
    @Test fun top_dark_w320_f1_3() = run("dark", "w320-f1.3", 320, 1.3f, false)
    @Test fun end_dark_w320_f1_3() = run("dark", "w320-f1.3", 320, 1.3f, true)
}
