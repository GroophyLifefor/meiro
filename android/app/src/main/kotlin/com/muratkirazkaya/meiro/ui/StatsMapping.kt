package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.History
import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.sync.StatsArtist
import com.muratkirazkaya.meiro.sync.StatsBucket
import com.muratkirazkaya.meiro.sync.StatsSummary
import com.muratkirazkaya.meiro.sync.StatsTotals
import com.muratkirazkaya.meiro.sync.StatsTrack
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

enum class StatsPeriod { Month, Year }

/** One bar of "Günlere göre" / "Aylara göre": its height (0..1 of the chart) and whether it is a quiet one (`.lo`). */
data class StatBar(val height: Float, val lo: Boolean)

/** One line of a top list (`.rank`): its name, the line under it, the cover (round for an artist). */
data class RankRow(val title: String, val sub: String, val cover: CoverSpec)

/** Mock screen `stats` ("Özet"). */
data class StatsUi(
    val monthLabel: String,
    val yearLabel: String,
    val period: StatsPeriod,
    /** The four tiles: value and label ("41 sa" / "dinleme", "612" / "çalma", ...). */
    val tiles: List<Pair<String, String>>,
    val bars: List<StatBar>,
    val artists: List<RankRow>,
    val tracks: List<RankRow>,
    val note: String,
)

/**
 * "Özet": the account's summary from the server (`GET /api/v1/stats/summary`, every device) when signed in, otherwise
 * (and when the server cannot be reached) the same numbers folded from this device's history, the desktop's way
 * (`history.Summarize`: counted plays only). How many of the period's artists are new is always folded from the history
 * this device holds (the server does not say).
 */
object StatsMapping {
    private val TR = Locale.forLanguageTag("tr")
    /** How many artists and tracks the top lists name (the desktop's `top(…, 5)`). */
    const val TOP = 5

    fun anchor(period: StatsPeriod, now: ZonedDateTime): String =
        if (period == StatsPeriod.Year) "%04d".format(now.year) else "%04d-%02d".format(now.year, now.monthValue)

    /** The period's first and last-plus-one day, in the phone's zone. */
    fun range(period: StatsPeriod, now: ZonedDateTime): Pair<LocalDate, LocalDate> =
        if (period == StatsPeriod.Year) LocalDate.of(now.year, 1, 1).let { it to it.plusYears(1) }
        else YearMonth.from(now).atDay(1).let { it to it.plusMonths(1) }

    /** The summary folded from the history, in the server's shape (counted plays, the period in the phone's zone). */
    fun local(history: List<HistoryEntry>, period: StatsPeriod, now: ZonedDateTime): StatsSummary {
        val zone = now.zone
        val (start, end) = range(period, now)
        val plays = history.filter { History.counted(it) && it.playedAt.atZone(zone).toLocalDate().let { d -> !d.isBefore(start) && d.isBefore(end) } }
        val tracks = plays.groupBy { it.title to it.artist }
            .map { (k, l) -> StatsTrack(k.first, k.second, l.size, l.sumOf { it.playedMs }) }
            .sortedWith(compareByDescending<StatsTrack> { it.plays }.thenByDescending { it.playedMs })
        val artists = plays.groupBy { it.artist }
            .map { (a, l) -> StatsArtist(a, l.size, l.sumOf { it.playedMs }) }
            .sortedWith(compareByDescending<StatsArtist> { it.plays }.thenByDescending { it.playedMs })
        val buckets = plays.groupBy {
            val d = it.playedAt.atZone(zone).toLocalDate()
            if (period == StatsPeriod.Year) "%04d-%02d".format(d.year, d.monthValue) else d.toString()
        }.map { (b, l) -> StatsBucket(b, l.sumOf { it.playedMs }) }.sortedBy { it.bucket }
        return StatsSummary(
            StatsTotals(
                plays.sumOf { it.playedMs }, plays.size,
                plays.map { e -> if (e.videoId.isNotEmpty()) e.source + ":" + e.videoId else (e.title.lowercase(TR) + "|" + e.artist.lowercase(TR)) }.toSet().size,
                plays.map { it.artist.lowercase(TR) }.toSet().size,
            ),
            tracks.take(10), artists.take(10), buckets,
        )
    }

