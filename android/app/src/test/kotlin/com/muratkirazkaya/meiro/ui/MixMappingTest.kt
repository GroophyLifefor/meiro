package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.data.SourceRef
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class MixMappingTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val today = ZonedDateTime.of(2026, 11, 22, 21, 0, 0, 0, zone).toLocalDate()
    private fun at(day: Int): Instant = ZonedDateTime.of(2026, 11, day, 7, 12, 0, 0, zone).toInstant()

    private fun mix(day: Int, n: Int = 3) = Library(
        "m", "Daily mix",
        items = (1..n).map {
            Item(
                source = Source.YTM, key = "v$it", title = "T$it", artist = "A$it", durationMs = 262_000, updatedAt = at(day),
                why = if (it == 1) "Because you listen to Fikret Kızılok" else "New to you, like Gülay",
                sources = if (it == 1) listOf(SourceRef(Source.YTM, "v1"), SourceRef(Source.SOUNDCLOUD, url = "https://soundcloud.com/x/1")) else emptyList(),
                state = if (it == 1) LinkState.MATCHED else "",
            )
        },
    )

    @Test fun reasonsAreShownInTheMocksTurkish() {
        assertEquals("Fikret Kızılok ile benzer", MixMapping.whyText("Because you listen to Fikret Kızılok"))
        assertEquals("Yeni sanatçı · Gülay ile benzer", MixMapping.whyText("New to you, like Gülay"))
        assertEquals("Son bir ayda 14 kez dinledin", MixMapping.whyText("Son bir ayda 14 kez dinledin"))
        assertEquals("", MixMapping.whyText(""))
    }

    @Test fun kindLineSaysForWhichDay() {
        assertEquals("Bugün için · 3 parça · her sabah yenilenir", MixMapping.kind(mix(22), today, zone))
        assertEquals("Dün için · 3 parça · her sabah yenilenir", MixMapping.kind(mix(21), today, zone))
        assertEquals("3 Kasım için · 30 parça · her sabah yenilenir", MixMapping.kind(mix(3, 30), today, zone))
    }

    @Test fun pageRowsCarryTheReasonAndThePlayingTrack() {
        val page = MixMapping.page(mix(22), null, "v2", today, zone)
        assertEquals(listOf("Fikret Kızılok ile benzer", "Yeni sanatçı · Gülay ile benzer", "Yeni sanatçı · Gülay ile benzer"), page.rows.map { it.why })
        assertEquals("A1 · 4:22", page.rows[0].subtitle)
        assertTrue(page.rows[0].ytm && page.rows[0].sc && page.rows[0].activeYtm)
        assertEquals(LinkBadge.None, page.rows[0].link)
        assertEquals(LinkBadge.Unknown, page.rows[1].link)
        assertFalse(page.rows[0].now)
        assertTrue(page.rows[1].now)
    }

    @Test fun libraryRowIsTheMocksDailyMixRow() {
        val row = MixMapping.libraryRow(mix(22, 30), today, zone)
        assertEquals("Daily mix", row.name)
        assertEquals("Meiro · bugün · 30 parça", row.meta)
        assertEquals("Meiro · dün · 3 parça", MixMapping.libraryRow(mix(21), today, zone).meta)
    }
}
