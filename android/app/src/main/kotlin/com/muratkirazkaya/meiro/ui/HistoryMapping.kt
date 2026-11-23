package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.History
import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.clockText
import com.muratkirazkaya.meiro.sync.LinksStore
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.SongRowData
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One chip of the history page: "Hepsi", "Sayılanlar", "Geçilenler", then one per device ("Telefon" is this one). */
data class HistoryChip(val key: String, val label: String)

/** A line of the history page: a day's heading ("Bugün", "5 çalma · ~16 dk") or one play as a song row. */
sealed class HistoryLine {
    data class Day(val title: String, val sub: String) : HistoryLine()
    data class Play(val row: SongRowData, val item: QItem) : HistoryLine()
}

data class HistoryUi(
    val chips: List<HistoryChip>,
    val filter: String,
    val lines: List<HistoryLine>,
    val note: String,
    /** The search bar under the top bar is open, and what it holds. */
    val searching: Boolean = false,
    val query: String = "",
    /** No play at all (the history is empty), as opposed to no play matching the filter or the search. */
    val empty: Boolean = false,
)

/**
 * The listening history page (`historypage.go`): every play, newest first, day by day, from every device; a chip keeps
 * the counted plays, the skipped ones or one device's; the search keeps plays whose title or artist holds the text.
 */
object HistoryMapping {
    const val ALL = "all"
    const val COUNTED = "counted"
    const val SKIPPED = "skipped"
    const val DEVICE = "device:"

    /** What this device is called in the history (the desktop's "This computer"). */
    const val THIS_DEVICE = "telefon"

    private val TR = Locale.forLanguageTag("tr")
    private val DAY = DateTimeFormatter.ofPattern("d MMMM EEEE", TR)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", TR)

    /** The name a play's device is shown under: "telefon" for this one (or a play from before plays named one). */
    fun deviceName(e: HistoryEntry, thisDevice: String): String =
        if (e.device.isEmpty() || e.device == thisDevice) THIS_DEVICE else e.device

