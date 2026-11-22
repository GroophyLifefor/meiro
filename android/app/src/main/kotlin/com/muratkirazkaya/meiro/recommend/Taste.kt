package com.muratkirazkaya.meiro.recommend

import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.Match
import java.time.Duration
import java.time.Instant

/**
 * Port of `internal/taste/taste.go` (PORTING.md 5.2): what the listener likes, from what they played and saved and
 * nothing else. A play is a vote: heard through is for the song and its artist, left early is against; a playlist and
 * a download are stronger votes for. Votes fade with age, and each further hearing of one song counts for less. A
 * profile is a pure function of the history, the signals and the clock.
 */
data class Play(
    val provider: String = "",
    val id: String = "",
    val title: String,
    val artist: String,
    val at: Instant,
    /** How much was heard and the song's length, in milliseconds; [durationMs] is 0 when not known. */
    val playedMs: Long = 0,
    val durationMs: Long = 0,
    val completed: Boolean = false,
)

/** What a listener did with a song besides playing it. */
enum class SignalKind { Like, Saved, Downloaded }

data class Signal(
    val provider: String = "",
    val id: String = "",
    val title: String,
    val artist: String,
    val kind: SignalKind,
    val at: Instant,
)

/** One artist or song and how much it is liked. */
data class Entry(val key: String, val name: String, val score: Double)

class Profile private constructor(
    val now: Instant,
    private val artists: Map<String, Double>,
    private val tracks: Map<String, Double>,
    private val names: Map<String, String>,
    private val last: Map<String, Instant>,
    private val plays: Map<String, Int>,
    private val left: Map<String, Int>,
) {
    /** The score of an artist, 0 when nothing is known of them. */
    fun artist(artist: String): Double = artists[Taste.artistKey(artist)] ?: 0.0

    /** The score of a song, 0 when nothing is known of it. */
    fun track(title: String, artist: String): Double = tracks[Taste.trackKey(title, artist)] ?: 0.0

    /** Whether the listener has played or acted on the artist (even a negative score). */
    fun known(artist: String): Boolean = artists.containsKey(Taste.artistKey(artist))

    /** When a song was last played, or null when it never was. */
    fun lastPlayed(title: String, artist: String): Instant? = last[Taste.trackKey(title, artist)]

    /** The share of an artist's plays left early, 0 for an artist never played. */
    fun skipRate(artist: String): Double {
        val key = Taste.artistKey(artist)
        val n = plays[key] ?: 0
        if (n == 0) return 0.0
        return (left[key] ?: 0).toDouble() / n
    }

    /** The [n] artists liked most, best first; one scored at or below zero is not among them. */
    fun topArtists(n: Int): List<Entry> = artists.filter { it.value > 0 }
        .map { Entry(it.key, names[it.key].orEmpty(), it.value) }
        .sortedWith(byScoreThenKey).take(n)

    /** The [n] songs liked most, best first, named "title by artist" from the folded key. */
    fun songs(n: Int): List<Entry> = tracks.filter { it.value > 0 }
        .map { val (t, a) = it.key.split('\u0000', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }; Entry(it.key, "$t by $a", it.value) }
        .sortedWith(byScoreThenKey).take(n)

    /** Nothing is known: no plays and no signals counted. */
    fun empty(): Boolean = artists.isEmpty() && tracks.isEmpty()

    companion object {
        private val byScoreThenKey = Comparator<Entry> { a, b ->
            if (a.score != b.score) b.score.compareTo(a.score) else LibraryStore.compareUtf8(a.key, b.key)
        }

        internal fun build(plays: List<Play>, signals: List<Signal>, now: Instant): Profile {
            val artists = HashMap<String, Double>(); val tracks = HashMap<String, Double>()
            val names = HashMap<String, String>(); val last = HashMap<String, Instant>()
            val count = HashMap<String, Int>(); val left = HashMap<String, Int>()
            val heard = HashMap<String, Int>()
            for (play in plays.sortedBy { it.at }) {
                if (play.at.isAfter(now)) continue
                val key = Taste.trackKey(play.title, play.artist)
                if (key.isEmpty()) continue
                val artist = Taste.artistKey(play.artist)
                var vote = Taste.voteOf(play)
                if (vote > 0) {
                    // Each further hearing counts for less than the one before.
                    vote *= Math.pow(Taste.REPEAT, (heard[key] ?: 0).toDouble())
                    heard[key] = (heard[key] ?: 0) + 1
                }
                val weight = vote * Taste.decay(Duration.between(play.at, now), Taste.HALF_LIFE)
                tracks[key] = (tracks[key] ?: 0.0) + weight
                if (artist.isNotEmpty()) {
                    artists[artist] = (artists[artist] ?: 0.0) + weight
                    names[artist] = play.artist
                    count[artist] = (count[artist] ?: 0) + 1
                    if (vote < 0) left[artist] = (left[artist] ?: 0) + 1
                }
                val prev = last[key]
                if (prev == null || play.at.isAfter(prev)) last[key] = play.at
            }
            for (s in signals) {
                if (s.at.isAfter(now)) continue
                val key = Taste.trackKey(s.title, s.artist)
                if (key.isEmpty()) continue
                val vote = when (s.kind) { SignalKind.Like -> Taste.VOTE_LIKE; SignalKind.Saved -> Taste.VOTE_SAVED; SignalKind.Downloaded -> Taste.VOTE_DOWNLOADED }
                val weight = vote * Taste.decay(Duration.between(s.at, now), Taste.HALF_LIFE.multipliedBy(Taste.EXPLICIT_SLOWDOWN))
                tracks[key] = (tracks[key] ?: 0.0) + weight
                val artist = Taste.artistKey(s.artist)
                if (artist.isNotEmpty()) {
                    artists[artist] = (artists[artist] ?: 0.0) + weight
                    if (names[artist].isNullOrEmpty()) names[artist] = s.artist
                }
            }
            return Profile(now, artists, tracks, names, last, count, left)
        }
    }
}

