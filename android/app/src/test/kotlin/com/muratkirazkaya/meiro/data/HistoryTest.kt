package com.muratkirazkaya.meiro.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Ports of internal/history/history_test.go (Counts, Append cap, Merge) plus DropThrough. */
class HistoryTest {
    // TestCountsTheRule
    @Test fun countsTheRule() {
        data class C(val name: String, val played: Long, val dur: Long, val want: Boolean)
        for (c in listOf(
            C("thirty seconds", 30000, 0, true), C("half a short track", 20000, 40000, true),
            C("short of half and of thirty", 10000, 40000, false), C("just under thirty with no length", 29999, 0, false),
            C("exactly half", 90000, 180000, true), C("no length and little heard", 5000, 0, false),
        )) assertEquals(c.name, c.want, History.counts(c.played, c.dur))
    }

    @Test fun playsRecordedBeforeTimingExistedStillCount() {
        assertTrue(History.counted(HistoryEntry(videoId = "v")))
        assertFalse(History.counted(HistoryEntry(videoId = "v", eventId = "e", playedMs = 5000)))
    }

    // TestAppendCapsAndWriteReadsBack (the cap part)
    @Test fun appendCaps() {
        var h = emptyList<HistoryEntry>()
        for (i in 0 until History.MAX_ENTRIES + 3) h = History.append(h, HistoryEntry(videoId = ('a' + i % 26).toString()))
        assertEquals(History.MAX_ENTRIES, h.size)
    }

    // TestMergeAddsPlaysOnceAndInOrder
    @Test fun mergeAddsPlaysOnceAndInOrder() {
        val at = Instant.parse("2026-03-10T12:00:00Z")
        val own = listOf(
            HistoryEntry(videoId = "old", playedAt = at.minusSeconds(3600)), // before plays had ids
            HistoryEntry(videoId = "a", eventId = "e1", playedAt = at),
            HistoryEntry(videoId = "c", eventId = "e3", playedAt = at.plusSeconds(7200)),
        )
        val incoming = listOf(
            HistoryEntry(videoId = "a", eventId = "e1", playedAt = at), // this device's own play, back from the server
            HistoryEntry(videoId = "b", eventId = "e2", device = "phone", playedAt = at.plusSeconds(3600)),
            HistoryEntry(videoId = "b", eventId = "e2", device = "phone", playedAt = at.plusSeconds(3600)),
        )
        val (got, added) = History.merge(own, incoming)
        assertTrue("added $added, history $got", added == 1 && got.size == 4)
        assertEquals(listOf("old", "a", "b", "c"), got.map { it.videoId })
        val (again, added2) = History.merge(got, incoming)
        assertTrue("merging twice added $added2", added2 == 0 && again.size == 4)

        val many = (0 until History.MAX_ENTRIES + 5).map { HistoryEntry(videoId = "$it", eventId = "e$it", playedAt = at.plusSeconds(60L * it)) }
        val (kept, _) = History.merge(emptyList(), many)
        assertTrue("kept ${kept.size} starting at ${kept[0].videoId}", kept.size == History.MAX_ENTRIES && kept[0].videoId == "5")
    }

    @Test fun mergeKeepsEqualTimesInArrivalOrder() {
        val at = Instant.parse("2026-03-10T12:00:00Z")
        val (got, _) = History.merge(
            listOf(HistoryEntry(videoId = "first", eventId = "e1", playedAt = at)),
            listOf(HistoryEntry(videoId = "second", eventId = "e2", playedAt = at)),
        )
        assertEquals(listOf("first", "second"), got.map { it.videoId })
    }

    @Test fun dropThroughRemovesAtOrBefore() {
        val at = Instant.parse("2026-03-10T12:00:00Z")
        val h = listOf(
            HistoryEntry(videoId = "a", playedAt = at.minusSeconds(1)),
            HistoryEntry(videoId = "b", playedAt = at), // at the clearing time: gone too
            HistoryEntry(videoId = "c", playedAt = at.plusNanos(1)),
        )
        val (rest, n) = History.dropThrough(h, at)
        assertEquals(2, n); assertEquals(listOf("c"), rest.map { it.videoId })
    }
}
