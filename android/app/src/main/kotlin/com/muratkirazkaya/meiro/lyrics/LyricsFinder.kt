package com.muratkirazkaya.meiro.lyrics

import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.youtube.YtmLyrics
import kotlinx.coroutines.CancellationException

/**
 * What a track's lyrics come to (`loadedLyrics`): the plain text, the timed lines when there are any, and where they
 * came from ("Source: LRCLIB", "Source: YouTube Music" or YouTube Music's own footer). [instrumental] is LRCLIB saying
 * the track has no words.
 */
data class FoundLyrics(val text: String = "", val synced: List<LyricLine> = emptyList(), val footer: String = "", val instrumental: Boolean = false) {
    fun empty() = text.isEmpty() && synced.isEmpty()
}

/**
 * `findLyrics` (lyrics.go:74): YouTube Music first (its timed lines, by the track's video id; a SoundCloud track that
 * has no YouTube Music copy skips it), then LRCLIB when there are no timed lines, and YouTube Music's plain text as the
 * last resort. A source that fails is not an error: the next is tried. No lyrics at all is an empty result, unless the
 * LRCLIB lookup failed, which is thrown (so the tab can say the lookup failed, not that there are none).
 */
class LyricsFinder(
    private val ytm: suspend (videoId: String) -> YtmLyrics,
    private val lrclib: suspend (LrcQuery) -> LrcLyrics?,
    private val log: (String) -> Unit = {},
) {
    suspend fun find(item: QItem, totalMs: Long): FoundLyrics {
        var synced: List<LyricLine> = emptyList()
        var plainText = ""
        var plainFooter = ""
        var lookupErr: Exception? = null
        val videoId = item.refFor(Source.YTM).orEmpty()
        if (videoId.isNotEmpty()) {
            try {
                val l = ytm(videoId)
                plainText = l.description.trim(); plainFooter = l.footer
                synced = l.timed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("lyrics: youtube video_id=$videoId failed: $e")
            }
        }
        var footer = plainFooter.ifEmpty { "Source: YouTube Music" }
        var instrumental = false
        if (synced.isEmpty()) {
            // A track that is not resolved yet has no length of its own; the one its row lists is as good.
            val total = if (totalMs > 0) totalMs else item.durationMs()
            val got = try {
                lrclib(LrcQuery(item.title, item.artist, item.album, total))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("lyrics: lrclib lookup failed: $e")
                lookupErr = e
                null
            }
            if (got != null && got.synced.isNotEmpty()) { synced = got.synced; footer = "Source: LRCLIB" }
            // Its plain text may hold a romanised copy as well; the timed lines are cleaned of that, so they stand in for it.
            if (plainText.isEmpty() && got != null && got.plain.isNotEmpty() && synced.isEmpty()) { plainText = got.plain; footer = "Source: LRCLIB" }
            instrumental = got?.instrumental == true
        }
        var text = plainText
        if (text.isEmpty() && synced.isNotEmpty()) text = synced.joinToString("\n") { it.text }.trim()
        val found = FoundLyrics(text, synced, footer, instrumental && text.isEmpty() && synced.isEmpty())
        log("lyrics: video_id=$videoId title=\"${item.title}\" artist=\"${item.artist}\" total=${totalMs}ms -> $footer, synced_lines=${synced.size} plain_chars=${text.length}")
        if (found.empty() && lookupErr != null) throw lookupErr
        return found
    }
}