object Taste {
    const val VOTE_HEARD = 1.0
    const val VOTE_PARTLY = 0.3
    const val VOTE_SKIPPED = -0.7
    const val VOTE_LIKE = 3.0
    const val VOTE_SAVED = 2.0
    const val VOTE_DOWNLOADED = 2.0
    const val REPEAT = 0.8
    val HALF_LIFE: Duration = Duration.ofDays(30)
    const val EXPLICIT_SLOWDOWN = 3L

    /** The folded title and artist, so one recording on two providers is one song; "" for a song with no title. */
    fun trackKey(title: String, artist: String): String {
        val t = Match.normalize(title)
        if (t.isEmpty()) return ""
        return t + "\u0000" + Match.normalize(artist)
    }

    fun artistKey(artist: String): String = Match.normalize(artist)

    fun build(plays: List<Play>, signals: List<Signal>, now: Instant): Profile = Profile.build(plays, signals, now)

    /** What one play says (`voteOf`): heard through, heard in part, or left early. */
    fun voteOf(play: Play): Double {
        if (play.completed) return VOTE_HEARD
        if (play.durationMs > 0) {
            val ratio = play.playedMs.toDouble() / play.durationMs.toDouble()
            return when {
                ratio >= 0.8 -> VOTE_HEARD
                ratio >= 0.3 || play.playedMs >= 30_000 -> VOTE_PARTLY
                else -> VOTE_SKIPPED
            }
        }
        if (play.playedMs >= 90_000) return VOTE_HEARD
        if (play.playedMs >= 30_000) return VOTE_PARTLY
        return VOTE_SKIPPED
    }

    /** The share of a vote left after it has aged by [d]. */
    fun decay(d: Duration, halfLife: Duration): Double {
        if (d.isZero || d.isNegative) return 1.0
        return Math.pow(0.5, d.toNanos().toDouble() / halfLife.toNanos().toDouble())
    }
}