    /** `historyDay`: Bugün, Dün, or the date ("27 Ekim Salı"). */
    fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
        today -> "Bugün"
        today.minusDays(1) -> "Dün"
        else -> day.format(DAY).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(TR) } }
    }

    /** `historyTotals`: how many plays the page shows of the day, and about how long the counted ones were heard. */
    fun dayTotals(plays: Int, heardMs: Long): String {
        var minutes = (heardMs + 30_000) / 60_000
        if (heardMs > 0 && minutes == 0L) minutes = 1
        return "$plays çalma · ~$minutes dk"
    }

    /** `historyNote`: when the play began, where, and how much of it was heard. */
    fun note(e: HistoryEntry, counted: Boolean, device: String, playing: Boolean, zone: ZoneId): String {
        val parts = mutableListOf(e.playedAt.atZone(zone).format(TIME), device)
        when {
            playing -> parts += "çalıyor"
            e.eventId.isEmpty() && e.playedMs == 0L -> {} // recorded before plays were timed
            !counted -> { parts += "geçildi"; parts += clockText(e.playedMs) }
            e.completed -> parts += "tamamı"
            e.durationMs > 0 -> parts += "${clockText(e.playedMs)} / ${clockText(e.durationMs)}"
            else -> parts += clockText(e.playedMs)
        }
        return parts.joinToString(" · ")
    }

    /** Folds text for the search: Turkish lower case. */
    fun fold(s: String) = s.lowercase(TR)

    fun build(
        history: List<HistoryEntry>, filter: String, query: String, searching: Boolean, now: ZonedDateTime,
        thisDevice: String, libs: List<Library>, links: LinksStore?, playingKey: String?, signedIn: Boolean,
    ): HistoryUi {
        val zone = now.zone
        val today = now.toLocalDate()
        val q = fold(query.trim())
        val index = listIndex(libs)
        // The play in progress: the newest play of the track that plays, not filed yet (no time heard).
        val playingAt = if (playingKey == null) -1
        else history.indexOfLast { it.videoId == playingKey }.takeIf { it >= 0 && history[it].playedMs == 0L && !history[it].completed } ?: -1
        val devices = LinkedHashSet<String>()
        val lines = ArrayList<HistoryLine>()
        var dayIdx = -1; var day: LocalDate? = null; var dayPlays = 0; var dayHeard = 0L
        fun closeDay() { if (dayIdx >= 0) lines[dayIdx] = HistoryLine.Day(dayTitle(day!!, today), dayTotals(dayPlays, dayHeard)) }
        for (i in history.indices.reversed()) {
            val e = history[i]
            val device = deviceName(e, thisDevice)
            devices += device
            val counted = History.counted(e)
            val keep = when {
                filter == COUNTED -> counted
                filter == SKIPPED -> !counted
                filter.startsWith(DEVICE) -> filter == DEVICE + device
                else -> true
            }
            if (!keep) continue
            if (q.isNotEmpty() && q !in fold(e.title + " " + e.artist)) continue
            val d = e.playedAt.atZone(zone).toLocalDate()
            if (d != day) {
                closeDay()
                lines += HistoryLine.Day("", ""); dayIdx = lines.size - 1; day = d; dayPlays = 0; dayHeard = 0
            }
            dayPlays++
            if (counted) dayHeard += e.playedMs
            val playing = i == playingAt
            lines += HistoryLine.Play(row(e, index, links, playingKey, playing, counted, device, zone), item(e))
        }
        closeDay()
        val chips = listOf(HistoryChip(ALL, "Hepsi"), HistoryChip(COUNTED, "Sayılanlar"), HistoryChip(SKIPPED, "Geçilenler")) +
            devices.sortedWith(compareBy<String> { it != THIS_DEVICE }.thenBy { it }).map { HistoryChip(DEVICE + it, if (it == THIS_DEVICE) "Telefon" else it) }
        return HistoryUi(chips, filter, lines, note(signedIn), searching, query, empty = history.isEmpty())
    }

    /** `historyNoteRow`: what the faded rows are. */
    fun note(signedIn: Boolean): String =
        (if (signedIn) "Bütün cihazlardaki çalmalar, Meiro hesabından. " else "Bu cihazdaki çalmalar. ") +
            "30 saniyeden kısa ya da yarısından az dinlenenler soluk: geçmişte durur ama Özet'e ve önerilere sayılmaz."

    private fun isUrl(s: String) = s.startsWith("https://") || s.startsWith("http://")
    private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)

    /** The stored list tracks by provider and id, so a play can show what its list knows (other sources, link). */
    private fun listIndex(libs: List<Library>): Map<String, Item> {
        val m = HashMap<String, Item>()
        for (l in libs) for (it in l.items) {
            val (s, id) = it.activeRef()
            m.putIfAbsent(s.ifEmpty { Source.YTM } + "\u0000" + id, it)
            for (r in it.sources) {
                if (r.id.isNotEmpty()) m.putIfAbsent(r.source + "\u0000" + r.id, it)
                if (r.url.isNotEmpty()) m.putIfAbsent(r.source + "\u0000" + r.url, it)
            }
        }
        return m
    }

    /** The song a play plays again (`historyItem`): its own source and id, one track. */
    fun item(e: HistoryEntry): QItem = QItem(
        e.source.ifEmpty { Source.YTM }, e.videoId, e.title.ifEmpty { "Bilinmeyen şarkı" }, e.artist,
        if (e.durationMs > 0) clockText(e.durationMs) else "", e.thumbnail.takeIf { isUrl(it) }.orEmpty(),
    )

    private fun row(
        e: HistoryEntry, index: Map<String, Item>, links: LinksStore?, playingKey: String?, playing: Boolean, counted: Boolean,
        device: String, zone: ZoneId,
    ): SongRowData {
        val src = e.source.ifEmpty { Source.YTM }
        val listed = index[src + "\u0000" + e.videoId]
        val note = note(e, counted, device, playing, zone)
        val base = if (listed != null) {
            val r = listed.toSongRow(links, playingKey)
            r.copy(
                ytm = r.ytm || src == Source.YTM, sc = r.sc || src == Source.SOUNDCLOUD,
                cover = if (r.cover.url == null && isUrl(e.thumbnail)) CoverSpec(e.thumbnail, hue(e.videoId)) else r.cover,
            )
        } else {
            val state = links?.song(src, e.videoId)?.state?.takeIf { it.isNotEmpty() } ?: LinkState.UNKNOWN
            SongRowData(
                key = e.videoId, title = "", subtitle = "", ytm = src == Source.YTM, sc = src == Source.SOUNDCLOUD, activeYtm = src != Source.SOUNDCLOUD,
                cover = CoverSpec(e.thumbnail.takeIf { isUrl(it) }, hue(e.videoId)),
                link = when (state) { LinkState.MATCHED -> LinkBadge.None; LinkState.OTHER -> LinkBadge.Other; else -> LinkBadge.Unknown },
            )
        }
        // The mock's history row: the artist alone under the title, the note line under it; a skipped play is faded.
        return base.copy(
            key = e.videoId, title = e.title.ifEmpty { "Bilinmeyen şarkı" }, subtitle = e.artist.ifEmpty { listed?.artist.orEmpty() },
            activeYtm = src != Source.SOUNDCLOUD, now = playing, note = note, dim = !counted && !playing,
            preview = src == Source.SOUNDCLOUD && e.durationMs in 29_000..31_000,
        )
    }
}
