package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.playback.RepeatMode
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.MiniPlayer
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.screens.NowPlayingActions
import com.muratkirazkaya.meiro.ui.screens.NowPlayingScreen
import com.muratkirazkaya.meiro.ui.screens.NowPlayingUi
import com.muratkirazkaya.meiro.ui.screens.NpLink
import com.muratkirazkaya.meiro.ui.screens.PlayerTab
import com.muratkirazkaya.meiro.ui.screens.QueueRow
import com.muratkirazkaya.meiro.ui.screens.SuggestionKind
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.screens.UpNextActions
import com.muratkirazkaya.meiro.ui.screens.UpNextSheet
import com.muratkirazkaya.meiro.ui.screens.UpNextUi
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

/** The mock's `renderNP` for s10 ("Bu Kalp Seni Unutur mu", liked, matched, YT + SC, 1:40 of 4:22, from Gece Sürüşü). */
val MockNp = NowPlayingUi(
    title = "Bu Kalp Seni Unutur mu", artist = "Fikret Kızılok", context = "Gece Sürüşü", cover = CoverSpec(null, 10),
    link = NpLink.Matched, liked = true, ytm = true, sc = true, active = Provider.Ytm,
    positionMs = 100_000, durationMs = 262_000, playing = true, preparing = false, shuffle = false, repeat = RepeatMode.Off,
)

/** The mock's `SHEET.qsheet` up-next tab: s10 playing, s9, s5, then a Meiro suggestion (s11) and two YouTube Music tracks. */
val MockUpNext = UpNextUi(
    autoPlay = true,
    sourceLine = "Gece Sürüşü'nden çalıyor · 2 sırada",
    rows = listOf(
        QueueRow.Song(0, SongRowData("s10", "Bu Kalp Seni Unutur mu", "Fikret Kızılok · 4:22", true, true, true, CoverSpec(null, 10), now = true)),
        QueueRow.Song(1, SongRowData("s9", "Son Arzum", "Nilüfer · 3:47", true, false, true, CoverSpec(null, 24), downloaded = true, link = LinkBadge.Unknown)),
        QueueRow.Song(2, SongRowData("s5", "Stolen Dance", "Milky Chance · 5:13", false, true, false, CoverSpec(null, 150), downloaded = true)),
        QueueRow.Label(SuggestionKind.Meiro),
        QueueRow.Song(3, SongRowData("s11", "Cesaretin Var mı Aşka", "Gülay · 3:35", true, false, true, CoverSpec(null, 170), link = LinkBadge.Unknown, why = "Fikret Kızılok dinleyenler de dinliyor")),
        QueueRow.Label(SuggestionKind.Ytm),
        QueueRow.Song(4, SongRowData("s12", "Yolla", "Tarkan · 4:05", true, false, true, CoverSpec(null, 270))),
        QueueRow.Song(5, SongRowData("s6", "After Dark", "Mr.Kitty · 4:19", true, false, true, CoverSpec(null, 330))),
    ),
)

/** Mock screens `np` (the full player) and `np-prep` (the same while the audio is found), light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class NowPlayingScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = checkScreen(rule, "np", "light") { NowPlayingScreen(MockNp, Bars) }
    @Test fun dark() = checkScreen(rule, "np", "dark") { NowPlayingScreen(MockNp, Bars) }
    @Test fun prepLight() = checkScreen(rule, "np-prep", "light") { NowPlayingScreen(MockNp.copy(preparing = true), Bars) }
    @Test fun prepDark() = checkScreen(rule, "np-prep", "dark") { NowPlayingScreen(MockNp.copy(preparing = true), Bars) }

    /** Close, the controls, the source buttons and the up-next tab reach their actions; ⋮, the chip and the heart do nothing. */
    @Test fun controlsCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                NowPlayingScreen(
                    MockNp, Bars,
                    NowPlayingActions(
                        onClose = { calls += "close" }, onToggle = { calls += "toggle" }, onPrev = { calls += "prev" }, onNext = { calls += "next" },
                        onShuffle = { calls += "shuffle" }, onRepeat = { calls += "repeat" }, onSource = { calls += "src:$it" },
                        onTab = { calls += "tab:$it" }, onSeek = { calls += "seek" },
                    ),
                )
            }
        }
        for (t in listOf("np.closeBtn", "np.moreBtn", "np.link", "np.likeBtn", "np.src.sc", "np.shuffleBtn", "np.prevBtn", "np.playBtn", "np.nextBtn", "np.repeatBtn", "np.tab.upnext", "np.tab.lyrics")) {
            rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        }
        assertEquals(
            listOf("close", "src:Sc", "shuffle", "prev", "toggle", "next", "repeat", "tab:${PlayerTab.UpNext}", "tab:${PlayerTab.Lyrics}"),
            calls,
        )
    }

    /** The mini player's tap opens the player; its own buttons keep their actions. */
    @Test fun miniPlayerTapOpens() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { MiniPlayer(SampleMini, onPlayPause = { calls += "pp" }, onNext = { calls += "next" }, onOpen = { calls += "open" }) } }
        rule.onNodeWithTag("mini.title", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("mini.playBtn", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("mini.nextBtn", useUnmergedTree = true).performClick()
        assertEquals(listOf("open", "pp", "next"), calls)
    }
}

