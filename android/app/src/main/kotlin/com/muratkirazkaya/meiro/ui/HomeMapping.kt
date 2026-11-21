package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.data.isZero
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.clockText
import com.muratkirazkaya.meiro.sync.Ids
import com.muratkirazkaya.meiro.sync.LinksStore
import com.muratkirazkaya.meiro.sync.libraryServerId
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.components.TileData
import com.muratkirazkaya.meiro.ui.screens.HomeUi
import com.muratkirazkaya.meiro.ui.screens.MixUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the home screen shows, and what its taps play or open. */
data class HomeData(
    val ui: HomeUi,
    /** The list each "Hızlı seçimler" tile opens. */
    val pickIds: List<String> = emptyList(),
    /** The track each "Kaldığın yerden" row plays. */
    val resume: List<QItem> = emptyList(),
    /** The Daily mix's tracks, in order (its play button), and the list's id. */
    val mixItems: List<QItem> = emptyList(),
    val mixId: String? = null,
)

/** Builds the home screen from the local store: the account, the listening history, the lists and the Daily mix. */
object HomeMapping {
    private val Turkish = Locale.forLanguageTag("tr")
    const val MAX_PICKS = 6
    const val MAX_RESUME = 3

    /** The desktop's `greeting` (routes.go), in Turkish: before 5 the night, before 12 the morning, before 18 the day. */
    fun greeting(hour: Int): String = when {
        hour < 5 -> "İyi geceler"
        hour < 12 -> "Günaydın"
        hour < 18 -> "İyi günler"
        else -> "İyi akşamlar"
    }

    /**
     * The name the greeting uses. The Meiro server keeps no name, only the email: its first word before the "@"
     * ("murat.kirazkaya@..." gives "Murat"); null without an account.
     */
    fun firstName(email: String): String? {
        val local = email.substringBefore('@').trim()
        val word = local.split('.', '_', '-', '+').firstOrNull { it.isNotBlank() } ?: return null
        return word.lowercase(Turkish).replaceFirstChar { it.titlecase(Turkish) }
    }

    /** "İyi akşamlar, Murat", or the greeting alone when there is no account. */
    fun greetingLine(hour: Int, email: String): String = firstName(email)?.let { "${greeting(hour)}, $it" } ?: greeting(hour)

    /** Time heard today (local calendar day), whichever device played it: the sum of each play's time heard. */
    fun listenedTodayMs(history: List<HistoryEntry>, today: LocalDate, zone: ZoneId): Long =
        history.filter { it.playedAt.atZone(zone).toLocalDate() == today }.sumOf { it.playedMs.coerceAtLeast(0) }

    fun minutesLine(ms: Long) = "Bugün ${ms / 60_000} dakika dinledin"

