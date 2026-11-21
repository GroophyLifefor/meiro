package com.muratkirazkaya.meiro.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.muratkirazkaya.meiro.ui.components.toMini
import kotlin.random.Random

/** Ported from queue_test.go (same scenarios and expectations); the app-level test at its end is not ported (no UI). */
class QueueTest {
    private fun tracks(vararg ids: String) = ids.map { QItem(key = it) }
    private fun ids(items: List<QItem>) = items.map { it.key }

    @Test fun queueKeepsItsPlaceThroughChanges() {
        val q = Queue()
        val chosen = tracks("a", "b", "c")
        q.replace(chosen[1], chosen, 1, "Album")
        assertEquals("b", q.current()!!.key); assertEquals(-1, q.recStart)

        q.appendGenerated(tracks("c", "r1", "r2")) // c is already queued
        assertEquals(listOf("a", "b", "c", "r1", "r2"), ids(q.items)); assertEquals(3, q.recStart)

        // A chosen track goes ahead of the generated ones; play-next goes next.
        q.insert(QItem(key = "x"), false, true)
        q.insert(QItem(key = "y"), true, true)
        assertEquals(listOf("a", "b", "y", "c", "x", "r1", "r2"), ids(q.items)); assertEquals(5, q.recStart)
        assertEquals("y", q.at(q.next(true, RepeatMode.Off)!!).key)

        // With auto-play off the queue ends before the generated tracks.
        q.moveTo(4) // x
        assertNull("auto-play off played into the generated tracks", q.next(false, RepeatMode.Off))
        assertEquals(0, q.next(false, RepeatMode.Queue))

        // Dragging the current track keeps it current, and the divider row is not a track.
        val before = q.rows()
        assertTrue(q.moveRows(listOf(q.rowOf(4)), 0))
        assertEquals("x", q.current()!!.key); assertEquals(0, q.index); assertEquals(before, q.rows())
        assertTrue(q.isDivider(q.recStart))
        assertFalse(q.moveRows(listOf(q.recStart), 0))
        assertEquals("r1", q.at(q.recStart).key)
    }

    @Test fun queueBoundsGeneratedTracks() {
        val q = Queue()
        q.replace(tracks("a")[0], tracks("a"), 0, "")
        val many = List(AUTOPLAY_QUEUE_LIMIT * 2) { "g" + ('A' + it / 26) + ('a' + it % 26) }
        q.appendGenerated(tracks(*many.subList(0, AUTOPLAY_QUEUE_LIMIT - 1).toTypedArray()))
        q.moveTo(q.size - 1)
        q.appendGenerated(tracks(*many.subList(AUTOPLAY_QUEUE_LIMIT, many.size).toTypedArray()))
        assertTrue("${q.generated.size} generated tracks, limit $AUTOPLAY_QUEUE_LIMIT", q.generated.size <= AUTOPLAY_QUEUE_LIMIT)
        assertEquals(many[AUTOPLAY_QUEUE_LIMIT - 2], q.current()!!.key)
    }

