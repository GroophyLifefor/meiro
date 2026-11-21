package com.muratkirazkaya.meiro.playback

import com.muratkirazkaya.meiro.data.Source

enum class Kind { Track, Video }

/**
 * A track in the queue (the parts of the desktop's `youtube.MusicItem` playback uses). [key] is the video ID for
 * YouTube Music and the page URL for SoundCloud (`streamKey`); [duration] is the row's own text ("3:17").
 */
data class QItem(
    val source: String = Source.YTM,
    val key: String = "",
    val title: String = "",
    val artist: String = "",
    val duration: String = "",
    val thumbnail: String = "",
    val kind: Kind = Kind.Track,
) {
    /** The queue's identity of a track (`Key()`): empty names no track. */
    fun queueKey(): String = key

    /** The provider the track plays from (`resolveSource`): YouTube Music unless it says otherwise. */
    fun provider(): String = source.ifEmpty { Source.YTM }

    fun durationMs(): Long = parseDurationMs(duration)

    companion object {
        /** `ytm:<videoId>` or `sc:<page url>` (the debug entry form), optionally `|title|artist|m:ss`. */
        fun parse(spec: String): QItem? {
            val parts = spec.split('|')
            val head = parts[0].trim()
            val (source, key) = when {
                head.startsWith("ytm:") -> Source.YTM to head.removePrefix("ytm:")
                head.startsWith("sc:") -> Source.SOUNDCLOUD to head.removePrefix("sc:")
                else -> return null
            }
            if (key.isEmpty()) return null
            return QItem(source, key, parts.getOrNull(1).orEmpty(), parts.getOrNull(2).orEmpty(), parts.getOrNull(3).orEmpty())
        }
    }
}

/** `playback.go` parseDuration: "3:42" or "1:02:03", 0 for anything else. */
fun parseDurationMs(text: String): Long {
    val parts = text.trim().split(":")
    if (parts.size < 2 || parts.size > 3) return 0
    var total = 0L
    for (p in parts) {
        val v = p.toIntOrNull() ?: return 0
        if (v < 0) return 0
        total = total * 60 + v
    }
    return total * 1000
}

/** `playback.go` clock: a position as "3:42" or "1:02:03". */
fun clockText(ms: Long): String {
    val total = (maxOf(ms, 0) / 1000).toInt()
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