    /** The period's artists whose first counted play this device knows of falls inside the period. */
    fun newArtists(history: List<HistoryEntry>, period: StatsPeriod, now: ZonedDateTime): Int {
        val zone = now.zone
        val (start, end) = range(period, now)
        val first = HashMap<String, LocalDate>()
        for (e in history) {
            if (!History.counted(e) || e.artist.isBlank()) continue
            val a = e.artist.lowercase(TR)
            val d = e.playedAt.atZone(zone).toLocalDate()
            val f = first[a]
            if (f == null || d.isBefore(f)) first[a] = d
        }
        return first.values.count { !it.isBefore(start) && it.isBefore(end) }
    }

    /** "41 sa" for an hour or more of listening, else "45 dk". */
    fun listened(ms: Long): String = if (ms >= 3_600_000) "${ms / 3_600_000} sa" else "${ms / 60_000} dk"

    /** A count with Turkish thousands separators ("5.940"). */
    fun number(n: Int): String = String.format(TR, "%,d", n)

    /** "Ekim 2026" and "2026". */
    fun monthLabel(now: ZonedDateTime) = now.month.getDisplayName(TextStyle.FULL_STANDALONE, TR).replaceFirstChar { it.titlecase(TR) } + " " + now.year

    /** The chart: one bar per day of the month or per month of the year, scaled to the busiest; a day with no plays is a quiet stub. */
    fun bars(series: List<StatsBucket>, period: StatsPeriod, now: ZonedDateTime): List<StatBar> {
        val n = if (period == StatsPeriod.Year) 12 else YearMonth.from(now).lengthOfMonth()
        val values = LongArray(n)
        for (b in series) {
            val i = if (period == StatsPeriod.Year) b.bucket.substringAfter('-').toIntOrNull()?.minus(1)
            else b.bucket.substringAfterLast('-').toIntOrNull()?.minus(1)
            if (i != null && i in 0 until n) values[i] += b.playedMs
        }
        val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
        return values.map { v -> if (v <= 0) StatBar(QUIET, true) else StatBar((v.toDouble() / max).toFloat().coerceAtLeast(QUIET), false) }
    }

    /** The height of a bar with nothing in it. */
    const val QUIET = 0.04f

    private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)

    fun ui(
        s: StatsSummary, newArtists: Int, period: StatsPeriod, now: ZonedDateTime, signedIn: Boolean,
        /** A cover for a track (its picture from the history), by title and artist. */
        cover: (String, String) -> String? = { _, _ -> null },
    ): StatsUi = StatsUi(
        monthLabel = monthLabel(now), yearLabel = now.year.toString(), period = period,
        tiles = listOf(
            listened(s.totals.playedMs) to "dinleme",
            number(s.totals.plays) to "çalma",
            number(s.totals.uniqueTracks) to "farklı şarkı",
            number(s.totals.uniqueArtists) to "sanatçı · ${number(newArtists)} yeni",
        ),
        bars = bars(s.series, period, now),
        artists = s.topArtists.take(TOP).map { RankRow(it.artist.ifEmpty { "Bilinmeyen sanatçı" }, "${number(it.plays)} çalma", CoverSpec(null, hue(it.artist))) },
        tracks = s.topTracks.take(TOP).map {
            RankRow(
                it.title.ifEmpty { "Bilinmeyen şarkı" },
                listOf(it.artist, "${number(it.plays)} çalma").filter { p -> p.isNotEmpty() }.joinToString(" · "),
                CoverSpec(cover(it.title, it.artist), hue(it.title + it.artist)),
            )
        },
        note = if (signedIn) "Bütün cihazlardaki dinlemeler, Meiro hesabından. Aynı özet dashboard'da da var." else "Bu cihazdaki dinlemeler.",
    )

    /** The picture of a track, from the newest play of it this device holds that has one. */
    fun coverIndex(history: List<HistoryEntry>): (String, String) -> String? {
        val m = HashMap<Pair<String, String>, String>()
        for (e in history) if (e.thumbnail.startsWith("http")) m[e.title to e.artist] = e.thumbnail
        return { t, a -> m[t to a] }
    }
}
