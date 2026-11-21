package com.muratkirazkaya.meiro.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Port of `internal/audio/urls.go`: remembers resolved audio URLs so a track resolved ahead of time plays without
 * waiting on yt-dlp. A URL is a few hundred bytes. Concurrent requests for one track share a single resolve (it
 * goes on when the asking coroutine is cancelled, so a later request can still use it); a failed resolve is not
 * remembered, and [forget] drops an entry so the next [get] resolves again. Safe for concurrent use.
 */
class UrlCache<V : Any>(
    private val scope: CoroutineScope,
    private val ttlMs: Long = TTL_MS,
    private val limit: Int = LIMIT,
    private val timeoutMs: Long = RESOLVE_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val resolve: suspend (String) -> V,
) {
    private class Entry<V>(val at: Long, val result: CompletableDeferred<V> = CompletableDeferred())

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry<V>>()

    /** The URL (and whatever else [resolve] returns) of a track: from a fresh entry, a resolve in flight, or anew. */
    suspend fun get(key: String): V {
        val e: Entry<V>
        var start = false
        synchronized(lock) {
            val have = entries[key]
            if (have == null || stale(have)) {
                e = Entry(now()); entries[key] = e; trimLocked(); start = true
            } else e = have
        }
        if (start) scope.launch { run(key, e) }
        return e.result.await()
    }

    /** What is remembered of a finished resolve, without starting one. */
    fun cached(key: String): V? = synchronized(lock) {
        entries[key]?.takeIf { !stale(it) && it.result.isCompleted && !it.result.isCancelled }?.result?.getCompleted()
    }

    /** Drops what is remembered of a track (a URL that stopped working before its time). */
    fun forget(key: String) { synchronized(lock) { entries.remove(key) } }

    fun size(): Int = synchronized(lock) { entries.size }

    private suspend fun run(key: String, e: Entry<V>) {
        try {
            val v = withTimeout(timeoutMs) { resolve(key) }
            e.result.complete(v)
        } catch (t: TimeoutCancellationException) {
            fail(key, e, IOException("timed out after ${timeoutMs / 1000}s resolving $key"))
        } catch (t: Throwable) {
            fail(key, e, t)
        }
    }

    private fun fail(key: String, e: Entry<V>, t: Throwable) {
        // Remove the failed result before waking waiters, or a caller that retries at once could reuse it.
        synchronized(lock) { if (entries[key] === e) entries.remove(key) }
        e.result.completeExceptionally(t)
    }

    private fun stale(e: Entry<V>): Boolean = e.result.isCompleted && now() - e.at > ttlMs

    /** Drops the oldest entries beyond the limit. */
    private fun trimLocked() {
        while (entries.size > limit) {
            val oldest = entries.entries.minByOrNull { it.value.at } ?: return
            entries.remove(oldest.key)
        }
    }

    companion object {
        /** YouTube's media URLs stay valid for hours; this is a wide safety margin. */
        const val TTL_MS = 30 * 60 * 1000L
        const val LIMIT = 8
        const val RESOLVE_TIMEOUT_MS = 90_000L
    }
}