    private fun isUrl(s: String) = s.startsWith("https://") || s.startsWith("http://")
    private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)

    /** The stored Daily mix: the list the settings name, else the list under the mix's shared server id. */
    fun dailyMix(libs: List<Library>, dailyMixId: String): Library? =
        libs.firstOrNull { dailyMixId.isNotEmpty() && it.id == dailyMixId }
            ?: libs.firstOrNull { libraryServerId(it.id, it.serverId) == Ids.dailyMixId() }

    /**
     * The Daily mix card, or null when there is no mix with tracks (the app does not make one yet; a mix made by the
     * desktop arrives with the sync). The kicker says "bugün" when its tracks were put there today, else the day.
     */
    fun mix(lib: Library?, history: List<HistoryEntry>, others: List<Library>, today: LocalDate, zone: ZoneId): MixUi? {
        if (lib == null || lib.items.isEmpty()) return null
        val made = lib.items.maxOf { it.updatedAt }.let { if (it.isZero) lib.updatedAt else it }
        val day = made.atZone(zone).toLocalDate()
        val kicker = "Daily mix · " + when (day) {
            today -> "bugün"
            today.minusDays(1) -> "dün"
            else -> day.format(DateTimeFormatter.ofPattern("d MMMM", Turkish))
        }
        return MixUi(kicker, "${lib.items.size} şarkı", artistsLine(lib.items, history, others), lib.items.take(4).map { it.cover() })
    }

    /**
     * "Fikret Kızılok, Gülay, Kavinsky ve 9 yeni sanatçı": the first three artists of the mix, then how many of the
     * others are new to the account (in no other list and never played). Without new ones only the names are given.
     */
    fun artistsLine(items: List<Item>, history: List<HistoryEntry>, others: List<Library>): String {
        val artists = items.map { it.artist.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Turkish) }
        val named = artists.take(3)
        val known = HashSet<String>()
        for (e in history) known.add(e.artist.trim().lowercase(Turkish))
        for (l in others) for (i in l.items) known.add(i.artist.trim().lowercase(Turkish))
        val fresh = artists.drop(3).count { it.lowercase(Turkish) !in known }
        if (named.isEmpty()) return ""
        if (fresh > 0) return named.joinToString(", ") + " ve $fresh yeni sanatçı"
        return if (named.size == 1) named[0] else named.dropLast(1).joinToString(", ") + " ve " + named.last()
    }

    private fun Item.cover() = CoverSpec(thumbnail.ifEmpty { albumThumbnail }.takeIf { isUrl(it) }, hue(activeRef().second))

    /**
     * "Hızlı seçimler": the Meiro lists other than the Daily mix, the most recently used first (the later of the list's
     * last change and the last play of one of its tracks), at most [MAX_PICKS].
     */
    fun picks(libs: List<Library>, mixId: String?, history: List<HistoryEntry>): List<Library> {
        val lastPlay = HashMap<String, Instant>()
        for (e in history) {
            val k = e.source.ifEmpty { Source.YTM } + "\u0000" + e.videoId
            val prev = lastPlay[k]
            if (prev == null || e.playedAt.isAfter(prev)) lastPlay[k] = e.playedAt
        }
        fun used(l: Library): Instant {
            var t = l.updatedAt
            for (i in l.items) {
                val (src, id) = i.activeRef()
                lastPlay[src.ifEmpty { Source.YTM } + "\u0000" + id]?.let { if (it.isAfter(t)) t = it }
            }
            return t
        }
        return libs.filter { it.id != mixId }.sortedByDescending { used(it) }.take(MAX_PICKS)
    }

    fun Library.toTile() = TileData(id, name, "Meiro listesi · ${items.size}", coverSpec(), Provider.Meiro)

    /** "Kaldığın yerden": the last [MAX_RESUME] different tracks of the history, newest first. */
    fun resume(history: List<HistoryEntry>): List<HistoryEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<HistoryEntry>()
        for (e in history.asReversed()) {
            if (e.videoId.isEmpty()) continue
            if (!seen.add(e.source.ifEmpty { Source.YTM } + "\u0000" + e.videoId)) continue
            out.add(e)
            if (out.size == MAX_RESUME) break
        }
        return out
    }

    /** The stored list track for a play, when a list holds it (it knows the track's other sources and its link). */
    private fun listed(e: HistoryEntry, libs: List<Library>): Item? {
        val src = e.source.ifEmpty { Source.YTM }
        for (l in libs) for (i in l.items) {
            val (s, id) = i.activeRef()
            if (s.ifEmpty { Source.YTM } == src && id == e.videoId) return i
            if (i.sources.any { it.source == src && (it.id == e.videoId || it.url == e.videoId) }) return i
        }
        return null
    }

    fun HistoryEntry.toQItem(): QItem = QItem(
        source.ifEmpty { Source.YTM }, videoId, title, artist, if (durationMs > 0) clockText(durationMs) else "",
        thumbnail.takeIf { isUrl(it) }.orEmpty(),
    )

    fun HistoryEntry.toSongRow(libs: List<Library>, links: LinksStore?, playingKey: String?): SongRowData {
        val item = listed(this, libs)
        if (item != null) {
            // The list's row, but as this play: played from this source, with this play's picture when the list has none.
            val row = item.toSongRow(links, playingKey)
            val src = source.ifEmpty { Source.YTM }
            val length = item.durationMs.takeIf { it > 0 } ?: durationMs
            return row.copy(
                key = videoId, activeYtm = src != Source.SOUNDCLOUD,
                subtitle = listOf(item.artist.ifEmpty { artist }, if (length > 0) clockText(length) else "").filter { it.isNotEmpty() }.joinToString(" · "),
                ytm = row.ytm || src == Source.YTM, sc = row.sc || src == Source.SOUNDCLOUD,
                cover = if (row.cover.url == null && isUrl(thumbnail)) CoverSpec(thumbnail, hue(videoId)) else row.cover,
                now = playingKey != null && videoId == playingKey,
            )
        }
        val src = source.ifEmpty { Source.YTM }
        val state = links?.song(src, videoId)?.state?.takeIf { it.isNotEmpty() } ?: LinkState.UNKNOWN
        return SongRowData(
            key = videoId,
            title = title.ifEmpty { videoId },
            subtitle = listOf(artist, if (durationMs > 0) clockText(durationMs) else "").filter { it.isNotEmpty() }.joinToString(" · "),
            ytm = src == Source.YTM, sc = src == Source.SOUNDCLOUD, activeYtm = src != Source.SOUNDCLOUD,
            cover = CoverSpec(thumbnail.takeIf { isUrl(it) }, hue(videoId)),
            link = when (state) { LinkState.MATCHED -> LinkBadge.None; LinkState.OTHER -> LinkBadge.Other; else -> LinkBadge.Unknown },
            now = playingKey != null && videoId == playingKey,
        )
    }

    fun build(
        email: String, now: ZonedDateTime, history: List<HistoryEntry>, libs: List<Library>, links: LinksStore?,
        dailyMixId: String, playingKey: String?,
    ): HomeData {
        val zone = now.zone
        val today = now.toLocalDate()
        val mixLib = dailyMix(libs, dailyMixId)
        val others = libs.filter { it.id != mixLib?.id }
        val mix = mix(mixLib, history, others, today, zone)
        val picks = picks(libs, mixLib?.id, history)
        val resume = resume(history)
        return HomeData(
            ui = HomeUi(
                greeting = greetingLine(now.hour, email),
                minutes = minutesLine(listenedTodayMs(history, today, zone)),
                mix = mix,
                picks = picks.map { it.toTile() },
                resume = resume.map { it.toSongRow(libs, links, playingKey) },
            ),
            pickIds = picks.map { it.id },
            resume = resume.map { it.toQItem() },
            mixItems = if (mix != null) mixLib!!.items.map { it.toQItem() } else emptyList(),
            mixId = mixLib?.id,
        )
    }
}
