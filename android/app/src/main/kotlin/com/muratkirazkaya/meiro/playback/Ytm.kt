package com.muratkirazkaya.meiro.playback

/** Port of `internal/ytdlp` `durationOf`: the `dur` query parameter of a media URL, in seconds (float), 0 if absent. */
fun durationMsFromUrl(url: String): Long {
    val q = url.substringAfter('?', "")
    if (q.isEmpty()) return 0
    for (pair in q.split('&')) {
        val k = pair.substringBefore('=')
        if (k == "dur") return ((pair.substringAfter('=', "").toDoubleOrNull() ?: return 0) * 1000).toLong().coerceAtLeast(0)
    }
    return 0
}
