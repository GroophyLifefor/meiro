package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.musicbrainz.LinkCandidate
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.screens.LinkRowUi
import com.muratkirazkaya.meiro.ui.screens.LinkSheetUi

/** The link sheet's words and rows. */
object LinkMapping {
    private const val ORDER = "Popülerlik ve benzerliğe göre sıralı, doğru olanı seç."
    const val NOTHING_FOUND = "MusicBrainz'de bir şey bulunamadı. Başka bir arama dene ya da \"diğer\" olarak işaretle."
    const val UNAVAILABLE = "MusicBrainz şu an cevap vermiyor. Biraz sonra tekrar ara."

    private fun hue(s: String) = Math.floorMod(s.hashCode(), 360)

    /** The field's first text: the mock's "title artist" for a song, the name for an artist. */
    fun initialQuery(t: LinkTarget): String = t.song?.let { "${it.title} ${it.artist}".trim() } ?: t.artist.orEmpty()

    /**
     * The title a song's search asks for (`searchLink`, whose field holds the title and whose artist comes from the
     * song): the song's own title while the field still holds the first text, else what was typed.
     */
    fun searchTitle(song: QItem, query: String, initial: String): String = if (query.trim() == initial.trim()) song.title else query.trim()

    fun ui(t: LinkTarget, query: String, found: List<LinkCandidate>?, failed: Boolean, selected: Int): LinkSheetUi {
        val song = t.song
        val note = if (song != null) "\"${song.title}\"${if (song.artist.isNotBlank()) " · ${song.artist}" else ""}. $ORDER" else "\"${t.artist.orEmpty()}\". $ORDER"
        val title = if (song != null) "MusicBrainz kaydı" else "MusicBrainz sanatçısı"
        val base = LinkSheetUi(title, note, query)
        return when {
            found == null -> base.copy(loading = true)
            failed -> base.copy(message = UNAVAILABLE)
            found.isEmpty() -> base.copy(message = NOTHING_FOUND)
            else -> base.copy(rows = found.map(::row), selected = selected.coerceIn(0, found.size - 1))
        }
    }

    /** `linkResult`: the name, the line under it and "%97" (nothing when MusicBrainz gave no score). */
    fun row(c: LinkCandidate) = LinkRowUi(c.name, c.detail, if (c.similarity > 0) "%${c.similarity}" else "", CoverSpec(null, hue(c.id)))
}
