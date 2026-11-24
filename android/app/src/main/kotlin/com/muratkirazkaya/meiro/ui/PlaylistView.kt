package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.Item
import java.text.Collator
import java.util.Locale

/** M6h: what the playlist page orders its tracks by (`playlistSortField`): the list's own order, title, artist, length. */
enum class PlSortField(val label: String) { Original("Ekleme sırası"), Title("Ad"), Artist("Sanatçı"), Duration("Süre") }

/**
 * One playlist page's order (`playlistSort`): a field and its direction. The page opens with the list's own order
 * (the zero value). A tap on the field already chosen reverses it (the mock's `plsort`; the desktop has a separate
 * direction button); a tap on another keeps the direction, as the desktop's `choose` does.
 */
data class PlSort(val field: PlSortField = PlSortField.Original, val desc: Boolean = false) {
    /** The page's sort button: "Ekleme sırası", "Ad · azalan", … */
    val label: String get() = this.field.label + if (desc) " · azalan" else ""

    fun choose(f: PlSortField): PlSort = if (f == field) copy(desc = !desc) else copy(field = f)
}

/** M6h: the playlist page's search and order over a list's tracks (`sortPlaylistSections`, the desktop's `detailFilter`). */
object PlaylistView {
    private val TR: Locale = Locale.forLanguageTag("tr")

    /**
     * The places in [items] the page shows, in the order it shows them: the tracks whose title or artist holds [query]
     * (Turkish casing, as the history's search), ordered by [sort]. A field sorts by its value, then by title,
     * whatever their case and accents (a Turkish collator; the desktop compares lower-cased bytes); equal tracks keep
     * the list's order. The list's own order reversed reads the list backwards.
     */
    fun order(items: List<Item>, query: String, sort: PlSort): List<Int> {
        val q = query.trim().lowercase(TR)
        val kept = items.indices.filter { i -> q.isEmpty() || "${items[i].title} ${items[i].artist}".lowercase(TR).contains(q) }
        if (sort.field == PlSortField.Original) return if (sort.desc) kept.reversed() else kept
        val collator = Collator.getInstance(TR).apply { strength = Collator.PRIMARY }
        val byTitle = Comparator<Int> { a, b -> collator.compare(items[a].title, items[b].title) }
        val byField: Comparator<Int> = when (sort.field) {
            PlSortField.Title -> byTitle
            PlSortField.Artist -> Comparator<Int> { a, b -> collator.compare(artistOf(items[a]), artistOf(items[b])) }.then(byTitle)
            else -> compareBy<Int> { items[it].durationMs }.then(byTitle)
        }
        return kept.sortedWith(if (sort.desc) byField.reversed() else byField)
    }

    /** `playlistArtist`: the start of the byline, up to the first " • ". */
    private fun artistOf(i: Item): String = i.artist.substringBefore(" • ").trim()
}
