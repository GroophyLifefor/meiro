package com.muratkirazkaya.meiro.data

/** Port of internal/match/match.go: decides whether two tracks are the same recording. */
object Match {
    enum class Status { MATCHED, UNKNOWN, OTHER }

    data class Track(val title: String = "", val artist: String = "", val durationMs: Long = 0, val isrc: String = "")

    const val DURATION_TOLERANCE_MS = 4_000L

    fun match(a: Track, b: Track): Status {
        if (a.isrc.isNotEmpty() && a.isrc == b.isrc) return Status.MATCHED
        if (normalize(a.title) == normalize(b.title) && normalize(a.artist) == normalize(b.artist) &&
            withinTolerance(a.durationMs, b.durationMs)
        ) return Status.MATCHED
        return Status.UNKNOWN
    }

    private fun withinTolerance(a: Long, b: Long): Boolean {
        if (a <= 0 || b <= 0) return true
        return kotlin.math.abs(a - b) <= DURATION_TOLERANCE_MS
    }

    private val noise = listOf(
        "official music video", "official video", "official audio", "official lyric video", "lyric video",
        "sped up", "slowed down", "slowed", "remastered", "remaster", "explicit", "lyrics", "audio", "video",
    )

    fun normalize(name: String): String {
        val folded = StringBuilder()
        name.codePoints().forEach { cp ->
            val r = if (cp > 0xFFFF) cp else when (cp.toChar()) {
                'ı', 'İ', 'I' -> 'i'.code
                'ş', 'Ş' -> 's'.code
                'ğ', 'Ğ' -> 'g'.code
                'ü', 'Ü' -> 'u'.code
                'ö', 'Ö' -> 'o'.code
                'ç', 'Ç' -> 'c'.code
                else -> cp
            }
            folded.appendCodePoint(r)
        }
        var s = dropBrackets(folded.toString().lowercase())
        for (word in noise) s = s.replace(word, " ")
        val mapped = StringBuilder()
        s.codePoints().forEach { cp -> mapped.appendCodePoint(if (Character.isLetter(cp) || Character.isDigit(cp)) cp else ' '.code) }
        return mapped.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun dropBrackets(name: String): String {
        val out = StringBuilder()
        var depth = 0
        for (r in name) {
            when (r) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                else -> if (depth == 0) out.append(r)
            }
        }
        return out.toString()
    }
}
