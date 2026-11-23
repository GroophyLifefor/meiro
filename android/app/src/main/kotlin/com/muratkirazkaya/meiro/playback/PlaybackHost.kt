package com.muratkirazkaya.meiro.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The process-wide handle on playback for the UI: the state the mini player shows, and commands that start the
 * service when it is not running. The service owns the engine; this only reaches it.
 */
object PlaybackHost {
    private val _ui = MutableStateFlow<PlayerUi?>(null)
    /** What is playing, or null when the queue is empty (the mini player is hidden). */
    val ui: StateFlow<PlayerUi?> = _ui

    private val engineFlow = MutableStateFlow<PlaybackEngine?>(null)
    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal fun publish(state: PlayerUi?) { _ui.value = state }
    internal fun attach(e: PlaybackEngine) { engineFlow.value = e }
    internal fun detach(e: PlaybackEngine?) {
        if (e != null && engineFlow.value === e) { engineFlow.value = null; _ui.value = null; controller?.release(); controller = null }
    }

    /**
     * Runs [block] on the engine, starting the service first if needed. A [MediaController] is what starts it: binding
     * through the controller lets Media3 move the service to the foreground itself once playback begins.
     */
    fun withEngine(context: Context, block: (PlaybackEngine) -> Unit) {
        scope.launch {
            val have = engineFlow.value
            if (have != null) { block(have); return@launch }
            connect(context.applicationContext)
            block(engineFlow.filterNotNull().first())
        }
    }

    private suspend fun connect(app: Context) {
        if (controller != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        controller = suspendCancellableCoroutine { cont ->
            val f = MediaController.Builder(app, token).buildAsync()
            f.addListener({ cont.resumeWith(runCatching { f.get() }) }, ContextCompat.getMainExecutor(app))
        }
    }

    fun playQueue(context: Context, items: List<QItem>, index: Int = 0, source: String = "") =
        withEngine(context) { it.playQueue(items, index, source) }

    fun togglePlay(context: Context) = withEngine(context) { it.togglePlay() }
    fun next(context: Context) = withEngine(context) { it.next() }
    fun previous(context: Context) = withEngine(context) { it.previous() }
    fun pause(context: Context) = withEngine(context) { it.pause() }
    fun seekTo(context: Context, ms: Long) = withEngine(context) { it.seekTo(ms) }
    fun setShuffle(context: Context, on: Boolean) = withEngine(context) { it.setShuffle(on) }
    fun cycleRepeat(context: Context) = withEngine(context) { it.cycleRepeat() }
    fun setAutoPlay(context: Context, on: Boolean) = withEngine(context) { it.setAutoPlay(on) }
    fun playFromQueue(context: Context, index: Int) = withEngine(context) { it.playFromQueue(index) }
    fun moveQueueItem(context: Context, from: Int, to: Int) = withEngine(context) { it.moveQueueItem(from, to) }
    fun clearUpcoming(context: Context) = withEngine(context) { it.clearUpcoming() }
    /** Switches the track playing to [source]; [onSwitched] gets the key it had before, when it switched. */
    fun switchSource(context: Context, source: String, onSwitched: (oldKey: String) -> Unit = {}) = withEngine(context) { e ->
        val old = e.current?.key.orEmpty()
        if (e.switchSource(source)) onSwitched(old)
    }
    internal fun debugBreak(context: Context) = withEngine(context) { it.debugBreakStream() }
}
