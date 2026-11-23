package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.lyrics.FoundLyrics
import com.muratkirazkaya.meiro.lyrics.LyricLine
import com.muratkirazkaya.meiro.lyrics.LyricsState
import com.muratkirazkaya.meiro.lyrics.RelatedState
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.screens.LyricsUi
import com.muratkirazkaya.meiro.ui.screens.NowPlayingScreen
import com.muratkirazkaya.meiro.ui.screens.PlayerTab
import com.muratkirazkaya.meiro.ui.screens.RelatedUi
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.screens.UpNextActions
import com.muratkirazkaya.meiro.ui.screens.UpNextSheet
import com.muratkirazkaya.meiro.ui.screens.lyricIndexAt
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import com.muratkirazkaya.meiro.youtube.MusicItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val Bars = SystemBars(36.dp, 16.dp)

/** The mock's `LYR`, timed 5 s apart; at 12 s the third line is sung (the mock's `.on`, two `.past` above it). */
private val MockLyricLines = listOf("Gece çöktü, şehir sustu", "Farlar yanıyor yol boyunca", "Bir şarkı çalıyor radyoda", "Ve sen yine aklımdasın", "Durmadan gidiyoruz", "Sabaha kadar bu yolda")
val MockLyrics = LyricsUi(MockLyricLines, MockLyricLines.indices.map { it * 5_000L }, timed = true, source = "Kaynak: LRCLIB")
const val MockLyricsPosition = 12_000L

/** The mock's related tab: s11, s4, s3. */
val MockRelated = RelatedUi(
    listOf(
        SongRowData("s11", "Cesaretin Var mı Aşka", "Gülay · 3:35", true, false, true, CoverSpec(null, 170), link = LinkBadge.Unknown),
        SongRowData("s4", "Nightcall", "Kavinsky · 4:18", true, true, true, CoverSpec(null, 300)),
        SongRowData("s3", "Around the World (remix)", "DJ KB · 4:06", false, true, false, CoverSpec(null, 190), link = LinkBadge.Unknown),
    ),
)

/** Mock screens `lyrics` and `related`: the queue sheet on its other two tabs over the full player, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class TrackTabsScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun lyrics(theme: String) = checkScreen(rule, "lyrics", theme) {
        Box(Modifier.fillMaxSize()) {
            NowPlayingScreen(MockNp, Bars)
            UpNextSheet(MockUpNext, true, Bars, tab = PlayerTab.Lyrics, lyrics = MockLyrics, positionMs = MockLyricsPosition)
        }
    }

    private fun related(theme: String) = checkScreen(rule, "related", theme) {
        Box(Modifier.fillMaxSize()) { NowPlayingScreen(MockNp, Bars); UpNextSheet(MockUpNext, true, Bars, tab = PlayerTab.Related, related = MockRelated) }
    }

    @Test fun lyricsLight() = lyrics("light")
    @Test fun lyricsDark() = lyrics("dark")
    @Test fun relatedLight() = related("light")
    @Test fun relatedDark() = related("dark")

    /** The tabs switch, the chips choose the view, a timed line seeks to its time; ⋮ and the link button stay inert. */
    @Test fun lyricsControlsCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                UpNextSheet(
                    MockUpNext, true, Bars,
                    UpNextActions(onTab = { calls += "tab:$it" }, onLyricsSynced = { calls += "synced:$it" }, onLyricSeek = { calls += "seek:$it" }),
                    tab = PlayerTab.Lyrics, lyrics = MockLyrics, positionMs = MockLyricsPosition,
                )
            }
        }
        for (t in listOf("q.tab.upnext", "q.tab.related", "q.lyr.plain", "q.lyr.synced", "q.lyr.line4", "q.lyr.source")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("tab:UpNext", "tab:Related", "synced:false", "synced:true", "seek:15000"), calls)
    }

    @Test fun relatedRowsPlayTheirPlace() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme { UpNextSheet(MockUpNext, true, Bars, UpNextActions(onRelated = { calls += "rel$it" }, onTab = { calls += "tab:$it" }), tab = PlayerTab.Related, related = MockRelated) }
        }
        for (t in listOf("q.rel1.title", "q.rel3.cover", "q.rel1.moreBtn", "q.rel1.link", "q.rel.note", "q.tab.lyrics")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("rel0", "rel2", "tab:Lyrics"), calls)
    }

    /** "Düz": the plain lines, no line lit, nothing to tap; the chips still show (the track has timed lines). */
    @Test fun plainViewLightsNoLineAndDoesNotSeek() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                UpNextSheet(MockUpNext, true, Bars, UpNextActions(onLyricSeek = { calls += "seek" }), tab = PlayerTab.Lyrics,
                    lyrics = MockLyrics.copy(times = null), positionMs = MockLyricsPosition)
            }
        }
        rule.onNodeWithTag("q.lyr.line3", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("q.lyr.plain.icon", useUnmergedTree = true).assertExists()
        assertTrue(calls.isEmpty())
    }
}

