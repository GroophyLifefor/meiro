package com.muratkirazkaya.meiro.playback

import com.muratkirazkaya.meiro.data.History
import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.MemoryPersistence
import com.muratkirazkaya.meiro.sync.Outbox
import com.muratkirazkaya.meiro.sync.SyncEngine
import com.muratkirazkaya.meiro.sync.InMemoryTokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watch (history_test.go TestWatchCountsOnlyMovingPositions, TestCountsTheRule) and the play recording lifecycle (stats.go). */
class PlayRecordingTest {
    @Test fun watchCountsOnlyMovingPositions() {
        val w = Watch()
        w.observe(0, 0)
        w.observe(1000, 1000) // playing
        w.observe(2000, 2000)
        assertEquals(2000L, w.listenedMs)
        w.observe(3000, 60_000) // a seek forward is not listening
        assertEquals(2000L, w.listenedMs)
        w.breakWatch() // a pause, broken and resumed at the same place, adds only what plays
        w.observe(3_600_000, 60_000)
        w.observe(3_601_000, 61_000)
        assertEquals(3000L, w.listenedMs)
    }

    @Test fun watchIgnoresLongGapsAndBackwardsMoves() {
        val w = Watch()
        w.observe(0, 0)
        w.observe(2001, 2001) // a wait over 2 s is a stall or sleep
        assertEquals(0L, w.listenedMs)
        w.observe(3001, 1000) // backwards
        assertEquals(0L, w.listenedMs)
        w.observe(4001, 2600) // 1600 ms of movement in 1000 ms: more than wait + 500
        assertEquals(0L, w.listenedMs)
        w.observe(5001, 3600)
        assertEquals(1000L, w.listenedMs)
    }

    @Test fun countingRule() {
        for ((name, played, dur, want) in listOf(
            Case("thirty seconds", 30000, 0, true), Case("half a short track", 20000, 40000, true),
            Case("short of half and of thirty", 10000, 40000, false), Case("just under thirty with no length", 29999, 0, false),
            Case("exactly half", 90000, 180000, true), Case("no length and little heard", 5000, 0, false),
        )) assertEquals(name, want, History.counts(played, dur))
    }
    private data class Case(val name: String, val played: Long, val dur: Long, val want: Boolean)

    /** A store that notes what the recorder tells it. */
    private class Fake : PlayStore {
        val begun = mutableListOf<HistoryEntry>()
        val finished = mutableListOf<Triple<String, Long, Boolean>>()
        override fun begin(entry: HistoryEntry) { begun.add(entry) }
        override fun finish(eventId: String, playedMs: Long, completed: Boolean) { finished.add(Triple(eventId, playedMs, completed)) }
    }

    private var ids = 0
    private fun recorder(f: Fake) = PlayRecorder(f, "Pixel", { "ev${++ids}" })

    @Test fun aPlayIsBegunWhenAudioStartsAndFiledWhenItEnds() {
        val f = Fake(); val r = recorder(f)
        r.begin(QItem(source = "soundcloud", key = "https://soundcloud.com/a/b", title = "T", artist = "A", thumbnail = "th"), 200_000)
        val e = f.begun.single()
        assertEquals("soundcloud", e.source); assertEquals("https://soundcloud.com/a/b", e.videoId) // the page URL
        assertEquals("Pixel", e.device); assertEquals(200_000L, e.durationMs); assertEquals("ev1", e.eventId)
        assertEquals(0L, e.playedMs); assertFalse(e.completed); assertEquals("th", e.thumbnail)
        assertTrue(f.finished.isEmpty()) // the event goes to the server only when the play ends
        r.observe(0, 0); r.observe(1000, 1000)
        r.finish(true)
        assertEquals(listOf(Triple("ev1", 1000L, true)), f.finished)
        r.finish(true) // one play is filed once
        assertEquals(1, f.finished.size)
    }

    @Test fun aNewTrackFilesTheOneBeforeAsNotFinished() {
        val f = Fake(); val r = recorder(f)
        r.begin(QItem(key = "a"), 100_000)
        r.observe(0, 0); r.observe(1000, 1000)
        r.begin(QItem(key = "b"), 100_000) // a skip
        assertEquals(listOf(Triple("ev1", 1000L, false)), f.finished)
        assertEquals(2, f.begun.size); assertTrue(r.active)
        r.finish(false) // quitting
        assertEquals(Triple("ev2", 0L, false), f.finished[1])
    }

    @Test fun nothingIsRecordedForAnEmptyKey() {
        val f = Fake(); val r = recorder(f)
        r.begin(QItem(key = ""), 0)
        assertTrue(f.begun.isEmpty()); assertFalse(r.active)
        r.finish(true); assertTrue(f.finished.isEmpty())
    }

    @Test fun aPauseIsNotListening() {
        val f = Fake(); val r = recorder(f)
        r.begin(QItem(key = "a"), 100_000)
        r.observe(0, 0); r.observe(1000, 1000)
        r.breakWatch() // paused
        r.observe(600_000, 1000); r.observe(601_000, 2000)
        assertEquals(2000L, r.listenedMs())
    }

    @Test fun theSyncEngineHoldsTheEntryInTheHistoryAndQueuesTheEventOnFinish() {
        val persistence = MemoryPersistence()
        val outbox = Outbox(persistence)
        val engine = SyncEngine(LibraryStore(persistence), outbox, InMemoryTokenStore(), persistence, deviceName = "Pixel")
        val r = PlayRecorder(object : PlayStore {
            override fun begin(entry: HistoryEntry) = engine.beginPlay(entry)
            override fun finish(eventId: String, playedMs: Long, completed: Boolean) { engine.finishPlay(eventId, playedMs, completed) }
        }, "Pixel", { "11111111-2222-4333-8444-555555555555" })

        r.begin(QItem(key = "dQw4w9WgXcQ", title = "Never Gonna Give You Up", artist = "Rick Astley"), 213_000)
        assertEquals("in the history from the moment audio starts", 1, engine.history().size)
        assertEquals("but not queued for the server yet", 0, outbox.eventCount())
        r.observe(0, 0); for (s in 1..40) r.observe(s * 1000L, s * 1000L)
        r.finish(true)
        val h = engine.history().single()
        assertEquals(40_000L, h.playedMs); assertTrue(h.completed); assertTrue(History.counted(h))
        assertEquals(1, outbox.eventCount())
        assertEquals(h, persistence.loadHistory().single())
    }

    @Test fun aSkipAfterFiveSecondsIsRecordedButDoesNotCount() {
        val persistence = MemoryPersistence()
        val engine = SyncEngine(LibraryStore(persistence), Outbox(persistence), InMemoryTokenStore(), persistence, deviceName = "Pixel")
        var n = 0
        val r = PlayRecorder(object : PlayStore {
            override fun begin(entry: HistoryEntry) = engine.beginPlay(entry)
            override fun finish(eventId: String, playedMs: Long, completed: Boolean) { engine.finishPlay(eventId, playedMs, completed) }
        }, "Pixel", { "ev${++n}" })
        r.begin(QItem(key = "a", title = "A"), 200_000)
        r.observe(0, 0); for (s in 1..5) r.observe(s * 1000L, s * 1000L)
        r.finish(false)
        val h = engine.history().single()
        assertEquals(5000L, h.playedMs); assertFalse(h.completed); assertFalse(History.counted(h))
        assertNull(engine.finishPlay("unknown", 1, true)) // a play the history no longer holds
    }
}
