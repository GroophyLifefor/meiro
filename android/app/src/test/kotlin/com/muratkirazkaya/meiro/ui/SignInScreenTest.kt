package com.muratkirazkaya.meiro.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.muratkirazkaya.meiro.ui.compare.checkScreen
import com.muratkirazkaya.meiro.ui.screens.SignInField
import com.muratkirazkaya.meiro.ui.screens.SignInScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Mock screen `signin` in both themes (see [checkScreen]), with the values the mock shows: server
 * "meiro.muratkirazkaya.com", e-mail "murat@" (focused), an eight-character password.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class SignInScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun light() = check("light")
    @Test fun dark() = check("dark")

    private fun check(theme: String) = checkScreen(rule, "signin", theme) {
        SignInScreen(
            server = "meiro.muratkirazkaya.com", email = "murat@", password = "12345678",
            onServer = {}, onEmail = {}, onPassword = {},
            focused = SignInField.Email, onFocus = { _, _ -> },
            bars = SystemBars(36.dp, 16.dp), onSubmit = {}, onSkip = {},
        )
    }
}
