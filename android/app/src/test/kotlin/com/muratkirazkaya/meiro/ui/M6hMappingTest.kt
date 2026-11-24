package com.muratkirazkaya.meiro.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.ui.theme.DarkMeiroColors
import com.muratkirazkaya.meiro.ui.theme.LightMeiroColors
import com.muratkirazkaya.meiro.ui.theme.SeedColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** M6h: the playlist page's search and order (`playlist_sort.go`), the swatches' colours (`m3/color.go`), the share links. */
class M6hMappingTest {
    private fun item(t: String, a: String, ms: Long) = Item(source = "ytm", key = t, title = t, artist = a, durationMs = ms)

    // The mock's "Gece Sürüşü" rows (`plRows`).
    private val list = listOf(
        item("Nightcall", "Kavinsky", 258_000), item("Stolen Dance", "Milky Chance", 313_000), item("After Dark", "Mr.Kitty", 259_000),
        item("Gece Yarısı (canlı)", "Anıl Kaya", 221_000), item("Bedroom Demo 3", "kendi kaydım", 132_000),
        item("Bu Kalp Seni Unutur mu", "Fikret Kızılok", 262_000), item("Yolla", "Tarkan", 245_000),
    )
    private fun titles(order: List<Int>) = order.map { list[it].title }

    @Test fun theListsOwnOrderAndItsReverse() {
        assertEquals(list.indices.toList(), PlaylistView.order(list, "", PlSort()))
        assertEquals(list.indices.reversed().toList(), PlaylistView.order(list, "", PlSort(desc = true)))
    }

    @Test fun searchKeepsTitleOrArtistMatchesInTurkishCase() {
        // The mock's `plsearch` ("ka"): Nightcall (Kavinsky), Gece Yarısı (Anıl Kaya), Bedroom Demo 3 (kendi kaydım), Bu Kalp…, Yolla (Tarkan).
        assertEquals(listOf("Nightcall", "Gece Yarısı (canlı)", "Bedroom Demo 3", "Bu Kalp Seni Unutur mu", "Yolla"), titles(PlaylistView.order(list, "ka", PlSort())))
        assertEquals(listOf("Gece Yarısı (canlı)"), titles(PlaylistView.order(list, "YARISI", PlSort())))
        assertEquals(listOf("Bu Kalp Seni Unutur mu"), titles(PlaylistView.order(list, "KIZILOK", PlSort())))
        assertEquals(emptyList<String>(), titles(PlaylistView.order(list, "zzz", PlSort())))
    }

    @Test fun fieldsSortThenTitleBreaksTiesAndDescReverses() {
        assertEquals(
            listOf("After Dark", "Bedroom Demo 3", "Bu Kalp Seni Unutur mu", "Gece Yarısı (canlı)", "Nightcall", "Stolen Dance", "Yolla"),
            titles(PlaylistView.order(list, "", PlSort(PlSortField.Title))),
        )
        assertEquals(
            listOf("Gece Yarısı (canlı)", "Bu Kalp Seni Unutur mu", "Nightcall", "Bedroom Demo 3", "Stolen Dance", "After Dark", "Yolla"),
            titles(PlaylistView.order(list, "", PlSort(PlSortField.Artist))),
        )
        assertEquals("Bedroom Demo 3", titles(PlaylistView.order(list, "", PlSort(PlSortField.Duration))).first())
        assertEquals("Stolen Dance", titles(PlaylistView.order(list, "", PlSort(PlSortField.Duration, desc = true))).first())
        // Equal lengths fall back to the title.
        val tie = listOf(item("b", "x", 100), item("a", "y", 100), item("c", "z", 50))
        assertEquals(listOf("c", "a", "b"), PlaylistView.order(tie, "", PlSort(PlSortField.Duration)).map { tie[it].title })
        // Search and order together.
        assertEquals(listOf("Yolla", "Nightcall", "Gece Yarısı (canlı)", "Bu Kalp Seni Unutur mu", "Bedroom Demo 3"), titles(PlaylistView.order(list, "ka", PlSort(PlSortField.Title, desc = true))))
    }

    @Test fun choosingTheSameFieldReversesItAndAnotherKeepsTheDirection() {
        var s = PlSort()
        assertEquals("Ekleme sırası", s.label)
        s = s.choose(PlSortField.Original)
        assertEquals(PlSort(PlSortField.Original, true), s)
        assertEquals("Ekleme sırası · azalan", s.label)
        s = s.choose(PlSortField.Artist)
        assertEquals(PlSort(PlSortField.Artist, true), s)
        assertEquals("Sanatçı · azalan", s.label)
        assertEquals(PlSort(PlSortField.Artist, false), s.choose(PlSortField.Artist))
    }

    @Test fun theFirstSwatchIsTheMocksOwnPalette() {
        assertSame(LightMeiroColors, SeedColors.of("#7c5cff", false))
        assertSame(DarkMeiroColors, SeedColors.of("#7C5CFF", true))
        assertSame(LightMeiroColors, SeedColors.of("not a colour", false))
    }

    /** Golden values printed by the desktop's own `m3.NewScheme(m3.NewPalettes(seed, m3.TonalSpot), dark)` (Go, run once). */
    @Test fun anotherSwatchGrowsEveryRoleAsTheDesktopDoes() {
        fun hex(c: Color) = "%06X".format(c.toArgb() and 0xFFFFFF)
        val blue = SeedColors.of("#3b82f6", false)
        assertEquals(listOf("315CA4", "FFFFFF", "D3E4FF", "F6FAFF", "EBEEF3", "414750", "717781", "F7D5FF", "B1100C"),
            listOf(blue.primary, blue.onPrimary, blue.primaryContainer, blue.surface, blue.surfaceContainer, blue.onSurfaceVariant, blue.outline, blue.tertiaryContainer, blue.error).map(::hex))
        val blueDark = SeedColors.of("#3b82f6", true)
        assertEquals(listOf("A6C8FF", "002B6E", "194389", "111317", "1D2023", "C0C7D2", "8B919B", "573A5E", "FFAFA3"),
            listOf(blueDark.primary, blueDark.onPrimary, blueDark.primaryContainer, blueDark.surface, blueDark.surfaceContainer, blueDark.onSurfaceVariant, blueDark.outline, blueDark.tertiaryContainer, blueDark.error).map(::hex))
        val red = SeedColors.of("#ef4444", false)
        assertEquals(listOf("983C38", "FFD8D4", "FFF7F7", "504342", "F8E0AE"), listOf(red.primary, red.primaryContainer, red.surface, red.onSurfaceVariant, red.tertiaryContainer).map(::hex))
        val redDark = SeedColors.of("#ef4444", true)
        assertEquals(listOf("FFAEA7", "610109", "7C2221", "171211", "231E1E"), listOf(redDark.primary, redDark.onPrimary, redDark.primaryContainer, redDark.surface, redDark.surfaceContainer).map(::hex))
        // The mock's own colours with no Material role stay.
        assertEquals(LightMeiroColors.scrim, blue.scrim)
        assertEquals(LightMeiroColors.wBg, blue.wBg)
        assertEquals(DarkMeiroColors.page, blueDark.page)
    }

    @Test fun shareLinksAreTheDesktopsBrowseUrls() {
        assertEquals("https://music.youtube.com/browse/MPREb_fake123", browseUrl("MPREb_fake123"))
        assertEquals("https://music.youtube.com/browse/VLPLfake%20list", browseUrl("VLPLfake list"))
    }
}
