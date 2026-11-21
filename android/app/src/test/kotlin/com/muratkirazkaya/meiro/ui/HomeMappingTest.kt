package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.sync.Ids
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeMappingTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val now = ZonedDateTime.of(2026, 11, 22, 21, 10, 0, 0, zone)
    private fun at(h: Int, m: Int = 0, day: Int = 22): Instant = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant()

    private fun play(id: String, at: Instant, playedMs: Long, artist: String = "A", source: String = Source.YTM) =
        HistoryEntry(videoId = id, title = "T $id", artist = artist, playedAt = at, source = source, durationMs = 200_000, playedMs = playedMs, eventId = "e-$id-${at.epochSecond}")

    private fun item(id: String, artist: String = "A", updated: Instant = at(1)) =
        Item(source = Source.YTM, key = id, title = "T $id", artist = artist, durationMs = 180_000, updatedAt = updated)

    @Test fun greetingFollowsTheDesktopHours() {
        assertEquals("İyi geceler", HomeMapping.greeting(0))
        assertEquals("İyi geceler", HomeMapping.greeting(4))
        assertEquals("Günaydın", HomeMapping.greeting(5))
        assertEquals("Günaydın", HomeMapping.greeting(11))
        assertEquals("İyi günler", HomeMapping.greeting(12))
        assertEquals("İyi günler", HomeMapping.greeting(17))
        assertEquals("İyi akşamlar", HomeMapping.greeting(18))
        assertEquals("İyi akşamlar", HomeMapping.greeting(23))
    }

    @Test fun nameComesFromTheEmail() {
        assertEquals("İyi akşamlar, Murat", HomeMapping.greetingLine(20, "murat.kirazkaya@example.com"))
        assertEquals("İyi akşamlar, İsmail", HomeMapping.greetingLine(20, "ismail_k@example.com"))
        assertEquals("Günaydın, Ayşe", HomeMapping.greetingLine(9, "AYŞE@example.com"))
        assertEquals("İyi akşamlar", HomeMapping.greetingLine(20, ""))
        assertNull(HomeMapping.firstName("@example.com"))
    }

    @Test fun minutesCountTodayOnlyAndEveryDevice() {
        val h = listOf(
            play("y", at(23, 50, day = 21), 600_000), // yesterday
            play("a", at(0, 5), 120_000),
            play("b", at(20, 0), 1_500_000).copy(device = "desktop"),
            play("c", at(21, 0), 59_000), // under a minute adds its seconds
            play("d", at(21, 5), 0), // still playing: nothing heard yet
        )
        val ms = HomeMapping.listenedTodayMs(h, now.toLocalDate(), zone)
        assertEquals(120_000L + 1_500_000 + 59_000, ms)
        assertEquals("Bugün 27 dakika dinledin", HomeMapping.minutesLine(ms)) // 27 min 59 s: whole minutes
        assertEquals("Bugün 0 dakika dinledin", HomeMapping.minutesLine(0))
    }

    @Test fun resumeIsTheLastThreeDifferentTracksNewestFirst() {
        val h = listOf(play("a", at(10), 1), play("b", at(11), 1), play("a", at(12), 1), play("c", at(13), 1), play("sc1", at(14), 1, source = Source.SOUNDCLOUD), play("c", at(15), 1))
        assertEquals(listOf("c", "sc1", "a"), HomeMapping.resume(h).map { it.videoId })
        assertEquals(emptyList<String>(), HomeMapping.resume(emptyList()).map { it.videoId })
    }

    @Test fun resumeRowUsesTheListTrackWhenAListHoldsIt() {
        val lib = Library("l1", "Gece", items = listOf(item("a").copy(sources = listOf(com.muratkirazkaya.meiro.data.SourceRef(Source.SOUNDCLOUD, url = "https://soundcloud.com/x/a")), state = LinkState.MATCHED)))
        val d = HomeMapping.build("m@x", now, listOf(play("a", at(20), 70_000), play("z", at(21), 1)), listOf(lib), null, "", playingKey = "a")
        val rows = d.ui.resume
        assertEquals(listOf("z", "a"), rows.map { it.key })
        // Not in a list: only its own source, link unknown.
        assertEquals(true, rows[0].ytm); assertEquals(false, rows[0].sc); assertEquals(LinkBadge.Unknown, rows[0].link)
        // In a list: both sources, matched (no link button), and it is the one playing.
        assertEquals(true, rows[1].ytm); assertEquals(true, rows[1].sc); assertEquals(LinkBadge.None, rows[1].link); assertTrue(rows[1].now)
        assertEquals("A · 3:20", rows[0].subtitle)
        assertEquals(listOf("z", "a"), d.resume.map { it.key })
    }

    /** The server can hand back a list track without a length (its shared track row has none): the play's length is used. */
    @Test fun resumeRowFallsBackToThePlaysLength() {
        val lib = Library("l1", "Klasikler", items = listOf(item("a").copy(durationMs = 0)))
        val d = HomeMapping.build("", now, listOf(play("a", at(20), 1)), listOf(lib), null, "", null)
        assertEquals("A · 3:20", d.ui.resume.single().subtitle)
    }

    @Test fun picksAreListsByLastUseWithoutTheMix() {
        val old = Library("old", "Eski", updatedAt = at(1, day = 1), items = listOf(item("x")))
        val played = Library("played", "Çalınan", updatedAt = at(1, day = 2), items = listOf(item("p")))
        val edited = Library("edited", "Değişen", updatedAt = at(10))
        val mix = Library("mix", "Daily mix", serverId = Ids.dailyMixId(), updatedAt = at(20), items = (1..6).map { item("m$it") })
        val d = HomeMapping.build("", now, listOf(play("p", at(19), 50_000)), listOf(old, played, edited, mix), null, "", null)
        assertEquals(listOf("played", "edited", "old"), d.pickIds)
        assertEquals("Meiro listesi · 1", d.ui.picks[0].meta)
        assertEquals(Provider.Meiro, d.ui.picks[0].logo)
        assertEquals("Çalınan", d.ui.picks[0].title)
    }

    @Test fun mixCardComesFromTheStoredDailyMix() {
        val items = listOf(item("1", "Fikret Kızılok", at(6)), item("2", "Gülay", at(6)), item("3", "Kavinsky", at(6)), item("4", "Yeni Bir"), item("5", "Gülay"), item("6", "Bilinen"), item("7", "Yeni İki"))
        val mix = Library("local-mix", "Daily mix", items = items)
        val other = Library("o", "Başka", items = listOf(item("b", "Bilinen")))
        val d = HomeMapping.build("", now, emptyList(), listOf(mix, other), null, "local-mix", null)
        val m = d.ui.mix!!
        assertEquals("Daily mix · bugün", m.kicker)
        assertEquals("7 şarkı", m.title)
        assertEquals("Fikret Kızılok, Gülay, Kavinsky ve 2 yeni sanatçı", m.artists)
        assertEquals(4, m.covers.size)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7"), d.mixItems.map { it.key })
        assertEquals(listOf("o"), d.pickIds)
    }

    @Test fun mixKickerSaysTheDayWhenNotToday() {
        val y = Library("m", "Daily mix", serverId = Ids.dailyMixId(), items = listOf(item("1", updated = at(9, day = 21))))
        assertEquals("Daily mix · dün", HomeMapping.build("", now, emptyList(), listOf(y), null, "", null).ui.mix!!.kicker)
        val old = Library("m", "Daily mix", serverId = Ids.dailyMixId(), items = listOf(item("1", updated = at(9, day = 3))))
        assertEquals("Daily mix · 3 Kasım", HomeMapping.build("", now, emptyList(), listOf(old), null, "", null).ui.mix!!.kicker)
    }

    @Test fun noMixNoCard() {
        assertNull(HomeMapping.build("", now, emptyList(), listOf(Library("a", "A", items = listOf(item("1")))), null, "", null).ui.mix)
        // A Daily mix list with no tracks shows no card either.
        assertNull(HomeMapping.build("", now, emptyList(), listOf(Library("m", "Daily mix", serverId = Ids.dailyMixId())), null, "", null).ui.mix)
    }

    @Test fun artistsLineWithoutNewArtists() {
        assertEquals("A, B ve C", HomeMapping.artistsLine(listOf(item("1", "A"), item("2", "B"), item("3", "C"), item("4", "A")), emptyList(), emptyList()))
        assertEquals("A ve B", HomeMapping.artistsLine(listOf(item("1", "A"), item("2", "B")), emptyList(), emptyList()))
        assertEquals("A", HomeMapping.artistsLine(listOf(item("1", "A")), emptyList(), emptyList()))
        // Artists played before are not new.
        assertEquals("A, B, C ve 1 yeni sanatçı", HomeMapping.artistsLine(listOf(item("1", "A"), item("2", "B"), item("3", "C"), item("4", "D"), item("5", "E")), listOf(play("x", at(1), 1, artist = "D")), emptyList()))
    }

    @Test fun emptyHomeIsGreetingAndMinutes() {
        val d = HomeMapping.build("", now, emptyList(), emptyList(), null, "", null)
        assertEquals("İyi akşamlar", d.ui.greeting)
        assertEquals("Bugün 0 dakika dinledin", d.ui.minutes)
        assertNull(d.ui.mix); assertTrue(d.ui.picks.isEmpty()); assertTrue(d.ui.resume.isEmpty()); assertTrue(d.ui.releases.isEmpty())
    }
}
