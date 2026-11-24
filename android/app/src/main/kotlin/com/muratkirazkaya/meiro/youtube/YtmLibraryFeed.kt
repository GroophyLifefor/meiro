package com.muratkirazkaya.meiro.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the signed-in YouTube Music account adds to the app (M6g): its [library] (null until the first read answers)
 * and the new releases shelf ([releases]).
 */
data class YtmFeed(val library: YtmLibrary? = null, val releases: List<MusicItem> = emptyList())

/**
 * The signed-in account's library and new releases, read with the session's own client. Signed out, [state] is null
 * and nothing is asked; a sign-in (or another account) starts a fresh read, a sign-out drops what was read at once.
 * [refresh] reads again when the last read is older than [STALE_MS] (or when [force]d); a failed part keeps what an
 * earlier read gave. Logs counts and outcomes only: never a cookie, a name or a list's title.
 */
class YtmLibraryFeed(
    private val session: StateFlow<YtmSession?>,
    private val newClient: (YtmCookieAuth) -> YtmClient,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<YtmFeed?>(null)
    val state: StateFlow<YtmFeed?> = _state

    private val mu = Mutex()
    @Volatile private var loadedFor: YtmSession? = null
    @Volatile private var loadedAt = 0L
    @Volatile private var loadingFor: YtmSession? = null
    private var job: Job? = null
    private val playlists = object : LinkedHashMap<String, BrowsePage>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BrowsePage>?) = size > 8
    }

    /** Follows the session: called once by the app; each change of session clears or reads. */
    fun start(): Job = scope.launch {
        session.collect { s ->
            if (s == null) {
                job?.cancel()
                loadedFor = null; _state.value = null
                synchronized(playlists) { playlists.clear() }
            } else if (!same(loadedFor, s) && !(job?.isActive == true && same(loadingFor, s))) {
                synchronized(playlists) { playlists.clear() }
                _state.value = YtmFeed()
                refresh(force = true)
            }
        }
    }

    /** Reads the library and the new releases again when signed in and the last read is stale (or [force]). */
    fun refresh(force: Boolean = false) {
        val s = session.value ?: return
        if (!force && same(loadedFor, s) && now() - loadedAt < STALE_MS) return
        if (job?.isActive == true && !force) return
        job?.cancel()
        loadingFor = s
        job = scope.launch { load(s) }
    }

    /** One read for [s]; what it gets is dropped if the session changed meanwhile. */
    suspend fun load(s: YtmSession) = mu.withLock {
        val client = clientFor(s) ?: return@withLock
        val prev = _state.value ?: YtmFeed()
        val library = try {
            val page = client.library()
            val liked = try {
                client.playlist(YtmLibraryParse.LIKED_ID, all = false).let { p -> YtmLibraryParse.trackCount(p.secondSubtitle) ?: YtmLibraryParse.trackCount(p.subtitle) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { log("ytm library: liked count failed (${e.javaClass.simpleName})"); null }
            YtmLibraryParse.library(page, liked).also {
                log("ytm library: liked=${it.liked != null} likedCount=${it.liked?.count ?: -1} playlists=${it.playlists.size} albums=${it.albums.size}")
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { log("ytm library: read failed (${e.javaClass.simpleName})"); prev.library }
        val releases = try {
            YtmLibraryParse.releases(client.newReleases()).take(MAX_RELEASES).also { log("ytm library: releases=${it.size}") }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { log("ytm library: releases failed (${e.javaClass.simpleName})"); prev.releases }
        if (!same(session.value, s)) return@withLock
        _state.value = YtmFeed(library, releases)
        loadedFor = s; loadedAt = now()
    }

    /**
     * A playlist of the account (the liked songs too) with all its tracks, read with the session (a private playlist
     * needs it) and kept for the last 8 lists while the session lasts.
     */
    suspend fun playlist(id: String): BrowsePage {
        val s = session.value ?: throw YtmException("youtube: not signed in to YouTube Music")
        synchronized(playlists) { playlists[id] }?.let { return it }
        val client = clientFor(s) ?: throw YtmSessionGone()
        val page = client.playlist(id)
        if (same(session.value, s)) synchronized(playlists) { playlists[id] = page }
        log("ytm library: playlist read tracks=${page.tracks().size}")
        return page
    }

    /** The same session: the same cookie acting as the same channel (a check gives a new object for the same one). */
    private fun same(a: YtmSession?, b: YtmSession?) = a != null && b != null && a.cookie == b.cookie && a.channel == b.channel

    private fun clientFor(s: YtmSession): YtmClient? = try {
        newClient(YtmCookieAuth.create(s.cookie, onBehalfOfUser = s.channel))
    } catch (e: IllegalArgumentException) { log("ytm library: the session has no usable cookie"); null }

    companion object {
        /** How long a read stays fresh before Home or the Library asks again. */
        const val STALE_MS = 10 * 60_000L
        /** The new releases shelf shows at most this many albums. */
        const val MAX_RELEASES = 12
    }
}
