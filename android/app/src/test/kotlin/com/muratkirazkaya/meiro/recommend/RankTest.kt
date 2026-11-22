package com.muratkirazkaya.meiro.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Ports of `internal/recommend/recommend_test.go` (Replay, an offline evaluation, is not ported), and a golden run of the Go code. */
class RankTest {
    private val now = Instant.ofEpochSecond(1_800_000_000)
    private fun heard(title: String, artist: String, daysAgo: Long) =
        Play(title = title, artist = artist, at = now.minus(Duration.ofDays(daysAgo)), playedMs = 200_000, durationMs = 200_000)
    private fun cand(title: String, artist: String, rel: Relation, seed: String, score: Double, strength: Double = 0.0) =
        Candidate(title, artist, Unit, rel, strength, Seed(seed, score))
    private fun defaults() = RankOptions(now = now, salt = "2027-01-15")

    @Test fun rankOrdersByEvidence() {
        val p = Taste.build(listOf(heard("S1", "Seed One", 1), heard("S2", "Seed Two", 1)), emptyList(), now)
        val got = Rank.rank(p, listOf(
            cand("Found Twice", "A", Relation.YouTubeRelated, "Seed One", 1.0), cand("Found Twice", "A", Relation.YouTubeRelated, "Seed Two", 1.0),
            cand("Found Once", "B", Relation.YouTubeRelated, "Seed One", 1.0),
            cand("Similar Only", "C", Relation.SimilarArtist, "Seed One", 1.0),
            cand("Strong Seed", "D", Relation.YouTubeRelated, "Seed Two", 3.0),
        ), defaults().copy(explore = 0.0)).map { it.title }
        assertTrue(got.toString(), got[0] == "Strong Seed" || got[0] == "Found Twice")
        assertTrue(got.toString(), got.indexOf("Found Twice") < got.indexOf("Found Once") && got.indexOf("Found Once") < got.indexOf("Similar Only"))
    }

    @Test fun rankLeavesOutWhatIsDislikedOrJustPlayed() {
        val p = Taste.build(listOf(
            heard("Love", "Seed", 1),
            Play(title = "Nope", artist = "Disliked", at = now.minus(Duration.ofHours(1)), playedMs = 4_000, durationMs = 200_000),
            heard("Just Now", "Fresh Artist", 0), heard("Last Month", "Old Artist", 30),
        ), emptyList(), now)
        val got = Rank.rank(p, listOf(
            cand("Another", "Disliked", Relation.YouTubeRelated, "Seed", 1.0), cand("Just Now", "Fresh Artist", Relation.YouTubeRelated, "Seed", 1.0),
            cand("Last Month", "Old Artist", Relation.YouTubeRelated, "Seed", 1.0), cand("Fine", "Someone", Relation.YouTubeRelated, "Seed", 1.0),
        ), defaults()).map { it.title }.toSet()
        assertFalse(got.toString(), "Another" in got || "Just Now" in got)
        assertTrue(got.toString(), "Fine" in got && "Last Month" in got)
    }

    @Test fun rankKeepsVarietyAndTheExploreShare() {
        val plays = ArrayList<Play>(); val cands = ArrayList<Candidate<Unit>>()
        for (k in 0 until 6) {
            val artist = "Known $k"
            plays += heard("Love $artist", artist, 1)
            for (i in 0 until 6) cands += cand("$artist song $i", artist, Relation.YouTubeRelated, artist, 3.0)
        }
        for (i in 0 until 12) cands += cand("New song $i", "New artist $i", Relation.YouTubeRelated, "Known 0", 1.0)
        val o = defaults().copy(n = 20, explore = 0.3)
        val picks = Rank.rank(Taste.build(plays, emptyList(), now), cands, o)
        assertEquals(20, picks.size)
        for (start in 0..picks.size - 10) {
            val count = picks.subList(start, start + 10).groupingBy { Taste.artistKey(it.artist) }.eachCount()
            for ((a, n) in count) assertTrue("window $start: $n picks by $a", n <= o.perArtist)
        }
        val fresh = picks.count { it.fresh }
        assertTrue("$fresh fresh", fresh in 5..7)
    }

