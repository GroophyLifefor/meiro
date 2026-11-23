package com.muratkirazkaya.meiro.ui

import androidx.compose.ui.graphics.Brush
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.PlayerUi
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.QueueView
import com.muratkirazkaya.meiro.playback.clockText
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.SongRowData
import com.muratkirazkaya.meiro.ui.screens.NowPlayingUi
import com.muratkirazkaya.meiro.ui.screens.NpLink
import com.muratkirazkaya.meiro.ui.screens.QueueRow
import com.muratkirazkaya.meiro.ui.screens.SuggestionKind
import com.muratkirazkaya.meiro.ui.screens.UpNextUi
import java.util.Locale

/** The full player and its up-next list, from what the playback engine publishes. */
object NowPlayingMapping {
    private val TR: Locale = Locale.forLanguageTag("tr")
    private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)

    /** The track playing as the full player shows it; [linkState] is its MusicBrainz state ("" or null: not linked). */
    fun player(p: PlayerUi, linkState: String?, background: Brush? = null): NowPlayingUi {
        val item = p.item
        val providers = item.providers()
        return NowPlayingUi(
            title = item.title.ifEmpty { item.key },
            artist = item.artist,
            context = p.queue.source,
            cover = CoverSpec(item.thumbnail.takeIf { it.isNotBlank() }, hue(item.key)),
            link = when (linkState) { LinkState.MATCHED -> NpLink.Matched; LinkState.OTHER -> NpLink.Other; else -> NpLink.Unknown },
            // Likes are YouTube Music's own (the desktop's heart): no YouTube Music sign-in yet, so never liked.
            liked = false,
            ytm = Source.YTM in providers,
            sc = Source.SOUNDCLOUD in providers,
            active = if (item.provider() == Source.SOUNDCLOUD) Provider.Sc else Provider.Ytm,
            positionMs = p.positionMs,
            durationMs = p.durationMs,
            playing = p.playing,
            preparing = p.loading,
            shuffle = p.shuffle,
            repeat = p.repeat,
            background = background,
        )
    }

    /** The up-next tab: the switch, "<list>'nden çalıyor · N sırada" and the queue with headings over generated runs. */
    fun upNext(p: PlayerUi, linkState: (String, String) -> String?): UpNextUi {
        val q = p.queue
        val rows = ArrayList<QueueRow>()
        var group: SuggestionKind? = null
        for ((i, item) in q.items.withIndex()) {
            val generated = q.recStart in 0..i
            if (generated) {
                val g = if (item.queueKey() in q.suggested) SuggestionKind.Meiro else SuggestionKind.Ytm
                if (g != group) { rows += QueueRow.Label(g); group = g }
            }
            rows += QueueRow.Song(i, songRow(item, i == q.index, generated && item.queueKey() in q.suggested, linkState))
        }
        return UpNextUi(p.autoPlay, sourceLine(q), rows)
    }

    fun songRow(item: QItem, now: Boolean, suggested: Boolean, linkState: (String, String) -> String?): SongRowData {
        val providers = item.providers()
        val state = linkState(item.provider(), item.key)?.takeIf { it.isNotEmpty() } ?: LinkState.UNKNOWN
        return SongRowData(
            key = item.key,
            title = item.title.ifEmpty { item.key },
            subtitle = listOf(item.artist, item.durationMs().takeIf { it > 0 }?.let { clockText(it) }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
            ytm = Source.YTM in providers,
            sc = Source.SOUNDCLOUD in providers,
            activeYtm = item.provider() != Source.SOUNDCLOUD,
            cover = CoverSpec(item.thumbnail.takeIf { it.isNotBlank() }, hue(item.key)),
            link = when (state) { LinkState.MATCHED -> LinkBadge.None; LinkState.OTHER -> LinkBadge.Other; else -> LinkBadge.Unknown },
            now = now,
            why = if (suggested) item.why else "",
        )
    }

    /** "Gece Sürüşü'nden çalıyor · 2 sırada" (the chosen tracks after the one playing); "2 sırada" with no list named. */
    fun sourceLine(q: QueueView): String {
        val n = "${q.chosenAfter} sırada"
        return if (q.source.isEmpty()) n.replaceFirstChar { it.titlecase(TR) } else "${fromSource(q.source)} çalıyor · $n"
    }

    /**
     * [name] with the Turkish ablative after an apostrophe ("Gece Sürüşü'nden", "Daft Punk'tan", "Daily mix'ten"): the
     * vowel follows the last vowel, a final vowel takes the buffer "n", a final voiceless consonant hardens "d" to "t".
     * Read from the spelling, so a foreign name is suffixed as a Turkish reader would say it letter by letter. The
     * app's own common-noun names ("“daft punk” araması", "Dinleme geçmişi") take the suffix without an apostrophe
     * ("araması" + "ndan"), as Turkish spelling wants.
     */
    fun fromSource(name: String): String {
        val common = COMMON_ENDINGS.any { name.endsWith(it) }
        val letters = name.lowercase(TR).filter { it.isLetterOrDigit() }
        if (letters.isEmpty()) return name
        val vowels = "aeıioöuü"
        val last = letters.last()
        val lastVowel = letters.lastOrNull { it in vowels } ?: 'e'
        val e = if (lastVowel in "aıou") 'a' else 'e'
        val d = if (last in "çfhkpsştx") 't' else 'd'
        val n = if (last in vowels) "n" else ""
        return "$name${if (common) "" else "'"}$n$d${e}n"
    }

    /** The last words of the names the app gives a queue that are common nouns (search and history), not proper names. */
    private val COMMON_ENDINGS = listOf(" araması", " geçmişi")
}
