package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.sync.StatsBucket
import com.muratkirazkaya.meiro.sync.StatsSummary
import com.muratkirazkaya.meiro.sync.StatsTotals
import com.muratkirazkaya.meiro.ui.screens.SettingsUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The history page (`historypage.go`), the stats (`history.Summarize` and the server's summary) and the account lines. */
class M6bMappingTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val now = ZonedDateTime.of(2026, 10, 29, 14, 51, 0, 0, zone)
    private fun at(day: Int, h: Int, m: Int, month: Int = 10) = ZonedDateTime.of(2026, month, day, h, m, 0, 0, zone).toInstant()
    private fun e(id: String, title: String, artist: String, at: Instant, played: Long, dur: Long = 240_000, device: String = "", done: Boolean = false, src: String = Source.YTM) =
        HistoryEntry(videoId = id, title = title, artist = artist, playedAt = at, source = src, durationMs = dur, playedMs = played, completed = done, device = device, eventId = "ev-$id-${at.epochSecond}")

    private val history = listOf(
        e("b", "Starboy", "The Weeknd", at(27, 19, 36), 30_000, 230_000, src = Source.SOUNDCLOUD),
        e("a", "Get Lucky", "Daft Punk", at(27, 19, 40), 248_000, 248_000, done = true),
        e("d", "Gece Yarısı", "Anıl Kaya", at(28, 23, 12), 8_000, 221_000, device = "murat-pc"),
        e("c", "After Dark", "Mr.Kitty", at(28, 23, 17), 259_000, 259_000, device = "murat-pc", done = true),
        e("e", "Nightcall", "Kavinsky", at(29, 14, 31), 125_000, 258_000, device = "murat-pc"),
        e("f", "Yolla", "Tarkan", at(29, 14, 40), 12_000, 245_000, device = "Pixel"),
        e("g", "Bu Kalp Seni Unutur mu", "Fikret Kızılok", at(29, 14, 48), 262_000, 262_000, device = "Pixel", done = true),
    )

    @Test fun daysNewestFirstWithTotalsAndNotes() {
        val ui = HistoryMapping.build(history, HistoryMapping.ALL, "", false, now, "Pixel", emptyList(), null, null, true)
        val days = ui.lines.filterIsInstance<HistoryLine.Day>()
        assertEquals(listOf("Bugün", "Dün", "27 Ekim Salı"), days.map { it.title })
        // Today: 3 plays, the counted ones heard 125 + 262 s = 6.45 min -> ~6 dk (Yolla is skipped).
        assertEquals("3 çalma · ~6 dk", days[0].sub)
        val plays = ui.lines.filterIsInstance<HistoryLine.Play>().map { it.row }
        assertEquals(listOf("Bu Kalp Seni Unutur mu", "Yolla", "Nightcall"), plays.take(3).map { it.title })
        assertEquals("14:48 · telefon · tamamı", plays[0].note)
        assertEquals("14:40 · telefon · geçildi · 0:12", plays[1].note)
        assertTrue(plays[1].dim)
        assertEquals("14:31 · murat-pc · 2:05 / 4:18", plays[2].note)
        assertEquals("Fikret Kızılok", plays[0].subtitle)
        // A 30 s SoundCloud play of a 3:50 song counts (30 s) and shows how much was heard.
        assertEquals("19:36 · telefon · 0:30 / 3:50", plays.last().note)
        assertEquals(listOf("Hepsi", "Sayılanlar", "Geçilenler", "Telefon", "murat-pc"), ui.chips.map { it.label })
        assertTrue(ui.note.startsWith("Bütün cihazlardaki çalmalar"))
    }

    @Test fun filtersAndSearch() {
        fun titles(filter: String, q: String = "") = HistoryMapping.build(history, filter, q, q.isNotEmpty(), now, "Pixel", emptyList(), null, null, false)
            .lines.filterIsInstance<HistoryLine.Play>().map { it.row.title }
        assertEquals(listOf("Yolla", "Gece Yarısı"), titles(HistoryMapping.SKIPPED))
        assertEquals(5, titles(HistoryMapping.COUNTED).size)
        assertEquals(listOf("Nightcall", "After Dark", "Gece Yarısı"), titles(HistoryMapping.DEVICE + "murat-pc"))
        assertEquals(listOf("Bu Kalp Seni Unutur mu", "Yolla", "Get Lucky", "Starboy"), titles(HistoryMapping.DEVICE + "telefon"))
        // Turkish folding: "KIZILOK" finds "Kızılok"; the artist is searched too.
        assertEquals(listOf("Bu Kalp Seni Unutur mu"), titles(HistoryMapping.ALL, "KIZILOK"))
        assertEquals(listOf("Get Lucky"), titles(HistoryMapping.ALL, "daft"))
        assertTrue(HistoryMapping.build(emptyList(), HistoryMapping.ALL, "", false, now, "Pixel", emptyList(), null, null, false).empty)
    }

    @Test fun thePlayInProgressIsMarkedAndNotFaded() {
        val playing = history + e("h", "Son Arzum", "Nilüfer", at(29, 14, 52), 0, 227_000, device = "Pixel")
        val plays = HistoryMapping.build(playing, HistoryMapping.ALL, "", false, now, "Pixel", emptyList(), null, "h", true).lines.filterIsInstance<HistoryLine.Play>()
        assertTrue(plays[0].row.now); assertFalse(plays[0].row.dim)
        assertEquals("14:52 · telefon · çalıyor", plays[0].row.note)
        assertFalse(plays[1].row.now)
    }

    @Test fun dayTotalsRoundAsTheDesktop() {
        assertEquals("2 çalma · ~0 dk", HistoryMapping.dayTotals(2, 0))
        assertEquals("1 çalma · ~1 dk", HistoryMapping.dayTotals(1, 10_000))
        assertEquals("5 çalma · ~16 dk", HistoryMapping.dayTotals(5, 15 * 60_000 + 40_000))
        assertEquals("3 Kasım Salı", HistoryMapping.dayTitle(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 23)))
    }

    @Test fun localStatsCountTheCountedPlaysOfTheMonth() {
        val s = StatsMapping.local(history + e("x", "Old", "Someone", at(30, 10, 0, month = 9), 200_000), StatsPeriod.Month, now)
        assertEquals(5, s.totals.plays) // 7 in October, 2 skipped
        assertEquals(248_000L + 30_000 + 259_000 + 125_000 + 262_000, s.totals.playedMs)
        assertEquals(5, s.totals.uniqueTracks)
        assertEquals(5, s.totals.uniqueArtists)
        assertEquals(listOf("2026-10-27", "2026-10-28", "2026-10-29"), s.series.map { it.bucket })
        val year = StatsMapping.local(history, StatsPeriod.Year, now)
        assertEquals(listOf("2026-10"), year.series.map { it.bucket })
        // New this month: every artist but "Someone" (first heard in September, and not played in October anyway).
        assertEquals(4, StatsMapping.newArtists(history + e("y", "Again", "Daft Punk", at(1, 9, 0, month = 9), 200_000), StatsPeriod.Month, now))
    }

    @Test fun serverSummaryBecomesTheTilesBarsAndLists() {
        val s = StatsSummary(
            StatsTotals(41L * 3_600_000 + 1_000, 5940, 1402, 411),
            listOf(com.muratkirazkaya.meiro.sync.StatsTrack("Nightcall", "Kavinsky", 31, 1)),
            listOf(com.muratkirazkaya.meiro.sync.StatsArtist("Kavinsky", 71, 1)),
            listOf(StatsBucket("2026-10-05", 600_000), StatsBucket("2026-10-29", 300_000)),
        )
        val ui = StatsMapping.ui(s, 19, StatsPeriod.Month, now, true)
        assertEquals(listOf("41 sa" to "dinleme", "5.940" to "çalma", "1.402" to "farklı şarkı", "411" to "sanatçı · 19 yeni"), ui.tiles)
        assertEquals("Ekim 2026", ui.monthLabel)
        assertEquals(31, ui.bars.size)
        assertEquals(1f, ui.bars[4].height, 0.001f); assertFalse(ui.bars[4].lo)
        assertEquals(0.5f, ui.bars[28].height, 0.001f)
        assertTrue(ui.bars[0].lo)
        assertEquals("Kavinsky · 31 çalma", ui.tracks[0].sub)
        assertEquals("71 çalma", ui.artists[0].sub)
        assertEquals("45 dk", StatsMapping.listened(45 * 60_000L))
        assertEquals(12, StatsMapping.bars(listOf(StatsBucket("2026-03", 5)), StatsPeriod.Year, now).size)
        assertEquals("2026-10", StatsMapping.anchor(StatsPeriod.Month, now)); assertEquals("2026", StatsMapping.anchor(StatsPeriod.Year, now))
    }

    @Test fun accountLines() {
        assertEquals("murat@…", AccountMapping.shortEmail("murat@example.com"))
        val t = Instant.parse("2026-10-29T12:00:00Z")
        assertEquals("az önce", AccountMapping.ago(t.minusSeconds(30), t))
        assertEquals("2 dk önce", AccountMapping.ago(t.minusSeconds(150), t))
        assertEquals("3 sa önce", AccountMapping.ago(t.minusSeconds(3 * 3600 + 5), t))
        assertEquals("hiç", AccountMapping.ago(null, t))
        assertEquals("eşitleme güncel", AccountMapping.state(0, "", false))
        assertEquals("2 kayıt bekliyor", AccountMapping.state(2, "", false))
        assertEquals("eşitlenemedi", AccountMapping.state(0, "ağ yok", false))
    }

    @Test fun turkishSuffixesAfterNumbers() {
        assertEquals("de", AccountMapping.locative(12)); assertEquals("da", AccountMapping.locative(30)); assertEquals("ta", AccountMapping.locative(40))
        assertEquals("te", AccountMapping.locative(3)); assertEquals("da", AccountMapping.locative(0)); assertEquals("da", AccountMapping.locative(9))
        assertEquals("da", AccountMapping.monthSuffix("Kasım")); assertEquals("de", AccountMapping.monthSuffix("Eylül")); assertEquals("ta", AccountMapping.monthSuffix("Mart"))
        assertEquals("u", SettingsUi.percentSuffix(30)); assertEquals("ı", SettingsUi.percentSuffix(40)); assertEquals("i", SettingsUi.percentSuffix(25)); assertEquals("ü", SettingsUi.percentSuffix(100))
        assertEquals("Önerilerin %30'u hiç dinlemediğin sanatçılardan", SettingsUi("", "", "").exploreSub)
        val made = ZonedDateTime.of(2026, 10, 29, 7, 12, 0, 0, zone).toInstant()
        assertEquals("Bugün 07:12'de yapıldı", AccountMapping.dailyMixLine(made, "2026-10-29", false, true, now.toInstant(), zone))
        assertEquals("Son Daily mix 3 Ekim'de yapıldı", AccountMapping.dailyMixLine(null, "2026-10-03", false, true, now.toInstant(), zone))
        assertEquals("Öneriler kapalı.", AccountMapping.dailyMixLine(null, "", false, false, now.toInstant(), zone))
    }
}
