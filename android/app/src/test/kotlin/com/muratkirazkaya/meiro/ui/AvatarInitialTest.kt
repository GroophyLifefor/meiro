package com.muratkirazkaya.meiro.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarInitialTest {
    @Test fun usesTheGreetingNamesFirstLetter() {
        assertEquals("E", avatarInitial("e2e-android-home@example.com"))
        assertEquals("M", avatarInitial("murat.kirazkaya@example.com"))
        // Turkish casing: a dotted i becomes İ, not I.
        assertEquals("İ", avatarInitial("irem@example.com"))
    }

    @Test fun withoutAnAccountItIsTheMocksM() {
        assertEquals("M", avatarInitial(""))
    }
}
