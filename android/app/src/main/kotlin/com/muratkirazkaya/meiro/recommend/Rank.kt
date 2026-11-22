package com.muratkirazkaya.meiro.recommend

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Port of `internal/recommend/recommend.go` (PORTING.md 5.3): a taste profile and a pool of candidate songs become a
 * short list, the songs most likely to please, each with a reason, and not one artist five times. It does not find the
 * candidates. The same profile, candidates, options and salt give the same list.
 */
enum class Relation(val weight: Double) {
    /** A song YouTube Music lists as related to a seed. */
    YouTubeRelated(1.0),
    /** A song SoundCloud lists as related to a seed. */
    SoundCloudRelated(0.8),
    /** A recording by an artist ListenBrainz names as similar to a seed's; its strength is the similarity. */
    SimilarArtist(0.7),
}

/** What a candidate came from: an artist the listener likes, and how much. */
data class Seed(val name: String, val score: Double)

/** A song that might be recommended, found by one relation to one seed. [ref] is the caller's own handle on it. */
data class Candidate<T>(
    val title: String,
    val artist: String,
    val ref: T,
    val relation: Relation,
    /** How strong the relation is, from 0 to 1; 0 means 1. */
    val strength: Double = 0.0,
    val seed: Seed,
)

data class Pick<T>(
    val candidate: Candidate<T>,
    val score: Double,
    /** The reason to show beside it (the desktop's English text; the phone's UI shows it in Turkish). */
    val why: String,
    /** A song by an artist the listener has not played. */
    val fresh: Boolean,
) {
    val title get() = candidate.title
    val artist get() = candidate.artist
}

data class RankOptions(
    val now: Instant,
    val n: Int = 30,
    /** The share of the picks that may be by artists new to the listener, 0..1. */
    val explore: Double = 0.3,
    /** The most picks by one artist within any ten in a row. */
    val perArtist: Int = 2,
    /** How long a song counts as just played: left out for the first half, counts for little for the rest. */
    val recent: Duration = Duration.ofDays(7),
    /** Breaks ties the same way all day: the local date for a daily list. */
    val salt: String,
) {
    companion object {
        /** `Defaults`: thirty picks, 30% new, two of an artist in ten, a week as "just played", today's date as salt. */
        fun defaults(now: Instant, zone: ZoneId = ZoneId.systemDefault()) = RankOptions(now = now, salt = now.atZone(zone).toLocalDate().toString())
    }
}

object Rank {
    fun <T> rank(p: Profile, candidates: List<Candidate<T>>, o0: RankOptions): List<Pick<T>> {
        if (o0.n <= 0) return emptyList()
        val o = if (o0.perArtist <= 0) o0.copy(perArtist = 2) else o0
        class Pooled(var candidate: Candidate<T>, var best: Double = 0.0, var total: Double = 0.0)
        val pool = LinkedHashMap<String, Pooled>()
        for (c in candidates) {
            val key = Taste.trackKey(c.title, c.artist)
            if (key.isEmpty() || c.seed.score <= 0) continue
            val strength = if (c.strength <= 0) 1.0 else c.strength
            val contribution = c.seed.score * c.relation.weight * strength
            val e = pool.getOrPut(key) { Pooled(c) }
            e.total += contribution
            // The largest single contribution names the reason; the first wins a tie.
            if (contribution > e.best) { e.best = contribution; e.candidate = c }
        }
        val maxArtist = p.topArtists(1).firstOrNull()?.score ?: 0.0
        val scored = ArrayList<Pick<T>>(pool.size)
        for (e in pool.values) {
            val c = e.candidate
            if (skip(p, c, o)) continue
            // Several seeds finding the same song count for more, with a falling return.
            var score = Math.log1p(e.total)
            if (maxArtist > 0) score += 0.3 * maxOf(0.0, p.artist(c.artist)) / maxArtist
            val last = p.lastPlayed(c.title, c.artist)
            if (last != null && Duration.between(last, o.now) < o.recent) score *= 0.1
            val fresh = !p.known(c.artist)
            scored.add(Pick(c, score, why(fresh, c.seed.name), fresh))
        }
        val sorted = scored.sortedWith { a, b ->
            if (a.score != b.score) b.score.compareTo(a.score)
            else Integer.compareUnsigned(tie(o.salt, a), tie(o.salt, b))
        }
        return choose(sorted, o)
    }

    private fun <T> skip(p: Profile, c: Candidate<T>, o: RankOptions): Boolean {
        if (p.track(c.title, c.artist) < 0 || p.artist(c.artist) < -0.5) return true
        if (p.skipRate(c.artist) > 0.6 && p.known(c.artist) && p.artist(c.artist) <= 0) return true
        val last = p.lastPlayed(c.title, c.artist)
        if (last != null && Duration.between(last, o.now) < o.recent.dividedBy(2)) return true
        return false
    }

    fun why(fresh: Boolean, seed: String) = if (fresh) "New to you, like $seed" else "Because you listen to $seed"

    /** FNV-1a 32 of salt + NUL + the song's key: orders two picks of one score the same way for a whole day. */
    fun <T> tie(salt: String, c: Pick<T>): Int = fnv32a(salt + "\u0000" + Taste.trackKey(c.title, c.artist))

    fun fnv32a(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xff)
            h *= 0x01000193
        }
        return h
    }

    /** Greedy: at each place the best allowed pick (at most [RankOptions.perArtist] of an artist in the last 9 + it), a new artist's when the new ones are behind. */
    private fun <T> choose(scored: List<Pick<T>>, o: RankOptions): List<Pick<T>> {
        val out = ArrayList<Pick<T>>()
        val used = BooleanArray(scored.size)
        var fresh = 0
        fun allowed(c: Pick<T>): Boolean {
            var count = 0
            var i = out.size - 1
            while (i >= 0 && i >= out.size - 9) {
                if (Taste.artistKey(out[i].artist) == Taste.artistKey(c.artist)) count++
                i--
            }
            return count < o.perArtist
        }
        fun next(wantFresh: Boolean): Int {
            for (i in scored.indices) if (!used[i] && scored[i].fresh == wantFresh && allowed(scored[i])) return i
            return -1
        }
        while (out.size < o.n) {
            val wantFresh = fresh.toDouble() < o.explore * (out.size + 1)
            var i = next(wantFresh)
            if (i < 0) i = next(!wantFresh)
            if (i < 0) break
            used[i] = true
            out.add(scored[i])
            if (scored[i].fresh) fresh++
        }
        return out
    }
}
