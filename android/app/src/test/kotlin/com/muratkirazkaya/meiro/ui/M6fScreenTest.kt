package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.SampleMini
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.SettingsActions
import com.muratkirazkaya.meiro.ui.screens.SettingsScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.screens.YtmLoginScreen
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import com.muratkirazkaya.meiro.youtube.YtmAccountDetails
import com.muratkirazkaya.meiro.youtube.YtmSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val Bars = SystemBars(36.dp, 16.dp)

/** A made-up signed-in session (no real account data); the mock's account is "Murat K." on its own channel. */
private val FakeSession = YtmSession("PREF=x; SAPISID=fakeSapisid0123; SID=fakeSid", YtmAccountDetails(name = "Murat K.", email = "test-user@example.com"))

/** M6f: the settings' YouTube Music line and the sign-in snack against the mock's own text. */
class M6fMappingTest {
    @Test fun signedInLineIsTheMocks() {
        assertEquals("Girişli · Murat K. · kanal: kişisel" to "Çıkış", AccountMapping.ytmLine(FakeSession))
    }

    @Test fun signedOutLineIsTheMocks() {
        assertEquals("Giriş yok. Arama ve çalma hesapsız çalışır." to "Giriş", AccountMapping.ytmLine(null))
    }

    @Test fun anAccountWithoutANameStillSaysSignedIn() {
        assertEquals("Girişli · kanal: kişisel", AccountMapping.ytmLine(YtmSession("SAPISID=x", YtmAccountDetails(channelId = "UC-x"))).first)
    }

    @Test fun snacksAreTheMocks() {
        assertEquals("YouTube Music'e girildi · Murat K.", AccountMapping.ytmSignedIn(FakeSession))
        assertEquals("YouTube Music'e girildi", AccountMapping.ytmSignedIn(YtmSession("SAPISID=x", YtmAccountDetails(channelId = "UC-x"))))
    }
}

/**
 * Mock screen `ytmlogin` (light, dark) and `settings` with the YouTube Music row from the app's own mapping of a fake
 * signed-in session; the sign-in screen's and the row's controls.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6fScreenTest {
    @get:Rule val rule = createComposeRule()

    /** The mock reaches `ytmlogin` with Ana sayfa selected; Google's page is a WebView in the app, empty here. */
    private fun ytmlogin(theme: String) = checkScreen(rule, "ytmlogin", theme) {
        MeiroShell(Tab.Home, {}, Bars, SampleMini) { YtmLoginScreen("accounts.google.com", {}) {} }
    }

    @Test fun ytmloginLight() = ytmlogin("light")
    @Test fun ytmloginDark() = ytmlogin("dark")

    /** The settings screen, its YouTube Music row from [AccountMapping.ytmLine] of a fake session, against the mock. */
    private fun settingsSignedIn(theme: String) = checkScreen(rule, "settings", theme, variant = "-ytm") {
        val (sub, button) = AccountMapping.ytmLine(FakeSession)
        MeiroShell(Tab.Downloads, {}, Bars, SampleMini) { SettingsScreen(MockSettings.copy(ytmSub = sub, ytmButton = button), state = rememberLazyListState()) }
    }

    @Test fun settingsSignedInLight() = settingsSignedIn("light")
    @Test fun settingsSignedInDark() = settingsSignedIn("dark")

    @Test fun backAndAddress() {
        var back = 0
        rule.setContent { MeiroTheme { Box(Modifier.fillMaxSize()) { YtmLoginScreen("music.youtube.com", { back++ }) {} } } }
        rule.onNodeWithTag("web.url.label", useUnmergedTree = true).assertTextEquals("music.youtube.com")
        rule.onNodeWithTag("topbar.backBtn", useUnmergedTree = true).performClick()
        assertEquals(1, back)
    }

    @Test fun signedOutRowAndButtonOpenTheSignIn() {
        val calls = mutableListOf<String>()
        val (sub, button) = AccountMapping.ytmLine(null)
        rule.setContent {
            MeiroTheme {
                Box(Modifier.fillMaxSize()) {
                    SettingsScreen(MockSettings.copy(ytmSub = sub, ytmButton = button), SettingsActions(onYtm = { calls += "button" }, onYtmRow = { calls += "row" }), LazyListState())
                }
            }
        }
        rule.onNodeWithTag("ytm.btn.label", useUnmergedTree = true).assertTextEquals("Giriş")
        rule.onNodeWithTag("ytm.sub", useUnmergedTree = true).assertTextEquals(sub)
        rule.onNodeWithTag("ytm.btn", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("ytm.title", useUnmergedTree = true).performClick()
        assertEquals(listOf("button", "row"), calls)
    }

    @Test fun signedInRowOnlySignsOutFromItsButton() {
        val calls = mutableListOf<String>()
        val (sub, button) = AccountMapping.ytmLine(FakeSession)
        rule.setContent {
            MeiroTheme {
                Box(Modifier.fillMaxSize()) {
                    SettingsScreen(MockSettings.copy(ytmSub = sub, ytmButton = button), SettingsActions(onYtm = { calls += "signout" }, onYtmRow = null), LazyListState())
                }
            }
        }
        rule.onNodeWithTag("ytm.btn.label", useUnmergedTree = true).assertTextEquals("Çıkış")
        rule.onNodeWithTag("ytm.title", useUnmergedTree = true).performClick()
        rule.onNodeWithTag("ytm.btn", useUnmergedTree = true).performClick()
        assertEquals(listOf("signout"), calls)
    }
}

