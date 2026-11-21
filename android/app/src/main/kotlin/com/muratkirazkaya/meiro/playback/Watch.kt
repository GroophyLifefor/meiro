package com.muratkirazkaya.meiro.playback

import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Source
import java.time.Instant

/**
 * Port of `internal/history` Watch: measures how long one play was listened to from the positions the player
 * reports, so a pause, a stall or a seek forward is not counted. The zero value has listened to nothing. One Watch
 * is one play. Times are milliseconds on any monotonic clock ([atMs]) and in the track ([positionMs]).
 */
class Watch {
    private var have = false
    private var at = 0L
    private var pos = 0L
    var listenedMs = 0L; private set

    /** The sound stopped flowing between samples (paused, buffering or moving): the next sample only re-anchors. */
    fun breakWatch() { have = false }

    /** The movement since the last sample counts only when the two samples are close in time and in place. */
    fun observe(atMs: Long, positionMs: Long) {
        if (have) {
            val wait = atMs - at
            val moved = positionMs - pos
            if (wait > 0 && wait <= MAX_GAP_MS && moved >= 0 && moved <= wait + SLACK_MS) listenedMs += moved
        }
        have = true; at = atMs; pos = positionMs
    }

    companion object {
        const val MAX_GAP_MS = 2000L
        const val SLACK_MS = 500L
        /** The desktop samples every 250 ms (`pollInterval`). */
        const val POLL_MS = 250L
    }
}

/** Where plays go: the history and the sync outbox (the SyncEngine, behind an ordered background writer). */
interface PlayStore {
    /** Adds the play to the history, as the desktop does when audio starts. */
    fun begin(entry: HistoryEntry)
    /** Records how much was heard and whether it finished, and files the play for the server (`queueEvent`). */
    fun finish(eventId: String, playedMs: Long, completed: Boolean)
}

/**
 * Port of `stats.go` (`beginPlayRecord`, `observeRecord`, `breakRecord`, `finishPlayRecord`): follows the play being
 * written to the history. One recorder follows one play at a time. A play gets its event id when audio starts, so the
 * same play is one event however often it is sent.
 */
class PlayRecorder(
    private val store: PlayStore,
    private val device: String,
    private val newId: () -> String,
    private val nowInstant: () -> Instant = { Instant.now() },
) {
    private var eventId = ""
    private var watch = Watch()
    val active get() = eventId.isNotEmpty()

    /**
     * Starts a play: the one before it is filed as not finished, the new one is added to the history, and the clock
     * on it starts. [durationMs] is the track's length ([QItem.durationMs], or what the resolve found when the row
     * had none).
     */
    fun begin(item: QItem, durationMs: Long) {
        finish(false)
        if (item.queueKey().isEmpty()) return
        val id = newId()
        store.begin(
            HistoryEntry(
                videoId = item.key, title = item.title, artist = item.artist, playedAt = nowInstant(),
                source = item.provider().ifEmpty { Source.YTM }, durationMs = durationMs, device = device,
                thumbnail = item.thumbnail, eventId = id,
            ),
        )
        eventId = id; watch = Watch()
    }

    fun observe(atMs: Long, positionMs: Long) { if (active) watch.observe(atMs, positionMs) }

    fun breakWatch() { if (active) watch.breakWatch() }

    fun listenedMs(): Long = watch.listenedMs

    /** Files the play in progress: how much was heard, whether it reached the end, and the event the sync sends. */
    fun finish(completed: Boolean) {
        if (!active) return
        val id = eventId
        eventId = ""
        store.finish(id, watch.listenedMs, completed)
    }
}
