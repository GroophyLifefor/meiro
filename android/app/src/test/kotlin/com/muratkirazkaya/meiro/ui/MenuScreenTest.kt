package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.AddToSheet
import com.muratkirazkaya.meiro.ui.screens.ListMenuActions
import com.muratkirazkaya.meiro.ui.screens.ListMenuSheet
import com.muratkirazkaya.meiro.ui.screens.ListMenuUi
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.PlaylistScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.screens.TrackMenuActions
import com.muratkirazkaya.meiro.ui.screens.TrackMenuSheet
import com.muratkirazkaya.meiro.ui.screens.TrackMenuUi
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

/** The mock's track menu: opened on s7 ("Gece Yarısı (canlı)", Anıl Kaya, SoundCloud only, unknown, no album). */
val MockMenu = TrackMenuUi("Gece Yarısı (canlı)", "Anıl Kaya", CoverSpec(null, 220), "unknown", listOf(Provider.Sc))

/** The mock's add-to rows. */
val MockAddTo = listOf(
    ListRowData("a", "Gece Sürüşü", "28 parça", CoverSpec(null, 280, 80)),
    ListRowData("b", "Simülasyon", "11 parça", CoverSpec(null, 200, 80)),
    ListRowData("c", "Keşifler", "37 parça", CoverSpec(null, 120, 80)),
)

