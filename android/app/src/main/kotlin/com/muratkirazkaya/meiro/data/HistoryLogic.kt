package com.muratkirazkaya.meiro.data

import java.time.Instant

/** Port of internal/history/history.go (the parts the sync uses). */
object History {
    /** About a year of heavy listening; old entries go first when it overflows. */
    const val MAX_ENTRIES = 5000

    /** At least thirty seconds heard, or half the track, whichever comes first. */
    fun counts(playedMs: Long, durationMs: Long): Boolean {
        if (playedMs >= 30000) return true
        return durationMs > 0 && playedMs * 2 >= durationMs
    }

    /** Plays recorded before they carried timing (no event id) count as they always did. */
    fun counted(e: HistoryEntry): Boolean = if (e.eventId.isEmpty()) true else counts(e.playedMs, e.durationMs)

    fun append(history: List<HistoryEntry>, vararg entries: HistoryEntry): List<HistoryEntry> {
        val all = history + entries
        return if (all.size > MAX_ENTRIES) all.drop(all.size - MAX_ENTRIES) else all
    }

    /**
     * Adds plays that came from elsewhere. A play whose event id the history already has is left out. The result is
     * in the order the plays happened, oldest first (stable), keeps the latest [MAX_ENTRIES], and says how many
     * plays were added.
     */
    fun merge(entries: List<HistoryEntry>, incoming: List<HistoryEntry>): Pair<List<HistoryEntry>, Int> {
        val known = HashSet<String>()
        for (e in entries) if (e.eventId.isNotEmpty()) known.add(e.eventId)
        val out = entries.toMutableList()
        var added = 0
        for (e in incoming) {
            if (e.eventId.isNotEmpty()) {
                if (!known.add(e.eventId)) continue
            }
            out.add(e)
            added++
        }
        if (added == 0) return out to 0
        var sorted = out.sortedWith { a, b -> a.playedAt.compareTo(b.playedAt) } // stable
        if (sorted.size > MAX_ENTRIES) sorted = sorted.drop(sorted.size - MAX_ENTRIES)
        return sorted to added
    }

    /** Removes the plays at or before [at], as the history was cleared then; returns the rest and how many went. */
    fun dropThrough(entries: List<HistoryEntry>, at: Instant): Pair<List<HistoryEntry>, Int> {
        val kept = entries.filter { it.playedAt.isAfter(at) }
        return kept to (entries.size - kept.size)
    }
}
