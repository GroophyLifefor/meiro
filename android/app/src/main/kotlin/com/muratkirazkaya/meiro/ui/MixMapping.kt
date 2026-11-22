package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.sync.LinksStore
import com.muratkirazkaya.meiro.ui.components.ListRowData
import com.muratkirazkaya.meiro.ui.screens.MixPageUi
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** What the Daily mix page and the Daily mix's library row show, from the stored mix list. */
object MixMapping {
    private val Turkish = Locale.forLanguageTag("tr")

    /**
     * The reason under a recommended track. The desktop (and the phone, which ranks the same way) store the English
     * reason of `recommend.why` in the item's `note`, so the desktop keeps showing its own text; the phone shows it in
     * the mock's Turkish: "New to you, like X" is "Yeni sanatçı · X ile benzer", "Because you listen to X" is
     * "X ile benzer". Any other text is shown as it is.
     */
    fun whyText(note: String): String {
        val n = note.trim()
        NEW_PREFIX.let { if (n.startsWith(it)) return "Yeni sanatçı · ${n.removePrefix(it).trim()} ile benzer" }
        BECAUSE_PREFIX.let { if (n.startsWith(it)) return "${n.removePrefix(it).trim()} ile benzer" }
        return n
    }

    const val NEW_PREFIX = "New to you, like "
    const val BECAUSE_PREFIX = "Because you listen to "

    /** "Bugün için · 30 parça · her sabah yenilenir"; "Dün için", or "3 Kasım için" for an older mix. */
    fun kind(lib: Library, today: LocalDate, zone: ZoneId): String {
        val day = HomeMapping.dayWord(HomeMapping.madeDay(lib, zone), today).replaceFirstChar { it.titlecase(Turkish) }
        return "$day için · ${lib.items.size} parça · her sabah yenilenir"
    }

    fun page(lib: Library, links: LinksStore?, playingKey: String?, today: LocalDate, zone: ZoneId) = MixPageUi(
        kind = kind(lib, today, zone),
        rows = lib.items.map { it.toSongRow(links, playingKey).copy(why = whyText(it.why)) },
    )

    /** The library's row for the mix (the mock's "Daily mix", "Meiro · bugün · 30 parça"). */
    fun libraryRow(lib: Library, today: LocalDate, zone: ZoneId): ListRowData =
        lib.toRow().copy(meta = "Meiro · ${HomeMapping.dayWord(HomeMapping.madeDay(lib, zone), today)} · ${lib.items.size} parça")
}
