package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.LinkRowUi
import com.muratkirazkaya.meiro.ui.screens.LinkSheet
import com.muratkirazkaya.meiro.ui.screens.LinkSheetActions
import com.muratkirazkaya.meiro.ui.screens.LinkSheetUi
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.PlaylistScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
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

/** The mock's link sheet for s7 ("Gece Yarısı (canlı)", Anıl Kaya) with its three MusicBrainz results, the first chosen. */
val MockLink = LinkSheetUi(
    "MusicBrainz kaydı",
    "\"Gece Yarısı (canlı)\" · Anıl Kaya. Popülerlik ve benzerliğe göre sıralı, doğru olanı seç.",
    "Gece Yarısı (canlı) Anıl Kaya",
    listOf(
        LinkRowUi("Gece Yarısı (live)", "Kayıt · Anıl Kaya · 3:40 · 2019", "%97", CoverSpec(null, 220)),
        LinkRowUi("Gece Yarısı", "Kayıt · Anıl Kaya · 3:38 · 2017", "%93", CoverSpec(null, 225)),
        LinkRowUi("Gece Yarısı", "Kayıt · Ece Kaya · 4:02", "%61", CoverSpec(null, 40)),
    ),
)

/** Mock screen `link`: the sheet over the playlist page (the mock opens it there with Kütüphane selected), light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class LinkScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun link(theme: String) = checkScreen(rule, "link", theme) {
        Box(Modifier.fillMaxSize()) {
            MeiroShell(Tab.Library, {}, Bars, SampleMini) { PlaylistScreen(MockPlaylist, true, {}, {}, {}, {}) }
            LinkSheet(MockLink, Bars)
        }
    }

    @Test fun linkLight() = link("light")
    @Test fun linkDark() = link("dark")

    /** A row chooses it; "Bağla", "Hiçbiri" and × call their actions; the scrim closes. */
    @Test fun actions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                LinkSheet(
                    MockLink, Bars,
                    LinkSheetActions(onClose = { calls += "close" }, onSelect = { calls += "sel$it" }, onOther = { calls += "other" }, onLink = { calls += "link" }),
                )
            }
        }
        for (t in listOf("link.res3", "link.other", "link.ok", "link.closeBtn", "link.title")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("sel2", "other", "link", "close"), calls)
    }

    /** The rows' link buttons (unknown and "other") and the search's artist link button open the link sheet. */
    @Test fun linkButtonsCallTheirAction() {
        val calls = mutableListOf<String>()
        val row = MockPlaylist.rows.first().copy(link = com.muratkirazkaya.meiro.ui.components.LinkBadge.Other)
        rule.setContent {
            MeiroTheme {
                androidx.compose.foundation.layout.Column {
                    com.muratkirazkaya.meiro.ui.components.SongRow("song1", row, false, { calls += "play" }, onLink = { calls += "song" })
                    com.muratkirazkaya.meiro.ui.components.ArtistRow(
                        "artist1", com.muratkirazkaya.meiro.ui.components.ArtistRowData("k", "Daft Punk", "YouTube Music sanatçısı", CoverSpec(null, 1), link = true),
                        { calls += "open" }, { calls += "artist" },
                    )
                }
            }
        }
        rule.onNodeWithTag("song1.link", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("artist1.link", useUnmergedTree = true).performClick()
        assertEquals(listOf("song", "artist"), calls)
    }

    @Test fun onlyTheChosenRowHasTheDot() {
        rule.setContent { MeiroTheme { LinkSheet(MockLink.copy(selected = 1), Bars) } }
        rule.onNodeWithTag("link.res1.dot", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("link.res2.dot", useUnmergedTree = true).assertExists()
    }

    @Test fun loadingAndMessageReplaceTheResults() {
        val ui = androidx.compose.runtime.mutableStateOf(MockLink.copy(rows = emptyList(), loading = true))
        rule.setContent { MeiroTheme { LinkSheet(ui.value, Bars) } }
        rule.onNodeWithTag("link.spinner", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("link.res1", useUnmergedTree = true).assertDoesNotExist()
        ui.value = MockLink.copy(rows = emptyList(), message = LinkMapping.NOTHING_FOUND)
        rule.waitForIdle()
        val text = rule.onNodeWithTag("link.message", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertEquals(LinkMapping.NOTHING_FOUND, text)
        rule.onNodeWithTag("link.ok", useUnmergedTree = true).assertExists()
    }
}

private const val LONG = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978, yeniden düzenlenmiş)"
private const val LONG_DETAIL = "Kayıt · Sanatçı Bir, Sanatçı İki, Sanatçı Üç feat. Çok Uzun İsimli Konuk Sanatçı ve Orkestrası · 1:02:03 · 1978"

/**
 * The link sheet with no mock counterpart, judged by the structural rules: six results with 150-character names and
 * long lines (the CSS wraps them), a one-letter result, the artist variant, the loading state and the message; at 370
 * and 320dp, font 1.0 and 1.3, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class LinkStressTest {
    @get:Rule val rule = createComposeRule()

    private val longRows = (1..6).map { i ->
        if (i == 2) LinkRowUi("A", "Kayıt", "%1", CoverSpec(null, 5))
        else LinkRowUi(LONG, LONG_DETAIL, "%100", CoverSpec(null, i * 50))
    }
    private val longUi = LinkSheetUi("MusicBrainz kaydı", "\"$LONG\" · Sanatçı Bir, Sanatçı İki. Popülerlik ve benzerliğe göre sıralı, doğru olanı seç.", "$LONG Sanatçı Bir", longRows, selected = 3)
    private val artistUi = LinkSheetUi("MusicBrainz sanatçısı", "\"Daft Punk\". Popülerlik ve benzerliğe göre sıralı, doğru olanı seç.", "Daft Punk",
        listOf(LinkRowUi("Daft Punk", "Sanatçı · FR · French electronic duo · 395703 dinleyici", "%100", CoverSpec(null, 1))))

    private fun run(which: String, theme: String, w: Int, fs: Float) {
        val name = "link-$which-w$w-f$fs"
        val fails = stressScreen(rule, name, "link", theme, w, fs) {
            when (which) {
                "long" -> LinkSheet(longUi, Bars)
                "artist" -> LinkSheet(artistUi, Bars)
                "loading" -> LinkSheet(longUi.copy(rows = emptyList(), loading = true), Bars)
                else -> LinkSheet(longUi.copy(rows = emptyList(), message = LinkMapping.UNAVAILABLE), Bars)
            }
        }
        assertTrue("stress $name $theme (${fails.size} failures):\n" + fails.joinToString("\n") { " - $it" }, fails.isEmpty())
    }

    @Test fun longLight370() = run("long", "light", 370, 1.0f)
    @Test fun longLight320f13() = run("long", "light", 320, 1.3f)
    @Test fun longDark320() = run("long", "dark", 320, 1.0f)
    @Test fun longDark370f13() = run("long", "dark", 370, 1.3f)
    @Test fun artistLight320f13() = run("artist", "light", 320, 1.3f)
    @Test fun artistDark370() = run("artist", "dark", 370, 1.0f)
    @Test fun loadingLight320f13() = run("loading", "light", 320, 1.3f)
    @Test fun messageDark320f13() = run("message", "dark", 320, 1.3f)
}