/**
 * Mock screens `menu` and `addto`: the sheet over the playlist page (the mock opens both from the playlist with
 * Kütüphane selected), light and dark. The page under the scrim is occluded in the inventory and not compared here
 * (`playlist` compares it).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class MenuScreenTest {
    @get:Rule val rule = createComposeRule()

    @androidx.compose.runtime.Composable private fun page() = MeiroShell(Tab.Library, {}, Bars, SampleMini) { PlaylistScreen(MockPlaylist, true, {}, {}, {}, {}) }

    private fun menu(theme: String) = checkScreen(rule, "menu", theme) {
        Box(Modifier.fillMaxSize()) { page(); TrackMenuSheet(MockMenu, Bars) }
    }

    private fun addto(theme: String) = checkScreen(rule, "addto", theme) {
        Box(Modifier.fillMaxSize()) { page(); AddToSheet(MockAddTo, Bars, {}, {}, {}) }
    }

    @Test fun menuLight() = menu("light")
    @Test fun menuDark() = menu("dark")
    @Test fun addtoLight() = addto("light")
    @Test fun addtoDark() = addto("dark")

    /** Every item calls its own action; the scrim dismisses; a tap on the sheet's own background does nothing. */
    @Test fun trackMenuItemsCallTheirActions() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                TrackMenuSheet(
                    MockMenu.copy(album = true, inList = true), Bars,
                    TrackMenuActions(
                        onDismiss = { calls += "dismiss" }, onLike = { calls += "like" }, onPlayNext = { calls += "next" },
                        onQueue = { calls += "queue" }, onAddTo = { calls += "addto" }, onDownload = { calls += "download" },
                        onLink = { calls += "link" }, onSource = { calls += "source" }, onArtist = { calls += "artist" },
                        onAlbum = { calls += "album" }, onShare = { calls += "share" }, onNoRecommend = { calls += "noRec" },
                        onRemove = { calls += "remove" },
                    ),
                )
            }
        }
        val tags = listOf("menu.likeBtn", "menu.next", "menu.queue", "menu.addto", "menu.download", "menu.link", "menu.source", "menu.artist", "menu.album", "menu.share", "menu.noRec", "menu.remove", "menu.head.title")
        for (t in tags) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("like", "next", "queue", "addto", "download", "link", "source", "artist", "album", "share", "noRec", "remove"), calls)
    }

    /** The mock's variants: "Albüme git" only with an album, "İndirildi · kaldır" when downloaded, both logos for a merged track. */
    @Test fun trackMenuVariants() {
        rule.setContent { MeiroTheme { TrackMenuSheet(MockMenu.copy(downloaded = true, sources = listOf(Provider.Ytm, Provider.Sc), link = "eşleşti"), Bars) } }
        rule.onNodeWithTag("menu.album", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("menu.remove", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("menu.download.icon", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("menu.source.ytm", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("menu.source.sc", useUnmergedTree = true).assertExists()
        val text = { t: String -> rule.onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString { it.text } }
        assertEquals("İndirildi · kaldır", text("menu.download.label"))
        assertEquals("eşleşti", text("menu.link.state"))
    }

    @Test fun addToPicksTheListAndOffersANewOne() {
        val calls = mutableListOf<String>()
        rule.setContent { MeiroTheme { AddToSheet(MockAddTo, Bars, { calls += "close" }, { calls += "new" }, { calls += "pick:$it" }) } }
        for (t in listOf("addto.row2.title", "addto.new", "addto.closeBtn", "addto.title")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("pick:b", "new", "close"), calls)
    }

    @Test fun listMenuForOwnListAndAlbum() {
        val calls = mutableListOf<String>()
        rule.setContent {
            MeiroTheme {
                ListMenuSheet(
                    ListMenuUi(4, own = true), Bars,
                    ListMenuActions(onCopy = { calls += "copy" }, onDownload = { calls += "download" }, onRename = { calls += "rename" }, onLinkAll = { calls += "link" }, onDelete = { calls += "delete" }),
                )
            }
        }
        for (t in listOf("lmenu.copy", "lmenu.download", "lmenu.rename", "lmenu.link", "lmenu.delete")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertEquals(listOf("copy", "download", "rename", "link", "delete"), calls)
        val n = rule.onNodeWithTag("lmenu.link.state", useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
        assertEquals("4 unknown", n.joinToString { it.text })
    }

    @Test fun albumListMenuHasNoRenameOrDelete() {
        rule.setContent { MeiroTheme { ListMenuSheet(ListMenuUi(13, own = false), Bars) } }
        rule.onNodeWithTag("lmenu.copy", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("lmenu.rename", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("lmenu.delete", useUnmergedTree = true).assertDoesNotExist()
    }
}

private const val LONG_TITLE = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978, yeniden düzenlenmiş)"
private const val LONG_ARTIST = "Sanatçı Bir, Sanatçı İki, Sanatçı Üç, Çok Uzun İsimli Konuk Sanatçı ve Orkestrası"
private const val LONG_NAME = "Çok uzun bir liste adı: yaz akşamları denize karşı dinlenecek sakin, hafif ve biraz hüzünlü şarkılar, 2019 - 2024 arası"

/**
 * The menu sheets with no mock counterpart, judged by the structural rules only: the track menu of a 150-character
 * title and a long artist with every optional item (album, downloaded, both logos, "Listeden çıkar"), the add-to sheet
 * with 20 lists with long names, the list menu; at 370 and 320dp, font 1.0 and 1.3, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class MenuStressTest {
    @get:Rule val rule = createComposeRule()

    private val longMenu = TrackMenuUi(LONG_TITLE, LONG_ARTIST, CoverSpec(null, 10), "diğer", listOf(Provider.Ytm, Provider.Sc), downloaded = true, album = true, inList = true)
    private val longLists = (1..20).map { ListRowData("l$it", if (it % 2 == 0) LONG_NAME else "Liste $it", "${it * 111} parça", CoverSpec(null, it * 17, 80)) }

    private fun run(which: String, theme: String, w: Int, fs: Float) {
        val name = "$which-w$w-f$fs"
        val fails = stressScreen(rule, name, if (which == "addto") "addto" else "menu", theme, w, fs) {
            when (which) {
                "menu" -> TrackMenuSheet(longMenu, Bars)
                "addto" -> AddToSheet(longLists, Bars, {}, {}, {})
                else -> ListMenuSheet(ListMenuUi(1234, own = true), Bars)
            }
        }
        assertTrue("stress $name $theme (${fails.size} failures):\n" + fails.joinToString("\n") { " - $it" }, fails.isEmpty())
    }

    @Test fun menuLight370() = run("menu", "light", 370, 1.0f)
    @Test fun menuLight320f13() = run("menu", "light", 320, 1.3f)
    @Test fun menuDark320() = run("menu", "dark", 320, 1.0f)
    @Test fun menuDark370f13() = run("menu", "dark", 370, 1.3f)
    @Test fun addtoLight370() = run("addto", "light", 370, 1.0f)
    @Test fun addtoLight320f13() = run("addto", "light", 320, 1.3f)
    @Test fun addtoDark320() = run("addto", "dark", 320, 1.0f)
    @Test fun addtoDark370f13() = run("addto", "dark", 370, 1.3f)
    @Test fun listMenuLight320f13() = run("listmenu", "light", 320, 1.3f)
    @Test fun listMenuDark370() = run("listmenu", "dark", 370, 1.0f)
}