private const val LONG = "Bu bir ad değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978)"

/** The sign-in screen and the settings row under long text, 320dp, font 1.3, light and dark (structural rules only). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6fStressTest {
    @get:Rule val rule = createComposeRule()

    private fun run(name: String, keys: String, theme: String, width: Int, font: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val fails = stressScreen(rule, "$name-${width}dp-$font", keys, theme, width, font, content)
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    private val longHost = "bir-cok-uzun-alt-alan-adi-" + "x".repeat(120) + ".accounts.google.com"
    private fun login(host: String) = @androidx.compose.runtime.Composable { MeiroShell(Tab.Home, {}, Bars, SampleMini) { YtmLoginScreen(host, {}) {} } }
    private val longSettings = AccountMapping.ytmLine(YtmSession("SAPISID=x", YtmAccountDetails(name = LONG))).let { (sub, b) -> MockSettings.copy(ytmSub = sub, ytmButton = b) }
    private fun settings() = @androidx.compose.runtime.Composable { MeiroShell(Tab.Home, {}, Bars, SampleMini) { SettingsScreen(longSettings) } }

    @Test fun loginLight370() = run("ytmlogin-long", "ytmlogin", "light", 370, 1f, login(longHost))
    @Test fun loginDark370() = run("ytmlogin-long", "ytmlogin", "dark", 370, 1f, login(longHost))
    @Test fun loginLight320Font13() = run("ytmlogin-long", "ytmlogin", "light", 320, 1.3f, login(longHost))
    @Test fun loginDark320Font13() = run("ytmlogin-long", "ytmlogin", "dark", 320, 1.3f, login(longHost))
    @Test fun loginShortHostDark320Font13() = run("ytmlogin-short", "ytmlogin", "dark", 320, 1.3f, login("g.co"))
    @Test fun settingsLongNameLight370() = run("settings-ytm-long", "settings", "light", 370, 1f, settings())
    @Test fun settingsLongNameDark320Font13() = run("settings-ytm-long", "settings", "dark", 320, 1.3f, settings())
    @Test fun settingsSignedOutLight320Font13() = run("settings-ytm-out", "settings", "light", 320, 1.3f) {
        MeiroShell(Tab.Home, {}, Bars, SampleMini) { SettingsScreen(AccountMapping.ytmLine(null).let { (s, b) -> MockSettings.copy(ytmSub = s, ytmButton = b) }) }
    }
}
