package com.muratkirazkaya.meiro.data

import com.muratkirazkaya.meiro.data.Match.Status
import com.muratkirazkaya.meiro.data.Match.Track
import org.junit.Assert.*
import org.junit.Test

/** Ports of internal/match/match_test.go (the library dedupe and the link keys rely on it). */
class MatchTest {
    @Test fun normalize() {
        for ((input, want) in listOf(
            "Yorushika" to "yorushika", "Just a Moment (feat. Someone)" to "just a moment", "Ame [Remastered]" to "ame",
            "Şımarık" to "simarik", "İstanbul'da" to "istanbul da", "Gülüşün" to "gulusun", "Song - Sped Up" to "song",
            "Song (Slowed Down)" to "song", "Song (Official Video)" to "song", "Song (Lyrics)" to "song", "Some Song!!" to "some song",
        )) assertEquals(input, want, Match.normalize(input))
    }

    @Test fun matchCases() {
        assertEquals(Status.MATCHED, Match.match(Track("Just a Moment", "Yorushika", 242_000), Track("Just a Moment (feat. Someone)", "Yorushika", 243_000)))
        assertEquals(Status.MATCHED, Match.match(Track("Sımarık", "Tarkan"), Track("Simarik", "TARKAN")))
        assertEquals(Status.MATCHED, Match.match(Track("Ame", "Nova"), Track("Ame [Remastered]", "Nova")))
        val a = Track("Nostalgia", "Kaori", 200_000)
        assertEquals(Status.UNKNOWN, Match.match(a, Track("Nostalgia - Sped Up", "Kaori", 180_000)))
        assertEquals(Status.MATCHED, Match.match(a, Track("Nostalgia - Sped Up", "Kaori", 202_000)))
        val m = Track("Meteorite", "Kaori", 240_000)
        assertEquals(Status.MATCHED, Match.match(m, Track("Meteorite", "Kaori", 244_000)))
        assertEquals(Status.UNKNOWN, Match.match(m, Track("Meteorite", "Kaori", 245_000)))
        assertEquals(Status.MATCHED, Match.match(Track("Meteorite", "Kaori"), Track("Meteorite", "Kaori", 999_000)))
        assertEquals(Status.UNKNOWN, Match.match(Track("Home", "Nova"), Track("Home", "Kaori")))
        assertEquals(Status.MATCHED, Match.match(Track("One Name", "Nova", isrc = "JPA651703790"), Track("Another Name", "Someone", isrc = "JPA651703790")))
    }
}