/** The mapping from the loads to the two tabs (`NowPlayingMapping.lyrics` / `related`). */
class TrackTabsMappingTest {
    private val timed = FoundLyrics("a\nb", listOf(LyricLine(0, "a"), LyricLine(900, "b")), "Source: LRCLIB")

    @Test fun lyricsOfAnotherTrackOrLoadingShowNothing() {
        assertEquals(LyricsUi(), NowPlayingMapping.lyrics("k", null, false))
        assertEquals(LyricsUi(), NowPlayingMapping.lyrics("k", "other" to LyricsState.Found(timed), false))
        assertEquals(LyricsUi(), NowPlayingMapping.lyrics("k", "k" to LyricsState.Loading, false))
    }

    @Test fun timedLyricsAreSyncedUnlessPlainIsChosen() {
        assertEquals(LyricsUi(listOf("a", "b"), listOf(0L, 900L), true, "Kaynak: LRCLIB"), NowPlayingMapping.lyrics("k", "k" to LyricsState.Found(timed), false))
        assertEquals(LyricsUi(listOf("a", "b"), null, true, "Kaynak: LRCLIB"), NowPlayingMapping.lyrics("k", "k" to LyricsState.Found(timed), true))
        val plain = NowPlayingMapping.lyrics("k", "k" to LyricsState.Found(FoundLyrics("x\n\ny ", emptyList(), "Source: LyricFind")), false)
        assertEquals(LyricsUi(listOf("x", "", "y"), null, false, "Kaynak: LyricFind"), plain)
    }

    @Test fun messagesForNoneInstrumentalAndFailure() {
        assertEquals(NowPlayingMapping.LYRICS_NONE, NowPlayingMapping.lyrics("k", "k" to LyricsState.None(false), false).message)
        assertEquals(NowPlayingMapping.LYRICS_INSTRUMENTAL, NowPlayingMapping.lyrics("k", "k" to LyricsState.None(true), false).message)
        assertEquals(NowPlayingMapping.LYRICS_FAILED, NowPlayingMapping.lyrics("k", "k" to LyricsState.Failed, false).message)
        assertEquals(NowPlayingMapping.RELATED_NONE, NowPlayingMapping.related("k", "k" to RelatedState.None, null) { _, _ -> null }.first.message)
        assertEquals(NowPlayingMapping.RELATED_FAILED, NowPlayingMapping.related("k", "k" to RelatedState.Failed, null) { _, _ -> null }.first.message)
    }

    @Test fun relatedTracksBecomeRowsAndTheQueue() {
        val items = listOf(
            MusicItem(videoId = "v1", title = "One", artists = "A", duration = "3:01"),
            MusicItem(videoId = "v2", title = "Two", artists = "B"),
        )
        val (ui, queue) = NowPlayingMapping.related("k", "k" to RelatedState.Found(items), "v2") { _, _ -> null }
        assertNull(ui.message)
        assertEquals(listOf("One" to "A · 3:01", "Two" to "B"), ui.rows.map { it.title to it.subtitle })
        assertEquals(listOf(false, true), ui.rows.map { it.now })
        assertEquals(listOf("v1", "v2"), queue.map { it.key })
        assertEquals(LinkBadge.Unknown, ui.rows[0].link)
    }

