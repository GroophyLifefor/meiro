package com.muratkirazkaya.meiro.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Ports of `internal/taste/taste_test.go`. */
class TasteTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private fun play(title: String, artist: String, daysAgo: Long, playedMs: Long, durationMs: Long) =
        Play(title = title, artist = artist, at = now.minus(Duration.ofDays(daysAgo)), playedMs = playedMs, durationMs = durationMs)

    @Test fun heardThroughRaisesAndSkippedLowers() {
        val p = Taste.build(listOf(play("Loved", "Artist A", 1, 200_000, 200_000), play("Skipped", "Artist B", 1, 5_000, 200_000), play("Half", "Artist C", 1, 120_000, 200_000)), emptyList(), now)
        assertTrue(p.artist("Artist A") > p.artist("Artist C") && p.artist("Artist C") > 0)
        assertTrue(p.artist("Artist B") < 0)
        assertEquals(1.0, p.skipRate("Artist B"), 0.0)
        assertTrue(p.skipRate("Nobody") == 0.0 && !p.known("Nobody"))
    }

    @Test fun votesFadeWithAge() {
        val p = Taste.build(listOf(play("Old", "Old Artist", 120, 200_000, 200_000), play("New", "New Artist", 1, 200_000, 200_000)), emptyList(), now)
        assertTrue(p.artist("New Artist") > p.artist("Old Artist"))
        // Four half-lives old: about a sixteenth of the vote is left.
        val old = p.artist("Old Artist")
        assertTrue("old = $old", old in 0.04..0.09)
    }

    @Test fun repeatsCountForLess() {
        val loop = List(20) { play("Loop", "Looper", 1, 200_000, 200_000) }
        val variety = (1..10).map { play("S$it", "Variety", 1, 200_000, 200_000) }
        val many = Taste.build(loop + variety, emptyList(), now)
        assertTrue(many.artist("Looper") <= 5.1)
        assertTrue(many.artist("Variety") > many.artist("Looper"))
    }

    @Test fun explicitVotes() {
        val week = now.minus(Duration.ofDays(7))
        val p = Taste.build(
            listOf(play("Played", "P", 7, 200_000, 200_000)),
            listOf(Signal(title = "Liked", artist = "L", kind = SignalKind.Like, at = week), Signal(title = "Saved", artist = "S", kind = SignalKind.Saved, at = week), Signal(title = "Kept", artist = "K", kind = SignalKind.Downloaded, at = week)),
            now,
        )
        assertTrue(p.artist("L") > p.artist("S") && p.artist("S") >= p.artist("K") && p.artist("K") > p.artist("P"))
    }

    @Test fun songKeysFoldAndUnknownLengthIsJudgedByTime() {
        assertEquals(Taste.trackKey("Get Lucky", "Daft Punk"), Taste.trackKey("get  lucky", "DAFT PUNK"))
        val p = Taste.build(listOf(Play(title = "Long", artist = "A", at = now, playedMs = 150_000), Play(title = "Short", artist = "B", at = now, playedMs = 8_000)), emptyList(), now)
        assertTrue(p.artist("A") > 0 && p.artist("B") < 0)
    }

    @Test fun topAndTheFuture() {
        val p = Taste.build(
            listOf(
                play("One", "Big", 1, 200_000, 200_000), play("Two", "Big", 1, 200_000, 200_000), play("Three", "Small", 1, 200_000, 200_000),
                Play(title = "Future", artist = "Later", at = now.plus(Duration.ofHours(1)), playedMs = 200_000, durationMs = 200_000),
            ),
            emptyList(), now,
        )
        assertFalse(p.known("Later"))
        assertEquals(listOf("Big", "Small"), p.topArtists(5).map { it.name })
        assertEquals(1, p.topArtists(1).size)
        assertEquals(3, p.songs(10).size)
        assertEquals(now.minus(Duration.ofDays(1)), p.lastPlayed("One", "Big"))
        assertTrue(Taste.build(emptyList(), emptyList(), now).empty())
        assertNotEquals("", Taste.trackKey("A", ""))
        assertEquals("", Taste.trackKey("", "A"))
    }
}
