package com.muratkirazkaya.meiro.playback

import android.content.Context
import android.util.Log
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.firstUrlLine
import com.muratkirazkaya.meiro.lastErrorLine
import com.muratkirazkaya.meiro.parseSoundCloudJson
import com.muratkirazkaya.meiro.soundCloudArgs
import com.muratkirazkaya.meiro.ytmArgs
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** A resolved track: a direct audio URL, its length (0 unknown) and whether it is an HLS manifest. */
data class Resolved(val url: String, val durationMs: Long, val hls: Boolean)

/** A resolve that failed; [message] is the raw reason (yt-dlp's last ERROR line), for [PlayError.message]. */
class ResolveException(message: String) : Exception(message)

/**
 * Finds the direct audio URL of a track with yt-dlp (youtubedl-android), behind one [UrlCache] per provider as on the
 * desktop (`streamsFor`): YouTube Music resolves anonymously, visionos client alone first and the default client set
 * if that fails (`ytdlp.Resolve`); SoundCloud resolves the page URL to JSON (`ResolveSoundCloud`). Each resolve is
 * bounded by 90 s.
 */
class AudioResolver(private val context: Context, scope: CoroutineScope, private val log: (String) -> Unit = { Log.i(TAG, it) }) {
    private val initLock = Mutex()
    @Volatile private var ready = false
    private val pids = AtomicLong()

    private val ytm = UrlCache<Resolved>(scope) { id -> resolveYtm(id) }
    private val sc = UrlCache<Resolved>(scope) { url -> resolveSoundCloud(url) }

    private fun cacheFor(item: QItem) = if (item.provider() == Source.SOUNDCLOUD) sc else ytm

    suspend fun get(item: QItem): Resolved = cacheFor(item).get(item.key)

    /** The URL already in the cache, if any (no resolve is started). */
    fun cached(item: QItem): Resolved? = cacheFor(item).cached(item.key)

    /** Drops a track's URL so the next [get] resolves again (it stopped working, or the network changed). */
    fun forget(item: QItem) = cacheFor(item).forget(item.key)

    private suspend fun ensureInit() {
        if (ready) return
        initLock.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context.applicationContext)
                updateIfDue()
            }
            ready = true
        }
    }

    /**
     * YouTube changes under yt-dlp, and the copy that ships in the app ages: it is updated in the app (PLAN.md), at most
     * once a day, before the first resolve of a process. A failed update (offline) is tried again next time.
     */
    private fun updateIfDue() {
        val prefs = context.getSharedPreferences("meiro-ytdlp", Context.MODE_PRIVATE)
        val last = prefs.getLong("updatedAt", 0)
        if (System.currentTimeMillis() - last < UPDATE_EVERY_MS) return
        val t0 = System.nanoTime()
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("updatedAt", System.currentTimeMillis()).apply()
            log("yt-dlp update: $status in ${(System.nanoTime() - t0) / 1_000_000} ms")
        } catch (e: Throwable) {
            log("yt-dlp update failed (will retry next start): ${e.message}")
        }
    }

    private suspend fun resolveYtm(id: String): Resolved {
        ensureInit()
        val t0 = System.nanoTime()
        val url = try {
            run(ytmArgs(id, true), "ytm-v")
        } catch (e: ResolveException) {
            log("resolve: js-less failed for ytm:$id, retrying with the solver: ${e.message}")
            run(ytmArgs(id, false), "ytm-d")
        }
        val r = Resolved(url, durationMsFromUrl(url), false)
        log("resolve: ytm:$id ok in ${(System.nanoTime() - t0) / 1_000_000} ms duration=${r.durationMs} ms ${urlShape(url)}")
        return r
    }

    private suspend fun resolveSoundCloud(page: String): Resolved {
        ensureInit()
        val t0 = System.nanoTime()
        val out = runRaw(soundCloudArgs(page), "sc")
        val info = try { parseSoundCloudJson(out) } catch (e: Exception) { throw ResolveException("could not resolve the audio") }
        val r = Resolved(info.url, ((info.durationSec ?: 0.0) * 1000).toLong(), info.hls)
        log("resolve: sc:$page ok in ${(System.nanoTime() - t0) / 1_000_000} ms duration=${r.durationMs} ms hls=${r.hls}")
        return r
    }

    private suspend fun run(args: List<String>, tag: String): String =
        firstUrlLine(runRaw(args, tag)) ?: throw ResolveException("could not resolve the audio")

    /** Runs yt-dlp and returns stdout; on failure the reason is the last `ERROR:` line of stderr. */
    private suspend fun runRaw(args: List<String>, tag: String): String {
        val pid = "$tag-${pids.incrementAndGet()}"
        val req = YoutubeDLRequest(emptyList()).apply { addCommands(args) }
        return withContext(Dispatchers.IO) {
            // A watchdog ends the process at the resolve timeout: a blocked execute cannot be cancelled otherwise.
            val dog = launch { delay(UrlCache.RESOLVE_TIMEOUT_MS - 2_000); YoutubeDL.getInstance().destroyProcessById(pid) }
            try {
                YoutubeDL.getInstance().execute(req, pid, null).out
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = e.message.orEmpty()
                throw ResolveException(lastErrorLine(msg) ?: msg.lineSequence().lastOrNull { it.isNotBlank() } ?: e.toString())
            } finally {
                dog.cancel()
            }
        }
    }

    companion object {
        const val TAG = "MeiroPlay"
        const val UPDATE_EVERY_MS = 24 * 60 * 60 * 1000L

        /** The non-secret shape of a media URL for the log: the client, itag and the address family it was issued for. */
        fun urlShape(url: String): String {
            val q = url.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val ip = q["ip"].orEmpty().replace("%3A", ":")
            return "[client=${q["c"]} itag=${q["itag"]} ip=${if (ip.contains(':')) "v6" else if (ip.isEmpty()) "none" else "v4"} host=${url.substringAfter("://").substringBefore('/')}]"
        }
    }
}
