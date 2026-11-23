package com.muratkirazkaya.meiro.downloads

import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.Resolved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * One row of "İniyor": a list asked for with "Hepsini indir" or a hero's download button, or one track asked for from
 * its menu. [done] and [failed] count finished tracks, [progress] is 0..1 over all of them (the running ones by bytes).
 */
data class DownloadBatch(
    val id: String,
    val name: String,
    val cover: String,
    val total: Int,
    val done: Int,
    val failed: Int,
    val running: Int,
    val progress: Float,
    /** True for a single track asked for from its menu (the row names the track, not a list). */
    val single: Boolean,
    val artist: String = "",
)

/** What a download request did, for its snack (`downloadCollection`'s notices). */
data class DownloadAsk(val started: Int, val kept: Int, val pending: Int)

/**
 * The download queue (`download.go`): at most [maxRunning] downloads at once (`maxDownloads`), the rest wait their turn,
 * oldest first; each resolves the track (network only, never a local file), then saves its audio into [store] in the
 * container it arrives in, bounded by 30 minutes (`downloadTimeout`). A track already in the playback cache is copied
 * from there. Tracks already kept or already on their way are skipped. A batch can be cancelled as a whole ("×"), or
 * all of them ("Hepsini durdur"); a cancelled track leaves no file. [onFinished] runs when a batch has ended, with how
 * many of its tracks failed; [onStored] after each kept track (the sync sends it).
 */
class Downloader(
    private val store: DownloadStore,
    private val scope: CoroutineScope,
    private val resolve: suspend (QItem) -> Resolved,
    private val fetch: AudioFetch,
    private val cached: (QItem) -> File? = { null },
    private val maxRunning: Int = MAX_DOWNLOADS,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val onFinished: (DownloadBatch) -> Unit = {},
    private val onStored: (DownloadItem) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Track(val item: QItem, val batch: String) {
        var job: Job? = null
        var state = 0 // 0 waiting, 1 running, 2 done, 3 failed
        var bytes = 0L
        var total = -1L
    }

    private class Batch(val id: String, val name: String, val cover: String, val single: Boolean, val artist: String) {
        val tracks = mutableListOf<Track>()
    }

    private val lock = Any()
    private val batches = LinkedHashMap<String, Batch>()
    private val slots = Semaphore(maxRunning)
    private var nextId = 0
    private var lastPublish = 0L
    private val _state = MutableStateFlow<List<DownloadBatch>>(emptyList())

    /** The batches on their way, in the order they were asked for. */
    val state: StateFlow<List<DownloadBatch>> = _state

    /** Whether [item] waits or downloads now. */
    fun pending(item: QItem): Boolean = synchronized(lock) { batches.values.any { b -> b.tracks.any { it.item.key == item.key && it.state < 2 } } }

    /**
     * Asks for [items] to be downloaded as one batch named [name] (null: a single track, named by itself). Returns how
     * many were started, already kept and already on their way.
     */
    fun download(items: List<QItem>, name: String?, cover: String = ""): DownloadAsk {
        var kept = 0; var pending = 0
        val todo = mutableListOf<QItem>()
        val seen = HashSet<String>()
        for (it in items) {
            if (it.key.isEmpty() || !seen.add(it.provider() + ":" + it.key)) continue
            when {
                store.has(it) -> kept++
                pending(it) -> pending++
                else -> todo += it
            }
        }
        if (todo.isEmpty()) return DownloadAsk(0, kept, pending)
        synchronized(lock) {
            val first = todo.first()
            val b = Batch("b${++nextId}", name ?: first.title, cover.ifEmpty { first.thumbnail }, name == null, if (name == null) first.artist else "")
            batches[b.id] = b
            for (item in todo) {
                val t = Track(item, b.id)
                b.tracks += t
                t.job = scope.launch { run(t) }
            }
        }
        log("download: ${todo.size} asked for${name?.let { " from \"$it\"" } ?: ""} ($kept kept, $pending on their way)")
        publish(force = true)
        return DownloadAsk(todo.size, kept, pending)
    }

    private suspend fun run(t: Track) {
        slots.withPermit {
            synchronized(lock) { t.state = 1 }
            publish(force = true)
            val item = t.item
            val t0 = now()
            try {
                val base = store.pathFor(item.provider(), item.key, "")
                val file = withTimeout(timeoutMs) {
                    val hit = cached(item)
                    if (hit != null) {
                        val dest = File(base.path + "." + hit.extension)
                        hit.copyTo(dest, overwrite = true)
                        log("download: ${item.provider()}:${item.key} copied from the playback cache")
                        dest
                    } else {
                        val r = resolve(item)
                        fetch.save(r.url, r.hls, base) { bytes, total ->
                            synchronized(lock) { t.bytes = bytes; t.total = total }
                            publish()
                        }
                    }
                }
                val rec = DownloadItem(
                    source = item.provider(), key = item.key, title = item.title, artist = item.artist, duration = item.duration,
                    thumbnail = item.thumbnail, sizeBytes = file.length(), downloadedAt = now(), file = file.name,
                    refs = (item.refs + (item.provider() to item.key)).filterValues { it.isNotEmpty() },
                )
                store.add(rec)
                synchronized(lock) { t.state = 2 }
                log("download: ${item.provider()}:${item.key} kept ${file.length()} bytes in ${now() - t0} ms (${file.name})")
                onStored(rec)
            } catch (e: CancellationException) {
                synchronized(lock) { t.state = 3 }
                throw e
            } catch (e: Throwable) {
                synchronized(lock) { t.state = 3 }
                log("download: ${item.provider()}:${item.key} failed: ${e.message ?: e}")
            } finally {
                ended(t)
            }
        }
    }

    private fun ended(t: Track) {
        val finished = synchronized(lock) {
            val b = batches[t.batch] ?: return
            if (b.tracks.any { it.state < 2 }) null else { batches.remove(b.id); view(b) }
        }
        publish(force = true)
        finished?.let(onFinished)
    }

    /** "×" of a row: its tracks still waiting or running stop and leave nothing behind; the kept ones stay. */
    fun cancel(batchId: String) {
        val b = synchronized(lock) { batches.remove(batchId) } ?: return
        b.tracks.forEach { it.job?.cancel() }
        log("download: \"${b.name}\" stopped")
        publish(force = true)
    }

    /** "Hepsini durdur". */
    fun cancelAll() = synchronized(lock) { batches.keys.toList() }.forEach(::cancel)

    private fun view(b: Batch): DownloadBatch {
        val done = b.tracks.count { it.state == 2 }
        val failed = b.tracks.count { it.state == 3 }
        val running = b.tracks.filter { it.state == 1 }
        val partial = running.sumOf { t -> if (t.total > 0) (t.bytes.toDouble() / t.total).coerceIn(0.0, 1.0) else 0.0 }
        val progress = if (b.tracks.isEmpty()) 0f else ((done + failed + partial) / b.tracks.size).toFloat()
        return DownloadBatch(b.id, b.name, b.cover, b.tracks.size, done, failed, running.size, progress, b.single, b.artist)
    }

    private fun publish(force: Boolean = false) {
        val t = now()
        synchronized(lock) {
            if (!force && t - lastPublish < 250) return
            lastPublish = t
            _state.value = batches.values.map { view(it) }
        }
    }

    companion object {
        /** `maxDownloads`. */
        const val MAX_DOWNLOADS = 2
        /** `downloadTimeout`. */
        const val TIMEOUT_MS = 30L * 60 * 1000
    }
}
