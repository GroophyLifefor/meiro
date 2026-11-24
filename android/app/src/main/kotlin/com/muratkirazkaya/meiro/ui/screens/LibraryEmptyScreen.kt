package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.muratkirazkaya.meiro.ui.components.EmptyState
import com.muratkirazkaya.meiro.ui.components.LibraryTabs
import com.muratkirazkaya.meiro.ui.components.TopBar

/**
 * Mock screen `empty`: Kütüphane with no Meiro lists yet. With a YouTube Music sign-in ([ytmTab]) its tab shows beside
 * "Meiro listeleri" as on the `library` screen, and a tap on it gives [onTab] true.
 */
@Composable
fun LibraryEmptyScreen(onNew: () -> Unit = {}, ytmTab: Boolean = false, onTab: (Boolean) -> Unit = {}) {
    Column {
        TopBar("Kütüphane")
        LibraryTabs(ytmTab = ytmTab, onTab = onTab)
        EmptyState(
            title = "Henüz liste yok",
            body = "Bir şarkının ⋮ menüsünden \"Listeye ekle\" ya da bir albümü Meiro listesine kopyala.",
            buttonLabel = "Yeni liste",
            onButton = onNew,
        )
    }
}