/** Mock screen `upnext`: the queue sheet over the full player, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class UpNextScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun check(theme: String) = checkScreen(rule, "upnext", theme) {
        Box(Modifier.fillMaxSize()) { NowPlayingScreen(MockNp, Bars); UpNextSheet(MockUpNext, true, Bars) }
    }

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    /** A row plays its place in the queue, the switch and "Temizle" reach theirs, the scrim closes; lyrics/related tabs are inert. */
    @Test fun rowsAndControlsCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                UpNextSheet(
                    MockUpNext, true, Bars,
                    UpNextActions(onClose = { calls += "close" }, onAutoPlay = { calls += "auto:$it" }, onClear = { calls += "clear" }, onRow = { calls += "row$it" }),
                )
            }
        }
        for (t in listOf("q.auto.switch", "q.clearBtn", "q.song2.title", "q.song5.title", "q.tab.lyrics", "q.tab.related", "q.song2.moreBtn")) {
            rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        }
        assertEquals(listOf("auto:false", "clear", "row1", "row4"), calls)
    }
}

private const val TITLE_150 = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978, yeniden"
private const val ARTIST_LONG = "Sanatçı Bir, Sanatçı İki, Sanatçı Üç, Çok Uzun İsimli Konuk Sanatçı ve Orkestrası"
private const val LIST_LONG = "Çok uzun bir liste adı: yaz akşamları denize karşı dinlenecek sakin, hafif ve biraz hüzünlü şarkılar"

/**
 * The full player and the up-next sheet with no mock counterpart, by the structural rules only: 150-character title,
 * long artist and list name, a one-letter track, an hour-long track, unknown link, SoundCloud only, repeat-one and
 * shuffle on, preparing; 370 and 320dp, font 1.0 and 1.3, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class NowPlayingStressTest {
    @get:Rule val rule = createComposeRule()

    private val long = MockNp.copy(
        title = TITLE_150, artist = ARTIST_LONG, context = LIST_LONG, link = NpLink.Unknown, liked = false,
        positionMs = 3_725_000, durationMs = 3_846_000, shuffle = true, repeat = RepeatMode.Track,
    )
    private val short = MockNp.copy(title = "Ö", artist = "a", context = "", ytm = false, active = Provider.Sc, link = NpLink.Other, positionMs = 0, durationMs = 0, preparing = true)

    private val longQueue = UpNextUi(
        autoPlay = false,
        sourceLine = "${NowPlayingMapping.fromSource(LIST_LONG)} çalıyor · 128 sırada",
        rows = listOf(
            QueueRow.Song(0, SongRowData("a", TITLE_150, "$ARTIST_LONG · 1:04:06", true, true, true, CoverSpec(null, 36), link = LinkBadge.Unknown, now = true)),
            QueueRow.Song(1, SongRowData("b", "Ö", "a", false, true, false, CoverSpec(null, 36), link = LinkBadge.Other)),
            QueueRow.Label(SuggestionKind.Meiro),
            QueueRow.Song(2, SongRowData("c", TITLE_150, ARTIST_LONG, true, false, true, CoverSpec(null, 36), link = LinkBadge.Unknown, why = "Because you listen to $ARTIST_LONG")),
            QueueRow.Label(SuggestionKind.Ytm),
        ) + (3..20).map { QueueRow.Song(it, SongRowData("r$it", "Şarkı $it", "Sanatçı $it · 3:0${it % 10}", true, false, true, CoverSpec(null, it * 10))) },
    )

    private fun run(name: String, keys: String, theme: String, w: Int, fs: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val fails = stressScreen(rule, name, keys, theme, w, fs, content)
        assertTrue("stress $name $theme (${fails.size} failures):\n" + fails.joinToString("\n") { " - $it" }, fails.isEmpty())
    }

    private fun np(which: String, theme: String, w: Int, fs: Float) =
        run("np-$which-w$w-f$fs", "np", theme, w, fs) { NowPlayingScreen(if (which == "long") long else short, Bars) }

    private fun q(theme: String, w: Int, fs: Float) =
        run("upnext-long-w$w-f$fs", "upnext", theme, w, fs) { Box(Modifier.fillMaxSize()) { NowPlayingScreen(long, Bars); UpNextSheet(longQueue, true, Bars) } }

    @Test fun long_light_w370() = np("long", "light", 370, 1.0f)
    @Test fun long_light_w320() = np("long", "light", 320, 1.0f)
    @Test fun long_light_w370_f13() = np("long", "light", 370, 1.3f)
    @Test fun long_dark_w320_f13() = np("long", "dark", 320, 1.3f)
    @Test fun short_light_w320_f13() = np("short", "light", 320, 1.3f)
    @Test fun short_dark_w370() = np("short", "dark", 370, 1.0f)
    @Test fun queue_light_w370() = q("light", 370, 1.0f)
    @Test fun queue_light_w320() = q("light", 320, 1.0f)
    @Test fun queue_dark_w370_f13() = q("dark", 370, 1.3f)
    @Test fun queue_dark_w320_f13() = q("dark", 320, 1.3f)
}
