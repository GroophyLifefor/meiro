package com.muratkirazkaya.meiro.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.MiniPlayer
import com.muratkirazkaya.meiro.ui.components.MiniState
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.HomeLoadingScreen
import com.muratkirazkaya.meiro.ui.screens.LibraryEmptyScreen
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.NowPlayingScreen
import com.muratkirazkaya.meiro.ui.screens.NowPlayingUi
import com.muratkirazkaya.meiro.ui.screens.NpLink
import com.muratkirazkaya.meiro.ui.screens.SearchActions
import com.muratkirazkaya.meiro.ui.screens.SearchScreen
import com.muratkirazkaya.meiro.ui.screens.SearchUi
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import com.muratkirazkaya.meiro.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val Bars = SystemBars(36.dp, 16.dp)

/** The mock's `error` (`V.error`): "hidra" searched, YouTube Music did not answer, SoundCloud's one row below. */
val MockSearchError = SearchUi(
    query = "hidra", results = true, ytmError = SearchMapping.YTM_ERROR,
    songs = listOf(SongRowData("s7", "Gece Yarısı (canlı)", "Anıl Kaya · 3:41", ytm = false, sc = true, activeYtm = false, cover = CoverSpec(null, 220), link = LinkBadge.Unknown)),
)

/** The mock's `playerr`: "Yolla" did not play, nothing after it; the reason in the artist's place, the bar empty. */
val MockMiniError = MiniState("Yolla", "Tarkan", CoverSpec(null, 270), listOf(Provider.Ytm), Provider.Ytm, 0f, playing = false, error = "Bu şarkı artık yok.")

/** Mock screens `loading`, `error` and `playerr` (M6c), light and dark; `lock` is System UI (see mock-keys/lock.json). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6cScreenTest {
    @get:Rule val rule = createComposeRule()

    /** The mock reaches `loading` with Ara selected (left from the screen before it); the app's skeleton is Home's. */
    private fun loading(theme: String) = checkScreen(rule, "loading", theme) {
        MeiroShell(Tab.Search, {}, Bars, SampleMini) { HomeLoadingScreen(animate = false) }
    }
    @Test fun loadingLight() = loading("light")
    @Test fun loadingDark() = loading("dark")

    private fun error(theme: String) = checkScreen(rule, "error", theme) {
        MeiroShell(Tab.Search, {}, Bars, SampleMini) { SearchScreen(MockSearchError, true, SearchActions()) }
    }
    @Test fun errorLight() = error("light")
    @Test fun errorDark() = error("dark")

    private fun playerr(theme: String) = checkScreen(rule, "playerr", theme) {
        MeiroShell(Tab.Library, {}, Bars, MockMiniError) { LibraryEmptyScreen() }
    }
    @Test fun playerrLight() = playerr("light")
    @Test fun playerrDark() = playerr("dark")

    @Test fun tekrarDeneAsksAgainAndTheRowsStayUsable() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                SearchScreen(MockSearchError, false, SearchActions(onRetry = { calls += "retry" }, onSong = { calls += "song $it" }, onSongMore = { calls += "more $it" }, onSongLink = { calls += "link $it" }))
            }
        }
        rule.onNodeWithTag("empty.title", useUnmergedTree = true).assertTextEquals("YouTube Music cevap vermedi")
        rule.onNodeWithTag("empty.text", useUnmergedTree = true).assertTextEquals("SoundCloud sonuçları aşağıda. Biraz sonra tekrar denenecek.")
        for (t in listOf("empty.button", "song1.title", "song1.moreBtn", "song1.link")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("retry", "song 0", "more 0", "link 0"), calls)
        // The chips come from YouTube Music's answer: there are none while it failed (as in the mock).
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("chip.all"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun theMiniPlayerOfAFailedTrackShowsWhyAndPlayRetries() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { MiniPlayer(MockMiniError, onPlayPause = { calls += "play" }, onNext = { calls += "next" }) } }
        rule.onNodeWithTag("mini.err", useUnmergedTree = true).assertTextEquals("Bu şarkı artık yok.")
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("mini.artist.label"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithTag("mini.playBtn", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("mini.nextBtn", useUnmergedTree = true).performClick()
        assertEquals(listOf("play", "next"), calls)
    }
}

private const val LONG = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978)"
private const val LONG_REASON = "Bu şarkı çalınamadı: ERROR: [youtube] dQw4w9WgXcQ: Sign in to confirm your age. This video may be inappropriate for some users, and a very long tail of words"

/** The M6c states under long text, 320dp, font 1.3, light and dark (structural rules only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6cStressTest {
    @get:Rule val rule = createComposeRule()

    private fun run(name: String, keys: String, theme: String, width: Int, font: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val fails = stressScreen(rule, "$name-${width}dp-$font", keys, theme, width, font, content)
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    private val longError = MockSearchError.copy(
        query = LONG, ytmError = SearchMapping.YTM_ERROR,
        songs = (1..8).map { SongRowData("k$it", LONG, "$LONG · 59:59", ytm = false, sc = true, activeYtm = false, cover = CoverSpec(null, it * 40), link = LinkBadge.Unknown, preview = it == 3) },
    )
    private val longMini = MockMiniError.copy(title = LONG, error = LONG_REASON)

    @Test fun loadingDark320Font13() = run("loading", "loading", "dark", 320, 1.3f) { MeiroShell(Tab.Home, {}, Bars, SampleMini) { HomeLoadingScreen(animate = false) } }
    @Test fun loadingLight370() = run("loading", "loading", "light", 370, 1f) { MeiroShell(Tab.Home, {}, Bars, SampleMini) { HomeLoadingScreen(animate = false) } }
    @Test fun errorLongLight370() = run("error-long", "error", "light", 370, 1f) { MeiroShell(Tab.Search, {}, Bars, SampleMini) { SearchScreen(longError, true, SearchActions()) } }
    @Test fun errorLongDark320Font13() = run("error-long", "error", "dark", 320, 1.3f) { MeiroShell(Tab.Search, {}, Bars, SampleMini) { SearchScreen(longError, true, SearchActions()) } }
    @Test fun errorNoScDark320Font13() = run("error-nosc", "error", "dark", 320, 1.3f) {
        MeiroShell(Tab.Search, {}, Bars, SampleMini) { SearchScreen(MockSearchError.copy(ytmError = SearchMapping.YTM_ERROR_NO_SC, songs = emptyList()), true, SearchActions()) }
    }
    @Test fun playerrLongLight370() = run("playerr-long", "playerr", "light", 370, 1f) { MeiroShell(Tab.Library, {}, Bars, longMini) { LibraryEmptyScreen() } }
    @Test fun playerrLongDark320Font13() = run("playerr-long", "playerr", "dark", 320, 1.3f) { MeiroShell(Tab.Library, {}, Bars, longMini) { LibraryEmptyScreen() } }

    /** The full player of a failed track: the whole reason in the artist's place, wrapping (the mock's `.np .info .a`). */
    @Test fun fullPlayerErrorDark320Font13() = run("np-error", "np", "dark", 320, 1.3f) {
        NowPlayingScreen(
            NowPlayingUi(LONG, LONG_REASON, "Gece Sürüşü", CoverSpec(null, 270), NpLink.Unknown, false, true, false, Provider.Ytm, 0, 245_000, false, false, false, RepeatMode.Off),
            Bars,
        )
    }
}
