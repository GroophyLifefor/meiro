package com.muratkirazkaya.meiro.playback

import com.muratkirazkaya.meiro.data.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** A page of the queue YouTube Music generates after a track: its tracks, the next page's token, the queue's playlist. */
data class GeneratedPage(val items: List<QItem>, val token: String, val playlistId: String)

/** Where generated tracks come from (`GetUpNext` / `ContinueUpNext`); [continuation] is "" for a first page. */
fun interface UpNextSource {
    suspend fun page(videoId: String, playlistId: String, continuation: String): GeneratedPage
}

/**
 * Port of `upnext.go` (`upNextState`, `ensureUpNext`) and `withSuggestions` (`recommendations.go`): the tracks auto-play
 * adds after the chosen ones. The first page is asked for as soon as a track plays; another only when the generated
 * part is almost played out. Each page gains up to [SUGGESTIONS_PER_FILL] of Meiro's own picks ([suggestions], the
 * Daily mix's tracks on the phone) in front of YouTube Music's tracks. Runs on the engine's (main) thread.
 */
class UpNext(
    private val scope: CoroutineScope,
    private val source: UpNextSource?,
    private val suggestions: () -> List<QItem> = { emptyList() },
    private val log: (String) -> Unit = {},
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    var videoId = ""; private set
    var playlistId = ""; private set
    var fetched = false; private set
    var token = ""; private set
    private val seen = HashSet<String>()
    /** The last request's failure ("" when it worked): the up-next list says recommendations are not available. */
    var error = ""; private set
    /** Playback reached the end of the queue and waits for what YouTube Music has to follow it. */
    var waiting = false
    /** Meiro picks already put in a queue this run (`recs.used`), and those of them the queue holds now. */
    private val used = HashSet<String>()
    val suggested = HashSet<String>()
    private var job: Job? = null
    /** The track playing when the last request failed. */
    private var failedFor = ""
    private var generation = 0

    fun busy() = job?.isActive == true

    /** `resetUpNext`: forgets the generated pages of the queue being replaced, and drops a request on its way. */
    fun reset(videoId: String) {
        stop()
        this.videoId = videoId; playlistId = ""; fetched = false; token = ""; error = ""; waiting = false
        seen.clear()
    }

    private fun stop() { job?.cancel(); job = null; generation++ }

    /** `setAutoPlay` turned on: a fresh first page from the track playing. */
    fun restart(videoId: String) {
        stop(); error = ""; waiting = false
        fetched = false; token = ""; seen.clear(); this.videoId = videoId
    }

    /** `setAutoPlay` turned off: what is on its way is dropped. */
    fun cancel() { stop(); error = ""; waiting = false }

    /**
     * `ensureUpNext`. [onDone] runs after a page was added to [queue] (with the continuation it was asked with) or a
     * request failed (failed = true); the engine then moves on if it was [waiting].
     */
    fun ensure(autoPlay: Boolean, queue: Queue, current: QItem?, onDone: (continuation: String, failed: Boolean) -> Unit) {
        val src = source ?: return
        // A SoundCloud track has no video ID (the desktop's `a.current.VideoID == ""`): YouTube Music cannot follow it.
        if (!autoPlay || current == null || current.provider() != Source.YTM || current.key.isEmpty() || busy() || queue.size == 0) return
        // A request that failed for this track is not asked again until another track plays (M5: offline, the engine's
        // ticks would ask several times a second).
        if (error.isNotEmpty() && failedFor == current.key) return
        var continuation = ""
        if (fetched) {
            when {
                token.isEmpty() -> {
                    // Everything the pages held has been played out. At the end of the queue, ask again from the track
                    // playing, which is what keeps the queue going.
                    if (queue.remaining() > 0) return
                    seen.clear()
                    videoId = current.key
                }
                queue.remaining() > 1 -> return
                else -> {
                    continuation = token
                    if (!seen.add(continuation)) { token = ""; return }
                }
            }
        }
        fetched = true
        val vid = videoId.ifEmpty { current.key }
        val list = playlistId
        val gen = generation
        job = scope.launch {
            val page = try {
                withTimeout(timeoutMs) { src.page(vid, list, continuation) }
            } catch (e: Throwable) {
                if (e is CancellationException && gen != generation) throw e
                if (gen != generation) return@launch
                job = null
                error = e.message ?: e.javaClass.simpleName; token = ""; failedFor = current.key
                log("up next: request failed for $vid: $error")
                onDone(continuation, true)
                return@launch
            }
            if (gen != generation) return@launch
            job = null
            error = ""
            if (page.playlistId.isNotEmpty()) playlistId = page.playlistId
            token = if (page.token == continuation) "" else page.token
            val before = queue.size
            queue.appendGenerated(withSuggestions(page.items, queue, current))
            suggested.retainAll(queue.items.map { it.queueKey() }.toSet())
            log("up next: ${page.items.size} tracks from YouTube Music for $vid${if (continuation.isEmpty()) "" else " (next page)"}, queue ${before} -> ${queue.size}, more=${token.isNotEmpty()}")
            onDone(continuation, false)
        }
    }

    /** `withSuggestions`: up to [SUGGESTIONS_PER_FILL] picks the queue does not hold yet, in front of [generated]. */
    fun withSuggestions(generated: List<QItem>, queue: Queue, current: QItem?): List<QItem> {
        val picks = suggestions()
        if (picks.isEmpty()) return generated
        val chosen = ArrayList<QItem>()
        for (p in picks) {
            val k = p.queueKey()
            if (k.isEmpty() || k in used || queue.find(k) >= 0 || k == current?.queueKey()) continue
            used.add(k)
            chosen.add(p)
            if (chosen.size == SUGGESTIONS_PER_FILL) break
        }
        if (chosen.isEmpty()) return generated
        val keys = chosen.map { it.queueKey() }.toSet()
        suggested.addAll(keys)
        return chosen + generated.filter { it.queueKey() !in keys }
    }

    companion object {
        /** `upNextTimeout`. */
        const val TIMEOUT_MS = 45_000L
        /** `suggestionsPerFill`. */
        const val SUGGESTIONS_PER_FILL = 5
    }
}