    @Test fun rankIsDeterministicAndExplains() {
        val p = Taste.build(listOf(heard("Love", "Hidra", 1)), emptyList(), now)
        val cands = (0 until 8).map { cand("Tie $it", "Artist $it", Relation.YouTubeRelated, "Hidra", 1.0) }
        val first = Rank.rank(p, cands, defaults()); val again = Rank.rank(p, cands, defaults())
        assertEquals(first.map { it.title }, again.map { it.title })
        val other = Rank.rank(p, cands, defaults().copy(salt = "another day"))
        assertNotEquals("a new salt left eight tied songs in the same order", first.map { it.title }, other.map { it.title })
        assertEquals("New to you, like Hidra", first[0].why)
    }

    @Test fun fnvMatchesGo() {
        // hash/fnv New32a over "2027-01-15\x00leyla\x00cem karaca", printed by the Go code.
        assertEquals(1423332992L, Integer.toUnsignedLong(Rank.fnv32a("2027-01-15\u0000" + Taste.trackKey("Leyla", "Cem Karaca"))))
    }

    /**
     * The same history, signals and candidates run through the desktop's Go `taste.Build` and `recommend.Rank`
     * (salt 2027-01-15, N 12, the other options the defaults) printed these artists, picks, scores and reasons. Turkish
     * names exercise the folding; a disliked artist, a song just played, a seed with no score, a similar artist with a
     * strength and both providers are among the candidates.
     */
    @Test fun sameAsTheGoCode() {
        fun d(days: Double): Instant = now.minusNanos((days * 24 * 3600 * 1e9).toLong())
        val plays = listOf(
            Play(title = "Bu Kalp Seni Unutur mu", artist = "Fikret Kızılok", at = d(1.0), playedMs = 262000, durationMs = 262000),
            Play(title = "Bu Kalp Seni Unutur mu", artist = "Fikret Kızılok", at = d(2.0), playedMs = 262000, durationMs = 262000),
            Play(title = "Gönül", artist = "Fikret Kızılok", at = d(10.0), playedMs = 100000, durationMs = 200000),
            Play(title = "Nightcall", artist = "Kavinsky", at = d(3.0), completed = true),
            Play(title = "Odd Look", artist = "Kavinsky", at = d(40.0), playedMs = 95000),
            Play(title = "Cesaretin Var mı Aşka", artist = "Gülay", at = d(0.5), playedMs = 4000, durationMs = 215000),
            Play(title = "Yolla", artist = "Tarkan", at = d(20.0), playedMs = 245000, durationMs = 245000),
            Play(title = "Şımarık", artist = "Tarkan", at = d(5.0), playedMs = 31000, durationMs = 240000),
            Play(title = "Skip Me", artist = "Hated", at = d(1.0), playedMs = 1000, durationMs = 200000),
            Play(title = "Skip Me 2", artist = "Hated", at = d(2.0), playedMs = 2000, durationMs = 200000),
        )
        val signals = listOf(
            Signal(title = "After Dark", artist = "Mr.Kitty", kind = SignalKind.Saved, at = d(60.0)),
            Signal(title = "Son Arzum", artist = "Nilüfer", kind = SignalKind.Downloaded, at = d(8.0)),
        )
        val p = Taste.build(plays, signals, now)
        val top = p.topArtists(10)
        val goTop = listOf("Fikret Kızılok" to 1.974679736453, "Nilüfer" to 1.880493228172, "Kavinsky" to 1.329883254529, "Mr.Kitty" to 1.259921049895, "Tarkan" to 0.897230140390)
        assertEquals(goTop.map { it.first }, top.map { it.name })
        top.zip(goTop).forEach { (k, g) -> assertEquals(g.first, g.second, k.score, 1e-9) }
        val seeds = top.associate { it.name to it.score }
        fun c(title: String, artist: String, rel: Relation, strength: Double, seed: String) = Candidate(title, artist, Unit, rel, strength, Seed(seed, seeds[seed] ?: 0.0))
        val y = Relation.YouTubeRelated; val s = Relation.SoundCloudRelated; val m = Relation.SimilarArtist
        val cands = listOf(
            c("Hoş Gör", "Fikret Kızılok", y, 0.0, "Fikret Kızılok"), c("Leyla", "Cem Karaca", y, 0.0, "Fikret Kızılok"),
            c("Leyla", "Cem Karaca", y, 0.0, "Tarkan"), c("Resimdeki Gözyaşları", "Cem Karaca", m, 0.8, "Fikret Kızılok"),
            c("Nightcall", "Kavinsky", y, 0.0, "Kavinsky"), c("Odd Look", "Kavinsky", y, 0.0, "Kavinsky"),
            c("Roadgame", "Kavinsky", s, 0.0, "Kavinsky"), c("Protovision", "Kavinsky", y, 0.0, "Kavinsky"),
            c("Turbo Killer", "Carpenter Brut", y, 0.0, "Kavinsky"), c("Le Perv", "Carpenter Brut", s, 0.0, "Kavinsky"),
            c("Skip Me 3", "Hated", y, 0.0, "Tarkan"), c("Kuzu Kuzu", "Tarkan", y, 0.0, "Tarkan"),
            c("Dudu", "Tarkan", y, 0.0, "Tarkan"), c("Hepsi Senin mi", "Tarkan", y, 0.0, "Tarkan"),
            c("Sen Ağlama", "Sezen Aksu", y, 0.0, "Tarkan"), c("Firuze", "Sezen Aksu", s, 0.0, "Fikret Kızılok"),
            c("Gülümse", "Sezen Aksu", y, 0.0, "Nilüfer"), c("Geceler", "Nilüfer", y, 0.0, "Nilüfer"),
            c("Esmerim", "Ajda Pekkan", y, 0.0, "Nilüfer"), c("Bambaşka Biri", "Ajda Pekkan", m, 0.5, "Nilüfer"),
            c("Disco Sensation", "Mr.Kitty", y, 0.0, "Mr.Kitty"), c("Habits", "Mr.Kitty", y, 0.0, "Mr.Kitty"),
            c("Unseen", "Nobody", y, 0.0, "Unknown Seed"),
        )
        val picks = Rank.rank(p, cands, RankOptions(now = now, n = 12, salt = "2027-01-15"))
        val go = listOf(
            listOf("Leyla", "1.353747893535", "true", "New to you, like Fikret Kızılok"),
            listOf("Hoş Gör", "1.390136381590", "false", "Because you listen to Fikret Kızılok"),
            listOf("Geceler", "1.343652407531", "false", "Because you listen to Nilüfer"),
            listOf("Gülümse", "1.057961539267", "true", "New to you, like Nilüfer"),
            listOf("Protovision", "1.047858506548", "false", "Because you listen to Kavinsky"),
            listOf("Odd Look", "1.047858506548", "false", "Because you listen to Kavinsky"),
            listOf("Esmerim", "1.057961539267", "true", "New to you, like Nilüfer"),
            listOf("Habits", "1.006741330685", "false", "Because you listen to Mr.Kitty"),
            listOf("Disco Sensation", "1.006741330685", "false", "Because you listen to Mr.Kitty"),
            listOf("Kuzu Kuzu", "0.776705228158", "false", "Because you listen to Tarkan"),
            listOf("Firuze", "0.947690087476", "true", "New to you, like Fikret Kızılok"),
            listOf("Dudu", "0.776705228158", "false", "Because you listen to Tarkan"),
        )
        assertEquals(go.map { it[0] }, picks.map { it.title })
        picks.zip(go).forEach { (k, g) ->
            assertEquals(g[0], g[1].toDouble(), k.score, 1e-9)
            assertEquals(g[0], g[2].toBoolean(), k.fresh)
            assertEquals(g[3], k.why)
        }
    }
}
