package com.muratkirazkaya.meiro.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.muratkirazkaya.meiro.ui.components.EmptyState
import com.muratkirazkaya.meiro.ui.components.LibraryTabs
import com.muratkirazkaya.meiro.ui.components.TopBar

/** Mock screen `empty`: Kütüphane with no Meiro lists yet. */
@Composable
fun LibraryEmptyScreen() {
    Column {
        TopBar("Kütüphane")
        LibraryTabs()
        EmptyState(
            title = "Henüz liste yok",
            body = "Bir şarkının ⋮ menüsünden \"Listeye ekle\" ya da bir albümü Meiro listesine kopyala.",
            buttonLabel = "Yeni liste",
        )
    }
}