    @Test fun lineBeingSung() {
        val t = listOf(0L, 5_000L, 10_000L)
        assertEquals(-1, lyricIndexAt(listOf(1L), 0))
        assertEquals(0, lyricIndexAt(t, 4_999))
        assertEquals(2, lyricIndexAt(t, 10_000))
    }
}

private const val LINE_150 = "Bu bir şarkı sözü değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü, canlı kayıt, 1978"
private const val TITLE_150 = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978, yeniden"

/**
 * The two tabs with no mock counterpart, by the structural rules only: 150-character lines, 80 timed lines sung deep
 * into the song, plain text with stanza breaks, a long source, the messages; 20 related tracks with long titles and
 * artists; 370 and 320dp, font 1.0 and 1.3, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class TrackTabsStressTest {
    @get:Rule val rule = createComposeRule()

    private val manyLines = (1..80).map { if (it % 7 == 0) LINE_150 else if (it % 5 == 0) "" else "Satır $it" }
    private val longTimed = LyricsUi(manyLines, manyLines.indices.map { it * 3_000L }, true, "Kaynak: Çok uzun bir sağlayıcı adı, LyricFind ve Musixmatch birlikte")
    private val plain = LyricsUi(listOf("Kısa", "", LINE_150, "Ö", "", "Son"), null, false, "Kaynak: LRCLIB")
    private val related = RelatedUi(
        (1..20).map { i ->
            SongRowData("r$i", if (i % 3 == 0) TITLE_150 else "Ö", if (i % 2 == 0) "Sanatçı Bir, Sanatçı İki, Sanatçı Üç, Çok Uzun İsimli Konuk Sanatçı · 1:04:06" else "a",
                i % 2 == 0, i % 3 != 2, i % 2 == 0, CoverSpec(null, i * 17), link = if (i % 4 == 0) LinkBadge.Other else LinkBadge.Unknown, now = i == 2)
        },
    )

    private fun run(name: String, keys: String, theme: String, w: Int, fs: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val fails = stressScreen(rule, name, keys, theme, w, fs, content)
        assertTrue("stress $name $theme (${fails.size} failures):\n" + fails.joinToString("\n") { " - $it" }, fails.isEmpty())
    }

    private fun lyr(which: String, theme: String, w: Int, fs: Float) = run("lyrics-$which-w$w-f$fs", "lyrics", theme, w, fs) {
        val ui = when (which) {
            "timed" -> longTimed
            "plain" -> plain
            else -> LyricsUi(message = com.muratkirazkaya.meiro.ui.NowPlayingMapping.LYRICS_FAILED)
        }
        Box(Modifier.fillMaxSize()) { NowPlayingScreen(MockNp, Bars); UpNextSheet(MockUpNext, true, Bars, tab = PlayerTab.Lyrics, lyrics = ui, positionMs = 150_000) }
    }

    private fun rel(which: String, theme: String, w: Int, fs: Float) = run("related-$which-w$w-f$fs", "related", theme, w, fs) {
        val ui = if (which == "long") related else RelatedUi(message = com.muratkirazkaya.meiro.ui.NowPlayingMapping.RELATED_FAILED)
        Box(Modifier.fillMaxSize()) { NowPlayingScreen(MockNp, Bars); UpNextSheet(MockUpNext, true, Bars, tab = PlayerTab.Related, related = ui) }
    }

    @Test fun timed_light_w370() = lyr("timed", "light", 370, 1.0f)
    @Test fun timed_dark_w320_f13() = lyr("timed", "dark", 320, 1.3f)
    @Test fun plain_light_w320() = lyr("plain", "light", 320, 1.0f)
    @Test fun plain_dark_w370_f13() = lyr("plain", "dark", 370, 1.3f)
    @Test fun message_light_w320_f13() = lyr("message", "light", 320, 1.3f)
    @Test fun related_light_w370() = rel("long", "light", 370, 1.0f)
    @Test fun related_light_w320() = rel("long", "light", 320, 1.0f)
    @Test fun related_dark_w370_f13() = rel("long", "dark", 370, 1.3f)
    @Test fun related_dark_w320_f13() = rel("long", "dark", 320, 1.3f)
    @Test fun related_message_dark_w320_f13() = rel("message", "dark", 320, 1.3f)
}
