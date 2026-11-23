package com.muratkirazkaya.meiro.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.muratkirazkaya.meiro.data.Source

/** What the UI shows of playback: the mini player, the full player and its up-next list. */
data class PlayerUi(
    val item: QItem,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val loading: Boolean,
    /** The reason the track did not play ([PlayError.message]); null when it plays. */
    val error: String? = null,
    val queue: QueueView = QueueView(),
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val autoPlay: Boolean = false,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * The queue as the up-next list shows it: every track, the place of the one playing, where the generated tracks start
 * (-1: none), which of those are Meiro's own suggestions, the list it plays from, and why recommendations failed.
 */
data class QueueView(
    val items: List<QItem> = emptyList(),
    val index: Int = 0,
    val recStart: Int = -1,
    val suggested: Set<String> = emptySet(),
    val source: String = "",
    val upNextError: String = "",
) {
    /** How many chosen tracks follow the one playing (the generated ones not counted). */
    val chosenAfter: Int get() = ((if (recStart >= 0) recStart else items.size) - index - 1).coerceAtLeast(0)
}

/**
 * The playback engine (the desktop's `stream.go` and the playback parts of `playback.go`): owns the queue and drives one
 * ExoPlayer that holds the track playing, and for the last 20 s of it the track after it. Everything runs on the main
 * thread; resolving runs in [resolver]'s own coroutines.
 *
 * - A track is found through the URL cache ([AudioResolver]); when audio starts the next track is resolved in the
 *   background (`warmNext`), and 20 s before the end it is handed to the player so the two join without a gap
 *   (`preloadLead`, `syncPreload`).
 * - A stream that breaks (HTTP 403 from an expired URL, a dropped network, the default network changing) is found
 *   again, up to 3 times in a row, and resumes where it was (`recoverStream`).
 * - A track the resolver cannot find is skipped, up to 3 in a row (`skipUnplayable`).
 * - Each play is recorded through [PlayRecorder]: begun when audio starts, filed when it ends.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(
    private val context: Context,
    val player: ExoPlayer,
    private val resolver: AudioResolver,
    store: PlayStore,
    device: String,
    private val scope: CoroutineScope,
    private val publishTo: (PlayerUi?) -> Unit,
    private val log: (String) -> Unit = { Log.i(AudioResolver.TAG, it) },
    newId: () -> String = { com.muratkirazkaya.meiro.sync.Ids.newId() },
    upNextSource: UpNextSource? = null,
    suggestions: () -> List<QItem> = { emptyList() },
    /** Auto-play as the settings hold it, and where a change is kept (`settings.AutoPlay`, on by default). */
    autoPlayInitially: Boolean = true,
    private val saveAutoPlay: (Boolean) -> Unit = {},
) {
    val queue = Queue()
    var repeat = RepeatMode.Off; private set
    /** Shuffle (`a.shuffle`): the queue was arranged behind the track playing, and is put back when turned off. */
    var shuffle = false; private set
    /** Generated tracks follow the chosen ones (`settings.AutoPlay`). */
    var autoPlay = autoPlayInitially; private set
    private val upNext = UpNext(scope, upNextSource, suggestions, log)
    private val upNextAvailable = upNextSource != null

    private val recorder = PlayRecorder(store, device, newId)

    var current: QItem? = null; private set
    private var totalMs = 0L
    private var generation = 0
    private var loading = false
    private var playIntent = false
    private var errorText: String? = null
    private var awaitingAudio = false
    private var startJob: Job? = null
    private var startedAt = 0L

    private var skipped = 0
    private var recoveries = 0
    private var recoveredFrom = 0L

    private var preloadedKey: String? = null
    private var preloadedDurationMs = 0L
    private var preloadFailed = ""
    private var preloadJob: Job? = null
    private var preloadFor = ""

    private var lastNetwork: Network? = null
    private val main = Handler(Looper.getMainLooper())
    private val net = context.getSystemService(ConnectivityManager::class.java)
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { onNetwork(network) }
    }
    private var released = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED && !loading && current != null) trackEnded()
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && awaitingAudio) audioStarted()
            if (!isPlaying) recorder.breakWatch()
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!loading) playIntent = playWhenReady
            publish()
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // A seek jumps the position: what it jumps over was not heard.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) recorder.breakWatch()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val key = preloadedKey ?: return
            if (mediaItem?.mediaId != key || player.currentMediaItemIndex != 1) return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                followed(auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val raw = rawError(error)
            if (current == null) return
            if (atTrackEnd()) { log("stream broke off at the end of ${current?.key}, counting it as the end"); trackEnded(); return }
            if (!recover(raw)) { fail(current!!, "playback failed", raw); skipUnplayable(current!!) }
        }
    }

    init {
        player.addListener(listener)
        runCatching { net?.registerDefaultNetworkCallback(netCallback, main) }
        scope.launch { while (isActive) { tick(); delay(Watch.POLL_MS) } }
    }

    // ---- commands ------------------------------------------------------------------------------------------

    /** Plays [items] from [index]: the queue becomes exactly these tracks (`playTracks`). */
    fun playQueue(items: List<QItem>, index: Int = 0, source: String = "") {
        if (items.isEmpty()) return
        val i = index.coerceIn(0, items.size - 1)
        queue.replace(items[i], items, i, source)
        upNext.suggested.clear()
        upNext.reset(if (items[i].provider() == Source.YTM) items[i].key else "")
        if (shuffle) queue.shuffleQueue()
        skipped = 0
        startTrack(queue.at(queue.index))
    }

    /**
     * A tap on a row of the up-next list (`playFrom` with the queue's own tracks): the queue is played again from that
     * track, so the tracks generated so far become chosen ones and auto-play starts over from it. A tap on the track
     * playing does nothing.
     */
    fun playFromQueue(index: Int) {
        if (index !in 0 until queue.size || (index == queue.index && current != null)) return
        playQueue(queue.items.toList(), index, queue.source)
    }

    /** `setShuffle`: arranges the queue behind the track playing, or puts it back in the order it had. */
    fun setShuffle(on: Boolean) {
        if (shuffle == on) return
        shuffle = on
        if (on) queue.shuffleQueue() else queue.restoreQueue()
        queueChanged()
    }

    /** `cycleRepeat`: off, the queue, the track, and off again. */
    fun cycleRepeat() {
        repeat = repeat.next()
        queueChanged()
    }

    /** `setAutoPlay`: whether generated tracks can follow the chosen queue; kept for the next run. */
    fun setAutoPlay(enabled: Boolean) {
        if (autoPlay == enabled) return
        autoPlay = enabled
        saveAutoPlay(enabled)
        if (enabled) {
            upNext.restart(current?.takeIf { it.provider() == Source.YTM }?.key.orEmpty())
            queue.regenerate()
            ensureUpNext()
        } else upNext.cancel()
        publish()
    }

    /** `moveQueue` for one track: the track at [from] ends up at place [to] of the queue. */
    fun moveQueueItem(from: Int, to: Int) {
        if (from !in 0 until queue.size || to !in 0 until queue.size || from == to) return
        val before = if (to > from) to + 1 else to
        if (queue.moveRows(listOf(queue.rowOf(from)), queue.rowOf(before))) queueChanged()
    }

    /** `clearUpcoming`: drops every track after the one playing, the generated ones included. */
    fun clearUpcoming() {
        if (queue.clearUpcoming()) { upNext.suggested.clear(); queueChanged() }
    }

    /**
     * `chooseSource` for the track playing: it plays on from [source] at the place it had reached, when the track is
     * held there. Reports whether anything changed.
     */
    fun switchSource(source: String): Boolean {
        val c = current ?: return false
        if (c.provider() == source) return false
        val key = c.refFor(source) ?: return false
        val updated = c.copy(source = source, key = key, refs = c.refs + (c.provider() to c.key))
        queue.replaceAt(queue.index, updated)
        log("source: ${c.provider()}:${c.key} -> $source:$key at ${player.currentPosition} ms")
        startTrack(updated, startAt = player.currentPosition, sameTrack = true)
        return true
    }

    /** `queueChanged`: what plays next may be another track now. */
    private fun queueChanged() {
        warmNext()
        ensureUpNext()
        publish()
    }

    private fun ensureUpNext() {
        upNext.ensure(autoPlay, queue, current) { continuation, failed ->
            if (!failed) warmNext() // a generated track can be the one that plays next
            if (!upNext.waiting) { publish(); return@ensure }
            when {
                failed -> { upNext.waiting = false; stopAtQueueEnd() }
                queue.next(autoPlay, repeat) != null -> { upNext.waiting = false; advance() }
                continuation.isNotEmpty() -> ensureUpNext() // the list had more to pull
                else -> { upNext.waiting = false; stopAtQueueEnd() } // a fresh request found nothing to follow the queue
            }
        }
    }

    /** `advance`: the next track, or (auto-play on) a wait at the end of the queue for what YouTube Music follows it with. */
    private fun advance() {
        val n = queue.next(autoPlay, repeat)
        if (n != null) { skipped = 0; startTrack(queue.moveTo(n)); return }
        if (autoPlay && upNextAvailable && current?.provider() == Source.YTM) {
            upNext.waiting = true
            startJob?.cancel(); generation++
            loading = false; awaitingAudio = false
            player.stop(); player.clearMediaItems()
            log("queue ended, waiting for up next")
            ensureUpNext()
            publish()
            return
        }
        stopAtQueueEnd()
    }

    fun play() {
        val c = current ?: return
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
            if (!loading) { skipped = 0; startTrack(c) }
        } else { playIntent = true; player.play() }
        publish()
    }

    fun pause() { playIntent = false; player.pause(); publish() }

    fun togglePlay() { if (playIntent) pause() else play() }

    /** The next track of the queue, or the end of it (`advance`, chosen by the listener). */
    fun next() {
        val n = queue.next(autoPlay, repeat)
        if (n == null) { recorder.finish(false); advance(); return }
        skipped = 0
        val item = queue.at(n)
        if (preloadedKey == item.queueKey() && player.mediaItemCount == 2) {
            startedAt = SystemClock.elapsedRealtime()
            log("next: ${item.key} was handed to the player ahead of time")
            player.seekToNextMediaItem() // followed() runs from the transition
            return
        }
        startTrack(queue.moveTo(n))
    }

    /** Restarts the track, or plays the one before it when the track has only just started (`previous`). */
    fun previous() {
        if (current == null) return
        if (queue.index == 0 || player.currentPosition > 3000) { seekTo(0); return }
        skipped = 0
        startTrack(queue.moveTo(queue.index - 1))
    }

    fun seekTo(ms: Long) {
        if (current == null) return
        recorder.breakWatch()
        player.seekTo(ms.coerceAtLeast(0))
    }

    fun hasNext(): Boolean = current != null && queue.next(autoPlay, repeat) != null
    fun hasPrevious(): Boolean = current != null

    fun isPlayIntent() = playIntent

    /** Files the play in progress as not finished (quitting, `main.go`), and lets the engine go. */
    fun release() {
        if (released) return
        released = true
        recorder.finish(false)
        startJob?.cancel(); preloadJob?.cancel()
        player.removeListener(listener)
        runCatching { net?.unregisterNetworkCallback(netCallback) }
        publishTo(null)
    }

    /** Debug only: damages the current stream URL so the server answers 403, to exercise [recover]. */
    internal fun debugBreakStream() {
        val c = current ?: return
        val r = resolver.cached(c) ?: return
        val bad = r.url.replace(Regex("expire=\\d+"), "expire=1").let { if (it == r.url) "$it&meiro_break=1" else it }
        val at = player.currentPosition
        log("debug: current stream URL damaged at ${at} ms")
        player.setMediaItem(mediaItem(c, r.copy(url = bad)), at)
        player.prepare(); player.play()
    }

    // ---- starting tracks -----------------------------------------------------------------------------------

    /**
     * Finds [item]'s audio and plays it from [startAt]. [sameTrack] is a provider switch of the track playing: its play
     * goes on (not filed, not begun again).
     */
    private fun startTrack(item: QItem, startAt: Long = 0, sameTrack: Boolean = false) {
        if (!sameTrack) recorder.finish(false) // a skip, or a new track: the play before it is filed as not finished
        val gen = ++generation
        upNext.waiting = false
        current = item; totalMs = item.durationMs(); loading = true; playIntent = true
        errorText = null; recoveries = 0; awaitingAudio = !sameTrack
        startedAt = SystemClock.elapsedRealtime()
        removePreloaded(); preloadFailed = ""
        startJob?.cancel()
        val hit = resolver.cached(item) != null
        publish()
        startJob = scope.launch {
            val r = try { resolver.get(item) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                if (gen == generation) { fail(item, "resolve failed", e.message ?: e.toString()); skipUnplayable(item) }
                return@launch
            }
            if (gen != generation) return@launch
            if (r.durationMs > 0) totalMs = r.durationMs
            log("start: ${item.provider()}:${item.key} resolved in ${SystemClock.elapsedRealtime() - startedAt} ms (cache hit=$hit)")
            player.setMediaItem(mediaItem(item, r), startAt)
            player.prepare()
            loading = false
            player.playWhenReady = true
            publish()
        }
        ensureUpNext()
    }

    /** The first moment audio plays for a track: the play is recorded and the next track readied (`audioStarted`). */
    private fun audioStarted() {
        val item = current ?: return
        awaitingAudio = false
        skipped = 0
        log("start: ${item.key} audio playing ${SystemClock.elapsedRealtime() - startedAt} ms after the request")
        recorder.begin(item, item.durationMs().takeIf { it > 0 } ?: totalMs)
        warmNext()
    }

    /** Resolves the next track's URL while this one plays, so the play that follows skips yt-dlp (`warmNext`). */
    private fun warmNext() {
        val next = following() ?: return
        if (resolver.cached(next) != null) { log("warm: ${next.key} already resolved"); return }
        val t0 = SystemClock.elapsedRealtime()
        scope.launch {
            try {
                resolver.get(next)
                log("warm: next ${next.provider()}:${next.key} resolved in ${SystemClock.elapsedRealtime() - t0} ms")
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                log("warm: could not resolve next ${next.key}: ${e.message}")
            }
        }
    }

    /** The track that plays after this one; a repeated track is not preloaded (it restarts). */
    private fun following(): QItem? {
        if (current == null || repeat == RepeatMode.Track) return null
        val i = queue.next(autoPlay, repeat) ?: return null
        return queue.at(i)
    }

    private fun mediaItem(item: QItem, r: Resolved): MediaItem {
        val b = MediaItem.Builder().setMediaId(item.queueKey()).setUri(r.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title.ifEmpty { item.key }).setArtist(item.artist.ifEmpty { null }).build())
        if (r.hls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    // ---- preloading ----------------------------------------------------------------------------------------

    private fun removePreloaded() {
        preloadJob?.cancel(); preloadedKey = null; preloadFor = ""
        if (player.mediaItemCount > 1) player.removeMediaItems(1, player.mediaItemCount)
    }

    /** Keeps the player's second item the track that follows, and hands it over once this track is near its end. */
    private fun syncPreload(pos: Long) {
        val next = following()
        val held = preloadedKey
        if (held != null && (next == null || held != next.queueKey())) removePreloaded()
        if (next == null || loading || preloadJob?.isActive == true || preloadFailed == next.queueKey() ||
            preloadedKey == next.queueKey() || totalMs <= 0 || !player.isPlaying || player.mediaItemCount != 1 ||
            totalMs - pos > PRELOAD_LEAD_MS
        ) return
        val gen = generation
        preloadFor = next.queueKey()
        preloadJob = scope.launch {
            try {
                val r = resolver.get(next)
                if (gen != generation || following()?.queueKey() != next.queueKey() || player.mediaItemCount != 1) return@launch
                player.addMediaItem(mediaItem(next, r))
                preloadedKey = next.queueKey(); preloadedDurationMs = r.durationMs
                log("preload: ${next.provider()}:${next.key} handed to the player ${(totalMs - player.currentPosition) / 1000} s before the end")
            } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                log("preload: could not load ${next.key}: ${e.message}")
                preloadFailed = next.queueKey()
            }
        }
    }

    /** The player took over with the preloaded track (the old one ended, or the listener skipped to it). */
    private fun followed(auto: Boolean) {
        val key = preloadedKey ?: return
        preloadedKey = null; preloadFor = ""
        if (auto) startedAt = SystemClock.elapsedRealtime()
        recorder.finish(auto) // reached its end, or skipped
        player.removeMediaItem(0)
        val i = queue.next(autoPlay, repeat)
        if (i == null || queue.at(i).queueKey() != key) { // the queue changed since the player took it
            if (i != null) startTrack(queue.moveTo(i)) else stopAtQueueEnd()
            return
        }
        val item = queue.moveTo(i)
        generation++
        current = item; loading = false; playIntent = true; errorText = null; recoveries = 0; skipped = 0
        totalMs = preloadedDurationMs.takeIf { it > 0 } ?: item.durationMs()
        awaitingAudio = true
        log("followed: ${item.provider()}:${item.key} took over${if (auto) " at the end of the last" else " on a skip"}, ${SystemClock.elapsedRealtime() - startedAt} ms after the request")
        if (player.isPlaying) audioStarted()
        ensureUpNext()
        publish()
    }

    // ---- ending, failing, recovering -----------------------------------------------------------------------

    /** The track reached its end: file it as completed before the next one starts (`trackEnded`). */
    private fun trackEnded() {
        recorder.finish(true)
        val c = current ?: return
        if (repeat == RepeatMode.Track) { skipped = 0; startTrack(c); return }
        advance()
    }

    private fun stopAtQueueEnd() {
        upNext.waiting = false
        startJob?.cancel(); generation++
        loading = false; playIntent = false; awaitingAudio = false
        player.stop(); player.clearMediaItems()
        log("queue ended")
        publish()
    }

    private fun fail(item: QItem, what: String, raw: String) {
        log("$what ${item.provider()}:${item.key}: $raw")
        errorText = PlayError.message(raw)
        loading = false; playIntent = false
        player.stop(); player.clearMediaItems()
        recorder.finish(false)
        publish()
    }

    /** Moves on after [item] did not play, unless 3 tracks were passed over already or nothing follows (`skipUnplayable`). */
    private fun skipUnplayable(item: QItem) {
        if (current?.queueKey() != item.queueKey() || skipped >= MAX_SKIPS) return
        val n = queue.next(autoPlay, repeat) ?: return
        skipped++
        log("skipped ${item.title.ifEmpty { item.key }}: ${errorText?.trimEnd('.')}")
        startTrack(queue.moveTo(n))
    }

    /** The current track's stream broke off near its end: what is left is shorter than finding it again (`atTrackEnd`). */
    private fun atTrackEnd(): Boolean = totalMs > 0 && player.playWhenReady && totalMs - player.currentPosition <= END_OF_TRACK_LEAD_MS

    /**
     * Picks the current track up where its stream broke off (`recoverStream`): resolves it again, which the URL cache
     * must not answer from memory, and plays from the position reached, keeping pause state. Reports false when the 3
     * tries are used up. [counted] is false for a network change, which is no failure.
     */
    private fun recover(failure: String, counted: Boolean = true): Boolean {
        val item = current ?: return false
        if (counted) {
            if (recoveries >= MAX_RECOVERIES) return false
            recoveries++
            if (recoveries == 1) recoveredFrom = player.currentPosition
        }
        val attempt = recoveries.coerceAtLeast(1)
        val at = player.currentPosition
        val paused = !player.playWhenReady
        log("recover: stream broke off ${item.provider()}:${item.key} at=${at} ms attempt=$attempt/$MAX_RECOVERIES: $failure")
        val gen = ++generation
        loading = true
        removePreloaded()
        startJob?.cancel()
        val t0 = SystemClock.elapsedRealtime()
        startJob = scope.launch {
            delay(attempt * RECOVERY_BACKOFF_MS)
            resolver.forget(item)
            val r = try { resolver.get(item) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                if (gen == generation) {
                    log("recover: failed ${item.key}: ${e.message}")
                    if (!recover(e.message ?: e.toString())) { fail(item, "recovery failed", e.message ?: e.toString()); skipUnplayable(item) }
                }
                return@launch
            }
            if (gen != generation) return@launch
            if (r.durationMs > 0) totalMs = r.durationMs
            player.setMediaItem(mediaItem(item, r), at)
            player.prepare()
            loading = false
            player.playWhenReady = !paused
            playIntent = !paused
            log("recover: ${item.key} resolved again in ${SystemClock.elapsedRealtime() - t0} ms, resuming at $at ms")
            publish()
        }
        return true
    }

    /** The default network changed: an audio URL is bound to the IP that asked for it, so it is resolved again. */
    private fun onNetwork(network: Network) {
        val before = lastNetwork
        lastNetwork = network
        if (before == null || before == network) return
        val item = current ?: return
        log("network changed: ${before} -> $network")
        resolver.forget(item)
        following()?.let { resolver.forget(it) }
        if (!loading && player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED) {
            recover("network changed", counted = false)
        }
    }

    // ---- polling and state ---------------------------------------------------------------------------------

    private fun tick() {
        if (current == null) return
        val pos = player.currentPosition
        if (loading || !player.isPlaying || player.playbackState == Player.STATE_BUFFERING) recorder.breakWatch()
        else recorder.observe(SystemClock.elapsedRealtime(), pos)
        if (recoveries > 0 && pos > recoveredFrom + RECOVERY_HEALTHY_MS) recoveries = 0
        if (player.isPlaying) { syncPreload(pos); ensureUpNext() }
        publish(pos)
    }

    private fun publish(pos: Long = player.currentPosition) {
        val c = current
        publishTo(
            if (c == null) null
            else PlayerUi(
                c, playIntent, if (loading) 0 else pos.coerceAtLeast(0), totalMs, loading, errorText,
                QueueView(queue.items.toList(), queue.index, queue.recStart, upNext.suggested.toSet(), queue.source, upNext.error),
                shuffle, repeat, autoPlay,
            ),
        )
    }

    private fun rawError(e: PlaybackException): String {
        var t: Throwable? = e
        while (t != null) {
            if (t is HttpDataSource.InvalidResponseCodeException) return "HTTP error ${t.responseCode} (${e.errorCodeName})"
            t = t.cause
        }
        return "${e.errorCodeName}: ${e.cause?.message ?: e.message}"
    }

    companion object {
        const val PRELOAD_LEAD_MS = 20_000L
        const val MAX_RECOVERIES = 3
        const val RECOVERY_BACKOFF_MS = 500L
        const val RECOVERY_HEALTHY_MS = 10_000L
        const val END_OF_TRACK_LEAD_MS = 3_000L
        const val MAX_SKIPS = 3
    }
}
