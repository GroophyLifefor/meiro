package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.PlayerUi
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.QueueView
import com.muratkirazkaya.meiro.playback.RepeatMode
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.screens.NpLink
import com.muratkirazkaya.meiro.ui.screens.QueueRow
import com.muratkirazkaya.meiro.ui.screens.SuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingMappingTest {
    @Test fun ablativeFollowsTheLastSound() {
        assertEquals("Gece Sürüşü'nden", NowPlayingMapping.fromSource("Gece Sürüşü"))
        assertEquals("Daft Punk'tan", NowPlayingMapping.fromSource("Daft Punk"))
        assertEquals("Daily mix'ten", NowPlayingMapping.fromSource("Daily mix"))
        assertEquals("Random Access Memories'ten", NowPlayingMapping.fromSource("Random Access Memories"))
        assertEquals("Dinleme geçmişinden", NowPlayingMapping.fromSource("Dinleme geçmişi"))
        assertEquals("“daft punk” aramasından", NowPlayingMapping.fromSource("“daft punk” araması"))
        assertEquals("Klasikler'den", NowPlayingMapping.fromSource("Klasikler"))
    }

    private val a = QItem(Source.YTM, "a", "A", "AA", "4:22", sources = listOf(Source.YTM, Source.SOUNDCLOUD), refs = mapOf(Source.SOUNDCLOUD to "https://sc/a"))
    private val b = QItem(Source.SOUNDCLOUD, "https://sc/b", "B", "BB", "3:00")
    private fun ui(items: List<QItem>, index: Int, recStart: Int, suggested: Set<String> = emptySet()) = PlayerUi(
        items[index], true, 100_000, 262_000, false, queue = QueueView(items, index, recStart, suggested, "Gece Sürüşü"),
        shuffle = true, repeat = RepeatMode.Queue, autoPlay = true,
    )

    @Test fun playerShowsTheTrackItsSourcesAndState() {
        val n = NowPlayingMapping.player(ui(listOf(a, b), 0, -1), LinkState.MATCHED)
        assertEquals("A", n.title); assertEquals("Gece Sürüşü", n.context); assertEquals(NpLink.Matched, n.link)
        assertTrue(n.ytm && n.sc); assertEquals(Provider.Ytm, n.active)
        assertTrue(n.shuffle); assertEquals(RepeatMode.Queue, n.repeat); assertFalse(n.liked)
        val m = NowPlayingMapping.player(ui(listOf(a, b), 1, -1), null)
        assertEquals(NpLink.Unknown, m.link); assertFalse(m.ytm); assertEquals(Provider.Sc, m.active)
    }

    @Test fun upNextGroupsGeneratedTracksUnderTheirOrigin() {
        val y1 = QItem(Source.YTM, "y1", "Y1"); val m1 = QItem(Source.YTM, "m1", "M1", why = "Because"); val y2 = QItem(Source.YTM, "y2", "Y2")
        val u = NowPlayingMapping.upNext(ui(listOf(a, b, m1, y1, y2), 0, 2, setOf("m1")), { _, _ -> null })
        assertEquals("Gece Sürüşü'nden çalıyor · 1 sırada", u.sourceLine)
        val shape = u.rows.map { when (it) { is QueueRow.Song -> "s${it.index}"; is QueueRow.Label -> it.kind.name } }
        assertEquals(listOf("s0", "s1", SuggestionKind.Meiro.name, "s2", SuggestionKind.Ytm.name, "s3", "s4"), shape)
        val rows = u.rows.filterIsInstance<QueueRow.Song>().map { it.row }
        assertTrue(rows[0].now); assertEquals("AA · 4:22", rows[0].subtitle)
        assertEquals("Because", rows[2].why); assertEquals("", rows[3].why)
        assertFalse(rows[1].activeYtm)
    }
}
