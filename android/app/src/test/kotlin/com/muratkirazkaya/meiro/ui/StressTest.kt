package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.MiniState
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.LibraryScreen
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.PlaylistScreen
import com.muratkirazkaya.meiro.ui.screens.PlaylistUi
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val LONG_NAME = "Çok uzun bir liste adı: yaz akşamları denize karşı dinlenecek sakin, hafif ve biraz hüzünlü şarkılar, 2019 - 2024 arası"
private const val LONG_TITLE = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978, yeniden düzenlenmiş)"
private const val LONG_ARTIST = "Sanatçı Bir, Sanatçı İki, Sanatçı Üç, Çok Uzun İsimli Konuk Sanatçı ve Orkestrası"
private val LongMini = MiniState(LONG_TITLE, LONG_ARTIST, CoverSpec(null, 10), listOf(Provider.Ytm, Provider.Sc), Provider.Ytm, 0.38f)

/**
 * Renders with no mock counterpart, judged only by the structural rules (rules 4 and 5: text that must not truncate does
 * not overflow, nothing leaves its container or the screen, centring and row lines hold, a title never reaches its
 * neighbour): a list with a 120-character name, a track with a 150-character title and a long artist, in both themes,
 * at 370dp, at a narrow 320dp and with font scale 1.3.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class StressTest {
    @get:Rule val rule = createComposeRule()

    private fun song(i: Int, title: String, artist: String, ytm: Boolean = true, sc: Boolean = true, link: LinkBadge = LinkBadge.None, dl: Boolean = false) =
        SongRowData("k$i", title, "$artist · 4:18", ytm, sc, ytm, CoverSpec(null, i * 40), dl, link)

    private val longRows = listOf(
        ListRowData("1", LONG_NAME, "Meiro · 1234 parça · YT 1000, SC 234", CoverSpec(null, 10, 80)),
        ListRowData("2", "Kısa", "Meiro · 3 parça", CoverSpec(null, 120, 80)),
        ListRowData("3", LONG_NAME.take(30), LONG_NAME, CoverSpec(null, 220, 80)),
    )

    private val longList = PlaylistUi(
        title = LONG_NAME,
        cover = CoverSpec(null, 280, 80),
        kind = "Meiro listesi · 1234 parça · 123 sa 59 dk",
        summary = "YT 1000 · SC 234 · eşleşen 1200",
        rows = listOf(
            song(1, LONG_TITLE, LONG_ARTIST, link = LinkBadge.Unknown, dl = true),
            song(2, "Kısa", "Ö", ytm = false, link = LinkBadge.Other),
            song(3, LONG_TITLE.take(60), LONG_ARTIST),
            song(4, "Bu Kalp Seni Unutur mu", "Fikret Kızılok").copy(now = true),
            song(5, LONG_TITLE, "Kısa sanatçı", link = LinkBadge.Unknown),
        ),
        similar = listOf(song(6, LONG_TITLE, LONG_ARTIST, link = LinkBadge.Unknown), song(7, "Get Lucky", "Daft Punk")),
    )

    /** One state per test (a test sets its content once): [which] 0 library, 1 playlist from the top, 2 playlist scrolled to the end. */
    private fun run(theme: String, tag: String, w: Int, fs: Float, which: Int) {
        val state = LazyListState()
        val name = listOf("library", "playlist", "playlist-end")[which] + "-$tag"
        val fails = stressScreen(rule, name, if (which == 2) "playlist-scrolled" else if (which == 1) "playlist" else "library", theme, w, fs) {
            if (which == 2) androidx.compose.runtime.LaunchedEffect(Unit) { state.scrollBy(100000f) }
            MeiroShell(Tab.Library, {}, SystemBars(36.dp, 16.dp), LongMini) {
                if (which == 0) LibraryScreen(longRows, {}, {}, ytmTab = true) else PlaylistScreen(longList, true, {}, {}, {}, {}, state)
            }
        }
        assertTrue("stress $name $theme (${fails.size} failures):\n" + fails.joinToString("\n") { " - $it" }, fails.isEmpty())
    }

    @Test fun library_light_w370() = run("light", "w370", 370, 1.0f, 0)
    @Test fun playlist_light_w370() = run("light", "w370", 370, 1.0f, 1)
    @Test fun playlistEnd_light_w370() = run("light", "w370", 370, 1.0f, 2)
    @Test fun library_light_w320() = run("light", "w320", 320, 1.0f, 0)
    @Test fun playlist_light_w320() = run("light", "w320", 320, 1.0f, 1)
    @Test fun playlistEnd_light_w320() = run("light", "w320", 320, 1.0f, 2)
    @Test fun library_light_w370_f1_3() = run("light", "w370-f1.3", 370, 1.3f, 0)
    @Test fun playlist_light_w370_f1_3() = run("light", "w370-f1.3", 370, 1.3f, 1)
    @Test fun playlistEnd_light_w370_f1_3() = run("light", "w370-f1.3", 370, 1.3f, 2)
    @Test fun library_light_w320_f1_3() = run("light", "w320-f1.3", 320, 1.3f, 0)
    @Test fun playlist_light_w320_f1_3() = run("light", "w320-f1.3", 320, 1.3f, 1)
    @Test fun playlistEnd_light_w320_f1_3() = run("light", "w320-f1.3", 320, 1.3f, 2)
    @Test fun library_dark_w370() = run("dark", "w370", 370, 1.0f, 0)
    @Test fun playlist_dark_w370() = run("dark", "w370", 370, 1.0f, 1)
    @Test fun playlistEnd_dark_w370() = run("dark", "w370", 370, 1.0f, 2)
    @Test fun library_dark_w320() = run("dark", "w320", 320, 1.0f, 0)
    @Test fun playlist_dark_w320() = run("dark", "w320", 320, 1.0f, 1)
    @Test fun playlistEnd_dark_w320() = run("dark", "w320", 320, 1.0f, 2)
    @Test fun library_dark_w370_f1_3() = run("dark", "w370-f1.3", 370, 1.3f, 0)
    @Test fun playlist_dark_w370_f1_3() = run("dark", "w370-f1.3", 370, 1.3f, 1)
    @Test fun playlistEnd_dark_w370_f1_3() = run("dark", "w370-f1.3", 370, 1.3f, 2)
    @Test fun library_dark_w320_f1_3() = run("dark", "w320-f1.3", 320, 1.3f, 0)
    @Test fun playlist_dark_w320_f1_3() = run("dark", "w320-f1.3", 320, 1.3f, 1)
    @Test fun playlistEnd_dark_w320_f1_3() = run("dark", "w320-f1.3", 320, 1.3f, 2)
}
