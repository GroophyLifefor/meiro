package com.muratkirazkaya.meiro.downloads

import com.muratkirazkaya.meiro.ui.DownloadsMapping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the download notification shows (the mock's lock screen `.notif`): the "İniyor" row's name and line, and its
 * bar in percent. With several batches the title counts them ("2 indirme") and the line sums their tracks.
 */
data class DownloadNotice(val title: String, val text: String, val percent: Int)

/**
 * Keeps the download queue going with the app in the background or the screen off (M6e): a foreground service
 * ([DownloadService], type dataSync) runs from the moment the queue has a batch until it is empty again, whether its
 * batches finished, failed or were stopped. The rules live here, away from Android, so they are tested on the JVM:
 *
 * - [watch] (the app's side) asks for the service whenever the queue has a batch and the service is not running
 *   (once per stop: a start Android refuses is not retried on every progress step);
 * - [serve] (the service's side) shows a [DownloadNotice] for each state of the queue and stops the service once the
 *   queue is empty. "Yalnız Wi-Fi'de indir" waiting counts as not empty: the queue is not done.
 */
class DownloadForeground(private val state: StateFlow<List<DownloadBatch>>, private val log: (String) -> Unit = {}) {
    private val _running = MutableStateFlow(false)
    /** True from the service's start until it decides to stop. */
    val running: Boolean get() = _running.value
    /** A start was asked for and the service has not begun yet (or could not start): not asked again until it changes. */
    @Volatile private var asked = false
    /** Bumped by [stopped], so the watcher looks again even when nothing else changed. */
    private val nudge = MutableStateFlow(0)

    /**
     * The app's side: [start] is asked for whenever the queue has a batch and no service runs, also when the service
     * stops while a new batch was being asked for.
     */
    fun watch(scope: CoroutineScope, start: () -> Unit): Job = scope.launch {
        combine(state, _running, nudge) { b, r, _ -> b to r }.collect { (batches, run) ->
            if (batches.isNotEmpty() && !run && !asked) {
                asked = true
                log("download service: asked to start (${batches.size} batch(es))")
                try { start() } catch (e: Exception) { log("download service: could not start: ${e.message ?: e}") }
            }
        }
    }

    /** The notice for the queue as it is now; null when it is empty. */
    fun now(): DownloadNotice? = notice(state.value)

    /**
     * The service's side, from its start: [show] for each state of the queue, then [stop] once, when it is empty. Returns
     * after [stop].
     */
    suspend fun serve(show: (DownloadNotice) -> Unit, stop: () -> Unit) {
        // Running before the ask is cleared: in between, the watcher would see neither and ask again, and that second
        // ask (ignored by a service already serving) would stay set, so the next queue would never start the service.
        _running.value = true
        asked = false
        state.first { batches ->
            val n = notice(batches)
            if (n != null) show(n) else { asked = false; _running.value = false; log("download service: the queue is empty, stopping"); stop() }
            n == null
        }
    }

    /** The service stopped for another reason (Android's time limit, the system): the next batch starts it again. */
    fun stopped() { asked = false; _running.value = false; nudge.update { it + 1 } }

    companion object {
        fun notice(batches: List<DownloadBatch>): DownloadNotice? {
            if (batches.isEmpty()) return null
            val b = batches.singleOrNull() ?: run {
                val total = batches.sumOf { it.total }
                DownloadBatch(
                    id = "all", name = "${batches.size} indirme", cover = "", total = total,
                    done = batches.sumOf { it.done }, failed = batches.sumOf { it.failed }, running = batches.sumOf { it.running },
                    progress = if (total == 0) 0f else batches.sumOf { (it.progress * it.total).toDouble() }.toFloat() / total,
                    single = false, waitingWifi = batches.all { it.waitingWifi },
                )
            }
            return DownloadNotice(b.name, DownloadsMapping.pending(b).meta, (b.progress * 100).toInt().coerceIn(0, 100))
        }
    }
}