    @Test fun shuffleArrangesTheWholeQueueAndPutsItBack() {
        val q = Queue(Random(7))
        val chosen = tracks("a", "b", "c", "d", "e")
        q.replace(chosen[3], chosen, 3, "Album") // playing d, with songs before it
        q.shuffleQueue()
        assertEquals("d", q.current()!!.key); assertEquals(0, q.index)
        assertEquals(5, q.items.size)
        assertEquals("the shuffle lost or repeated a track", 5, ids(q.items).toSet().size)

        q.restoreQueue()
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(q.items))
        assertEquals("d", q.current()!!.key); assertEquals(3, q.index)
    }

    @Test fun shuffleKeepsTheGeneratedTracksAtTheEnd() {
        val q = Queue(Random(3))
        val chosen = tracks("a", "b", "c")
        q.replace(chosen[0], chosen, 0, "")
        q.appendGenerated(tracks("r1", "r2"))
        q.shuffleQueue()
        assertEquals(3, q.recStart); assertEquals(listOf("r1", "r2"), ids(q.items.subList(3, 5)))
        assertEquals("a", q.current()!!.key); assertEquals(0, q.index)

        q.restoreQueue()
        assertEquals(listOf("a", "b", "c", "r1", "r2"), ids(q.items))
        assertEquals(0, q.index); assertEquals(3, q.recStart)
    }

    @Test fun replaceWithATrackOutsideTheTracksPlaysItAlone() {
        val q = Queue()
        q.replace(QItem(key = "solo"), tracks("a", "b"), 0, "")
        assertEquals(listOf("solo"), ids(q.items)); assertEquals(0, q.index)
    }

    @Test fun queueRemoveKeepsItsPlace() {
        val q = Queue()
        val chosen = tracks("a", "b", "c", "d")
        q.replace(chosen[2], chosen, 2, "")
        q.appendGenerated(tracks("r1", "r2"))
        q.insert(QItem(key = "n"), true, true) // a b c n d r1 r2

        assertFalse("removed the track playing", q.remove(q.index))
        assertTrue(q.remove(0)); assertEquals("c", q.current()!!.key); assertEquals(4, q.recStart)
        q.remove(q.find("n"))
        assertEquals("play-next mark outlived its track", "", q.playNext)
        q.remove(q.find("r1"))
        assertFalse(q.generated.contains("r1")); assertEquals(3, q.recStart); assertEquals("r2", q.at(q.recStart).key)
        q.remove(q.find("r2"))
        assertEquals(-1, q.recStart); assertEquals(0, q.generated.size)
        assertEquals(listOf("b", "c", "d"), ids(q.items)); assertEquals("c", q.current()!!.key)
    }

    @Test fun queueShiftAndClear() {
        val q = Queue()
        val chosen = tracks("a", "b", "c")
        q.replace(chosen[0], chosen, 0, "")
        q.appendGenerated(tracks("r1", "r2"))

        assertTrue(q.shift(1, 1)); assertEquals(listOf("a", "c", "b", "r1", "r2"), ids(q.items))
        assertFalse("moved past an end", q.shift(0, -1) || q.shift(4, 1))
        // Down past the divider: the boundary stays on the generated tracks.
        q.shift(2, 1)
        assertEquals("r1", q.at(q.recStart).key); assertEquals("a", q.current()!!.key)
        assertTrue(q.shift(q.recStart, -1)); assertEquals("r1", q.at(q.recStart).key)

        assertTrue(q.clearUpcoming())
        assertEquals(listOf("a"), ids(q.items)); assertEquals(-1, q.recStart); assertEquals(0, q.generated.size)
        assertFalse("cleared an empty queue", q.clearUpcoming())
    }

    @Test fun queueDragKeepsPlayNextAndBoundary() {
        val q = Queue()
        val chosen = tracks("a", "b", "c", "d")
        q.replace(chosen[0], chosen, 0, "")
        q.appendGenerated(tracks("r1", "r2"))
        q.insert(QItem(key = "n"), true, true) // a n b c d r1 r2

        // Drag d, the last chosen track, to the top of the upcoming tracks.
        assertTrue(q.moveRows(listOf(q.rowOf(4)), q.rowOf(1)))
        assertEquals(listOf("a", "d", "n", "b", "c", "r1", "r2"), ids(q.items))
        assertEquals("n", q.playNext)
        assertEquals("play-next must still play first", "n", q.at(q.next(true, RepeatMode.Off)!!).key)
        assertEquals("r1", q.at(q.recStart).key); assertEquals(0, q.index)

        // A generated track dragged among the chosen ones stays generated.
        assertTrue(q.moveRows(listOf(q.rowOf(6)), 1)); assertTrue(q.hasDivider())
        assertTrue(q.generated.contains("r2")); assertEquals("r2", q.at(q.recStart).key)
    }

    // YouTube Music recommends a song's music video and its audio upload as two tracks; only one is queued.
    @Test fun appendGeneratedSkipsOtherUploadsOfSameSong() {
        val q = Queue()
        q.replace(QItem(key = "a", title = "A", duration = "1:00"), emptyList(), 0, "")
        q.appendGenerated(listOf(
            QItem(key = "r1", title = "Nostalgia", duration = "3:17"),
            QItem(key = "r2", title = "Nostalgia", duration = "3:16"), // same song
            QItem(key = "r3", title = "Nostalgia", duration = "5:30"), // a different length: another song
            QItem(key = "r4", title = "A", duration = "1:02"), // the playing song
        ))
        assertEquals(listOf("a", "r1", "r3"), ids(q.items))
    }

    @Test fun appendGeneratedSkipsSameTitleAndArtist() {
        val q = Queue()
        q.replace(QItem(key = "a", title = "A", duration = "1:00"), emptyList(), 0, "")
        q.appendGenerated(listOf(
            QItem(key = "r1", title = "Nostalgia", artist = "Kenshi", duration = "3:17"),
            QItem(key = "r2", title = "NOSTALGIA!", artist = "kenshi", duration = ""),
            QItem(key = "r3", title = "Nostalgia", artist = "Another", duration = "3:17"), // another artist
        ))
        assertEquals(listOf("a", "r1", "r3"), ids(q.items))
    }

    @Test fun appendGeneratedKeepsAudioOrderWhenSkippingVideos() {
        val q = Queue()
        q.replace(QItem(key = "a", title = "A", duration = "1:00"), emptyList(), 0, "")
        q.appendGenerated(listOf(
            QItem(key = "v1", title = "Nostalgia", artist = "Kenshi", duration = "3:17", kind = Kind.Video),
            QItem(key = "r2", title = "Other", duration = "2:00", kind = Kind.Track),
            QItem(key = "t1", title = "Nostalgia", artist = "Kenshi", duration = "3:16", kind = Kind.Track),
            QItem(key = "v2", title = "Other", duration = "2:00", kind = Kind.Video),
        ))
        assertEquals(listOf("a", "r2", "t1"), ids(q.items))
    }

    @Test fun appendGeneratedDoesNotReserveASlotForVideoOnEarlierPage() {
        val q = Queue()
        val current = QItem(key = "a", title = "A", duration = "1:00")
        val video = QItem(key = "v1", title = "Nostalgia", artist = "Kenshi", duration = "3:17", kind = Kind.Video)
        q.replace(current, emptyList(), 0, "")
        q.appendGenerated(listOf(video))
        assertEquals(listOf("a"), ids(q.items))
        q.appendGenerated(listOf(QItem(key = "t1", title = "Nostalgia", artist = "Kenshi", duration = "3:16", kind = Kind.Track)))
        assertEquals(listOf("a", "t1"), ids(q.items))
        assertTrue(q.generated.contains("t1"))
    }

    @Test fun appendGeneratedKeepsChosenVideoWhenAudioVersionIsRecommended() {
        val video = QItem(key = "v1", title = "Nostalgia", artist = "Kenshi", duration = "3:17", kind = Kind.Video)
        val q = Queue()
        q.replace(video, listOf(video), 0, "Playlist")
        q.appendGenerated(listOf(QItem(key = "t1", title = "Nostalgia", artist = "Kenshi", duration = "3:16", kind = Kind.Track)))
        assertEquals(listOf("v1"), ids(q.items)); assertEquals(-1, q.recStart)
    }

    // The semantics playback relies on, beyond the Go test file.
    @Test fun nextAndRepeat() {
        val q = Queue()
        val chosen = tracks("a", "b")
        q.replace(chosen[0], chosen, 0, "")
        assertEquals(1, q.next(false, RepeatMode.Off))
        q.moveTo(1)
        assertNull(q.next(false, RepeatMode.Off))
        assertEquals(0, q.next(false, RepeatMode.Queue))
        assertNull("repeating one track is the player's work, not the queue's", q.next(false, RepeatMode.Track))
        assertEquals(RepeatMode.Queue, RepeatMode.Off.next()); assertEquals(RepeatMode.Track, RepeatMode.Queue.next()); assertEquals(RepeatMode.Off, RepeatMode.Track.next())
        assertNull(Queue().next(true, RepeatMode.Queue))
    }

    @Test fun durationText() {
        assertEquals(222_000L, parseDurationMs("3:42")); assertEquals(3_723_000L, parseDurationMs("1:02:03"))
        assertEquals(0L, parseDurationMs("")); assertEquals(0L, parseDurationMs("42")); assertEquals(0L, parseDurationMs("a:b"))
        assertEquals("3:42", clockText(222_000)); assertEquals("1:02:03", clockText(3_723_000))
        assertEquals(213_886L, durationMsFromUrl("https://x.googlevideo.com/videoplayback?expire=1&dur=213.886&x=y"))
        assertEquals(0L, durationMsFromUrl("https://x/y?expire=1"))
        assertEquals(QItem(source = "soundcloud", key = "https://soundcloud.com/a/b", title = "T", artist = "A", duration = "1:00"), QItem.parse("sc:https://soundcloud.com/a/b|T|A|1:00"))
        assertEquals("ytm", QItem.parse("ytm:abc")!!.source); assertNull(QItem.parse("nope"))
    }
}

/** The mini player's state from the playback state: the mapping the shell uses (the mock's sample is the render test's input). */
class MiniStateTest {
    @Test fun mapsWhatPlaysToWhatTheMiniPlayerShows() {
        val ui = PlayerUi(QItem(source = "soundcloud", key = "https://soundcloud.com/a/b", title = "T", artist = "A"), playing = false, positionMs = 50_000, durationMs = 200_000, loading = false)
        val mini = ui.toMini()
        assertEquals("T", mini.title); assertEquals("A", mini.artist); assertFalse(mini.activeIsYtm); assertFalse(mini.playing)
        assertEquals(0.25f, mini.progress, 0.0001f)
        assertEquals("a track without a title shows its key", "abc", PlayerUi(QItem(key = "abc"), true, 0, 0, true).toMini().title)
        assertEquals("no length, no progress", 0f, PlayerUi(QItem(key = "abc"), true, 5000, 0, false).toMini().progress, 0f)
    }
}
