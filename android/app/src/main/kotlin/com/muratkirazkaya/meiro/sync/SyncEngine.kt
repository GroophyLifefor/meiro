package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.Clock
import com.muratkirazkaya.meiro.data.History
import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library as LocalLibrary
import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.MemoryPersistence
import com.muratkirazkaya.meiro.data.Persistence
import com.muratkirazkaya.meiro.data.RemoteItem
import com.muratkirazkaya.meiro.data.Rfc3339Nano
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.data.SyncSettings
import com.muratkirazkaya.meiro.data.isZero
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.cancellation.CancellationException

/** The identity the server knows a list by: the one it gave a list that came from it, else derived from the local id. */
fun libraryServerId(localId: String, serverId: String): String = serverId.ifEmpty { Ids.libraryId(localId) }

/** A 401 from any call made with the session token: the session ended (tokens last 30 days, no refresh). */
fun sessionEnded(e: Throwable?): Boolean = e is ApiException && e.status == 401

/** What the card says once the server no longer takes the session token. */
const val SESSION_ENDED_MESSAGE = "Your session ended. Sign in again to keep syncing."

/** One line for a failure (`syncMessage`). */
fun syncMessage(e: Throwable): String {
    if (e is ApiException && e.status == 401) return "The server did not accept the email and password."
    return "Could not reach the server: " + (e.message ?: e.toString()).lineSequence().first()
}

/**
 * The local playlists, their items and the downloads, ready to queue for the server (`syncSnapshot`).
 */
data class SyncSnapshot(val libraries: List<Library>, val items: List<LibraryItems>, val downloads: List<Download>) {
    /** Puts the snapshot in the outbox, replacing what was pending there for the same records. */
    fun queue(o: Outbox) {
        o.addLibraries(libraries)
        for (g in items) o.addLibraryItems(g.libraryId, g.items)
        o.setDownloads(downloads)
    }
}

/**
 * The phone's Meiro account and two-way sync engine: a line by line port of the desktop's syncpage.go, syncpull.go
 * and internal/sync. Everything that touches state runs under one lock (the desktop's main thread); network calls
 * run outside it, and a result is applied only if its task was not stopped or superseded meanwhile (`startTask`).
 *
 * Operations are suspend functions that finish when their own work and apply are done; the follow-up work an apply
 * starts (a pull after a push, a push after links changed) is launched in [scope] like the desktop's goroutines.
 * [settled] waits for that follow-up work.
 */
class SyncEngine(
    val libraries: LibraryStore,
    val outbox: Outbox,
    private val tokens: TokenStore,
    private val persistence: Persistence = MemoryPersistence(),
    val links: LinksStore? = null,
    private val clock: Clock = Clock.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** The device name plays and downloads report. */
    val deviceName: String = "unknown",
    private val http: OkHttpClient = MeiroClient.defaultHttp(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Waits between delivery attempts; tests replace it so backoff costs no time. */
    private val sleep: suspend (Duration) -> Unit = { delay(it.toMillis()) },
    /** The downloads of this device to send; the downloads store (M5) plugs in here. */
    private val downloads: () -> List<Download> = { emptyList() },
) {
    private val lock = ReentrantLock()

    // ---- state (the desktop's app.settings, app.history and app.sync) ----------------------------------------
    @Volatile var settings: SyncSettings = persistence.loadSettings()
        private set
    private var history: List<HistoryEntry> = persistence.loadHistory()

    /** The session token, empty until one is read or signed in for. */
    @Volatile var token: String = ""
        private set
    /** Why signing in or syncing last failed, for the card. */
    @Volatile var lastError: String = ""
        private set
    /** The server stopped taking the session token: nothing is sent or read until the account signs in again. */
    @Volatile var expired: Boolean = false
        private set

    fun history(): List<HistoryEntry> = lock.withLock { history }

    /** Tests: an engine signed in to [url] as [email] with a token, as the desktop tests' `signedInTo`. */
    internal fun testSignIn(url: String, email: String = "me@example.com", token: String = "TOKEN") {
        lock.withLock { update { it.copy(syncUrl = url, syncEmail = email, syncEnabled = true) }; this.token = token; saveSettings() }
    }

    /** Tests: change settings the way the desktop tests assign `a.settings.X`. */
    internal fun testUpdateSettings(f: (SyncSettings) -> SyncSettings) { lock.withLock { update(f) } }

    // ---- tasks -----------------------------------------------------------------------------------------------
    private class Task {
        var gen = 0L
        var cancel: (() -> Unit)? = null
        val busy get() = cancel != null
        fun stop() { gen = generation.incrementAndGet(); cancel?.invoke(); cancel = null }
        companion object { val generation = AtomicLong() }
    }

    private val loadTask = Task()
    private val login = Task()
    private val push = Task()
    private val pull = Task()
    private val changes = Task()
    private val events = Task()
    private var pullAgain = false
    private var pullEventsAgain = false

    val pushBusy: Boolean get() = lock.withLock { push.busy }

    /**
     * Runs [work] off the lock with a cancellable scope, then [apply] under the lock with what it returned, unless the
     * task was started again or stopped meanwhile (`startTask`). Starting a task stops the one before it.
     */
    private suspend fun <T> startTask(t: Task, timeoutMs: Long?, work: suspend () -> T, apply: (Result<T>) -> Unit) {
        val gen: Long
        val job = scope.async(start = CoroutineStart.LAZY) {
            try {
                Result.success(if (timeoutMs != null) withTimeout(timeoutMs) { work() } else work())
            } catch (e: TimeoutCancellationException) {
                Result.failure(IOException("timed out"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        lock.withLock {
            t.stop()
            gen = t.gen
            t.cancel = { job.cancel() }
        }
        job.start()
        val res = try {
            job.await()
        } catch (e: CancellationException) {
            job.cancel()
            if (!currentCoroutineContext().isActive) throw e
            return // superseded: stop already cancelled the work
        }
        lock.withLock {
            if (t.gen != gen) return // superseded
            t.cancel = null
            apply(res)
        }
    }

    private val followUps = CopyOnWriteArrayList<Job>()

    /** Starts work that the desktop starts with a fire-and-forget task from inside an apply. */
    private fun followUp(block: suspend () -> Unit) {
        val job = scope.launch { block() }
        followUps.add(job)
        job.invokeOnCompletion { followUps.remove(job) }
    }

    /** Waits until no follow-up work is in flight. Operations awaited by their callers are not included. */
    suspend fun settled() {
        while (true) {
            val jobs = followUps.toTypedArray() // atomic snapshot (toList() races with removal)
            if (jobs.isEmpty()) return
            jobs.asList().joinAll()
        }
    }

    private fun client(url: String) = MeiroClient(url, token, http)

    private fun saveSettings() = persistence.saveSettings(settings)
    private fun saveHistory() = persistence.saveHistory(history)
    private fun update(f: (SyncSettings) -> SyncSettings) { settings = f(settings) }

    private fun today(): String = clock.now().atZone(zone).toLocalDate().toString()

    // ---- reading the saved session; signing in and out --------------------------------------------------------

    /** Reads the saved session token when an account is signed in, and sends/reads what waits (`loadSyncToken`). */
    suspend fun loadSyncToken() {
        lock.withLock {
            if (settings.syncEmail.isEmpty() || token.isNotEmpty() || loadTask.busy) return
        }
        startTask(loadTask, null, { tokens.load() }) { r ->
            if (r.isFailure) { SyncLog.log("sync: reading the saved session: ${r.exceptionOrNull()}"); return@startTask }
            val saved = r.getOrNull()
            if (saved == null) {
                // The session was ended, here or on an earlier run, and no token is left to try.
                SyncLog.log("sync: reading the saved session: not signed in for sync")
                markSessionEnded()
                return@startTask
            }
            token = saved
            followUp { pullLinks() }; followUp { pullChanges() }; followUp { pullEvents() }; followUp { syncNow() }
        }
    }

    /**
     * Signs in with the account, keeps the token apart from the settings, and turns syncing on (`signInSync`).
     * A failure is in [lastError]; a refused sign in is not an ended session.
     */
    suspend fun signIn(email: String, password: String) {
        val e = email.trim()
        val url: String
        lock.withLock {
            if (e.isEmpty() || password.isEmpty()) { lastError = "Enter your email and password."; return }
            url = settings.syncUrl.trim()
            if (url.isEmpty()) { lastError = "Enter the server address."; return }
            if (login.busy) return
        }
        val c = MeiroClient(url, "", http)
        startTask(login, SYNC_CALL_TIMEOUT_MS, {
            val t = c.login(e, password)
            tokens.save(t)
            t
        }) { r ->
            val t = r.getOrNull()
            if (t == null) { lastError = syncMessage(r.exceptionOrNull()!!); return@startTask }
            val previous = settings.syncEmail
            if (previous.isNotEmpty() && !previous.equals(e, ignoreCase = true)) {
                // The account that ended its session left a clearing that never reached its server; it must not
                // clear this one.
                update { it.copy(historyClearPending = null, historyClearedAt = null) }
            }
            token = t; lastError = ""; expired = false
            update { it.copy(syncUrl = url, syncEmail = e, syncEnabled = true) }
            saveSettings()
            followUp { pullLinks() }; followUp { pullChanges() }; followUp { pullEvents() }; followUp { syncNow() }
        }
    }

    /** Forgets the account: its token leaves the disk, syncing stops, and the records that wait are kept. */
    fun signOut() {
        lock.withLock {
            loadTask.stop(); login.stop(); push.stop(); pull.stop(); changes.stop(); pullAgain = false
            events.stop(); pullEventsAgain = false
            // A clearing that never reached the server belongs to the account just left.
            update { it.copy(historyClearPending = null, historyClearedAt = null) }
            token = ""; lastError = ""; expired = false
            update {
                it.copy(
                    syncEmail = "", syncEnabled = false,
                    syncCursor = 0, syncCursorUrl = "", syncCursorEmail = "",
                    eventCursor = 0, eventCursorUrl = "", eventCursorEmail = "",
                )
            }
            saveSettings()
        }
        try { tokens.delete() } catch (e: Exception) { SyncLog.log("sync: removing the saved session: $e") }
    }

    /**
     * Handles a failed call made with the session token and says whether it was the session ending. If so, syncing
     * stops until the account signs in again: the token is forgotten and its file removed, but the email and server
     * stay, and so do the records that wait and the cursors. Called with the lock held.
     */
    private fun endSyncSession(err: Throwable?): Boolean {
        if (!sessionEnded(err) || (token.isEmpty() && !expired)) return false
        if (expired) return true
        SyncLog.log("sync: the server no longer takes the session, waiting for a new sign in")
        push.stop(); pull.stop(); changes.stop(); pullAgain = false; events.stop(); pullEventsAgain = false
        token = ""
        markSessionEnded()
        try { tokens.delete() } catch (e: Exception) { SyncLog.log("sync: removing the saved session: $e") }
        return true
    }

    private fun markSessionEnded() { expired = true; lastError = "" }

    /** Keeps the server address the form holds; blank reads as the default. */
    fun setSyncUrl(url: String) {
        lock.withLock {
            val u = url.trim().ifEmpty { SyncSettings.DEFAULT_URL }
            update { it.copy(syncUrl = u) }
            saveSettings()
        }
    }

    /** Pauses or resumes sending; what waits is kept. */
    fun setSyncEnabled(on: Boolean) {
        lock.withLock { update { it.copy(syncEnabled = on) }; saveSettings() }
        if (on) followUp { syncNow() }
    }

    // ---- push -----------------------------------------------------------------------------------------------

    private class Pushed(var delivered: Int = 0, var cleared: Boolean = false, var clearedAt: Instant = Rfc3339Nano.ZERO, var error: Throwable? = null)

    /** The local lists as the server wants them (`syncSnapshot`). */
    fun syncSnapshot(): SyncSnapshot {
        val libs = mutableListOf<Library>()
        val groups = mutableListOf<LibraryItems>()
        for (lib in libraries.list()) {
            val items = ArrayList<LibraryItem>(lib.items.size + lib.removed.size)
            lib.items.forEachIndexed { i, item ->
                val (src, id) = item.activeRef()
                val track = trackFor(src, id, "", item.title, item.artist, item.durationMs)
                    .copy(thumbnail = item.thumbnail, albumThumbnail = item.albumThumbnail)
                items.add(LibraryItem(track = track, position = i, note = item.why, updatedAt = item.updatedAt))
            }
            // A track taken out is sent as deleted, at the time it was taken out, or the server would keep it and
            // send it back to every device.
            for (gone in lib.removed) {
                items.add(LibraryItem(track = trackFor(gone.source, gone.key, gone.url, gone.title, gone.artist, 0), updatedAt = gone.removedAt, deleted = true))
            }
            val id = libraryServerId(lib.id, lib.serverId)
            libs.add(Library(id = id, kind = "custom", name = lib.name, cover = lib.cover, updatedAt = lib.updatedAt))
            groups.add(LibraryItems(id, items))
        }
        for (gone in libraries.deleted()) {
            libs.add(Library(id = libraryServerId(gone.id, gone.serverId), kind = "custom", name = gone.name, updatedAt = gone.deletedAt, deleted = true))
        }
        return SyncSnapshot(libs, groups, downloads())
    }

    /**
     * Sends what waits, if a delivery is not already on its way and an account is signed in (`syncNow`). A failure
     * leaves everything in the queue for the next try. A clearing of the history that has not reached the server goes
     * first, so the plays the push brings are judged against it.
     */
    suspend fun syncNow() {
        val c: MeiroClient
        val snapshot: SyncSnapshot
        val clearPending: Instant?
        lock.withLock {
            if (!settings.syncEnabled || token.isEmpty() || push.busy) return
            c = client(settings.syncUrl)
            snapshot = syncSnapshot()
            clearPending = settings.historyClearPending
        }
        startTask(push, null, {
            val out = Pushed()
            if (clearPending != null) {
                try {
                    out.clearedAt = c.clearEvents(); out.cleared = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SyncLog.log("sync: clearing the history on the server: $e")
                    if (sessionEnded(e)) { out.error = e; return@startTask out }
                }
            }
            snapshot.queue(outbox)
            var tries = 1
            while (true) {
                val res = outbox.push(c)
                out.delivered += res.sent
                val err = res.error ?: break
                if (!currentCoroutineContext().isActive || !isRetryable(err) || tries >= SYNC_ATTEMPTS) { out.error = err; break }
                sleep(Outbox.backoff(tries))
                tries++
            }
            out
        }) { r ->
            val out = r.getOrNull() ?: Pushed(error = r.exceptionOrNull())
            if (out.cleared) {
                // The server has forgotten the plays up to the time it gives. A clearing made again meanwhile is
                // newer and still waits.
                val p = settings.historyClearPending
                if (p == null || !p.isAfter(clearPending)) update { it.copy(historyClearPending = null) }
                update { it.copy(historyClearedAt = out.clearedAt) }
                saveSettings()
            }
            val err = out.error
            if (err != null) {
                SyncLog.log("sync: $err")
                if (endSyncSession(err)) return@startTask
                lastError = syncMessage(err)
                return@startTask
            }
            lastError = ""
            if (settings.syncLastAt == null) {
                // A device whose first delivery is this one has never left stale rows on the server, so it has
                // nothing to migrate: see pullChanges.
                update { it.copy(syncTwoWay = true) }
            }
            update { it.copy(syncLastAt = clock.now()) }
            saveSettings()
            // What another device changed meanwhile is read once ours is there.
            followUp { pullChanges() }; followUp { pullEvents() }
        }
    }

    // ---- pull ------------------------------------------------------------------------------------------------

    /**
     * Reads what changed on the server since the last read and folds it into the local playlists (`pullChanges`).
     * Never runs twice at once: a read asked for while one is on its way is run when that one ends. The cursor it
     * reads from belongs to one server and one account, so a read of another starts from the beginning.
     */
    suspend fun pullChanges() {
        val url: String
        val email: String
        val since: Long
        val firstRead: Boolean
        val migrate: Boolean
        val c: MeiroClient
        lock.withLock {
            if (token.isEmpty()) return
            if (changes.busy) { pullAgain = true; return }
            url = settings.syncUrl; email = settings.syncEmail
            since = if (settings.syncCursorUrl != url || settings.syncCursorEmail != email) 0 else settings.syncCursor
            // An install that synced before lists could be read back left rows on the server that are older than
            // its lists and that it may have changed or deleted since without the server hearing of it. Importing
            // them would bring back every list and track the user removed. So on its first read this device is the
            // truth: what the server holds that it lacks is deleted there rather than imported. A device that
            // never synced has nothing stale on the server and imports as usual. The question is settled when the
            // read starts, since a push finishing meanwhile would otherwise make a new install look old.
            firstRead = !settings.syncTwoWay
            migrate = firstRead && settings.syncLastAt != null
            c = client(url)
        }
        startTask(changes, null, { c.fetchAllChanges(since) }) { r ->
            val again = pullAgain
            pullAgain = false
            val ch = r.getOrNull()
            if (ch == null) {
                val err = r.exceptionOrNull()!!
                SyncLog.log("sync: reading the changes: $err")
                endSyncSession(err)
                return@startTask
            }
            val (_, queued) = mergeChanges(ch, migrate)
            update { it.copy(syncCursor = ch.next, syncCursorUrl = url, syncCursorEmail = email) }
            if (firstRead) update { it.copy(syncTwoWay = true) }
            saveSettings()
            if (queued) followUp { syncNow() }
            if (again) followUp { pullChanges() }
        }
    }

    /**
     * Reads the plays the server holds that it has not given this device yet, those of every device of the account,
     * and adds them to the listening history (`pullEvents`). Its cursor belongs to one server and one account too.
     */
    suspend fun pullEvents() {
        val url: String
        val email: String
        val since: Long
        val applied: Instant?
        val c: MeiroClient
        lock.withLock {
            if (token.isEmpty()) return
            if (events.busy) { pullEventsAgain = true; return }
            url = settings.syncUrl; email = settings.syncEmail
            if (settings.eventCursorUrl != url || settings.eventCursorEmail != email) { since = 0; applied = null }
            else { since = settings.eventCursor; applied = settings.historyClearedAt }
            c = client(url)
        }
        startTask(events, null, { c.fetchAllEvents(since) }) { r ->
            val again = pullEventsAgain
            pullEventsAgain = false
            val plays = r.getOrNull()
            if (plays == null) {
                val err = r.exceptionOrNull()!!
                SyncLog.log("sync: reading the plays: $err")
                endSyncSession(err)
                return@startTask
            }
            var changed = mergeEvents(plays.events)
            // A clearing newer than the last this device applied, made on any device, takes away the plays up to it.
            // It comes after the merge, since the answer may hold plays from before the clearing.
            update { it.copy(historyClearedAt = applied) }
            val cleared = plays.clearedAt
            if (cleared != null && cleared.isAfter(applied ?: Rfc3339Nano.ZERO)) {
                changed = dropHistoryThrough(cleared) || changed
                update { it.copy(historyClearedAt = cleared) }
            }
            update { it.copy(eventCursor = plays.next, eventCursorUrl = url, eventCursorEmail = email) }
            saveSettings()
            if (again) followUp { pullEvents() }
        }
    }

    /**
     * Adds the plays the server reported to the history and says whether any was new (`mergeEvents`). A play whose
     * event id the history holds is this device's own, come back, or one read twice. None is queued for the server.
     */
    private fun mergeEvents(events: List<PlayedEvent>): Boolean {
        var incoming = ArrayList<HistoryEntry>(events.size)
        for (e in events) {
            if (e.clientEventId.isEmpty() || e.track.providerTrackId.isEmpty()) continue
            incoming.add(
                HistoryEntry(
                    eventId = e.clientEventId, device = e.device, source = e.track.provider, videoId = e.track.providerTrackId,
                    title = e.track.title, artist = e.track.artist, playedMs = e.playedMs, durationMs = e.durationMs,
                    completed = e.completed, thumbnail = e.track.thumbnail, playedAt = e.playedAt,
                ),
            )
        }
        // While a clearing waits to be sent, the server still holds the plays it will forget: not brought back.
        val pending = settings.historyClearPending
        var list: List<HistoryEntry> = incoming
        if (pending != null) list = History.dropThrough(list, pending).first
        val (merged, added) = History.merge(history, list)
        history = merged
        if (added == 0) return false
        saveHistory()
        return true
    }

    /** Removes the plays at or before [at]: the history was cleared then, on some device. Says whether any went. */
    private fun dropHistoryThrough(at: Instant): Boolean {
        val (rest, n) = History.dropThrough(history, at)
        history = rest
        if (n == 0) return false
        saveHistory()
        return true
    }

    private fun localList(serverId: String): LocalLibrary? =
        libraries.list().firstOrNull { libraryServerId(it.id, it.serverId) == serverId }

    /**
     * Makes the shared Daily mix list this device's, when it is here: the list the server holds under the fixed
     * identity of the mix is the one mix of the account, whichever device made it. If its tracks were put there
     * today, today's mix is made, and this device does not make a second. Says whether it changed anything.
     */
    private fun adoptDailyMix(): Boolean {
        val lib = localList(Ids.dailyMixId()) ?: return false
        var changed = false
        if (settings.dailyMixId != lib.id) { update { it.copy(dailyMixId = lib.id) }; changed = true }
        if (settings.dailyMixDate != today() && madeToday(lib)) { update { it.copy(dailyMixDate = today()) }; changed = true }
        return changed
    }

    /** Whether today's Daily mix is made here or by another device (the settings' date is today's local date). */
    fun dailyMixMadeToday(): Boolean = lock.withLock { settings.dailyMixDate == today() }

    /**
     * `writeDailyMix` (recommendations.go:423): puts [items] in the Daily mix, once a day. The mix has one identity on
     * the server whichever device makes it, so the first device to make today's fills it: a device that finds the list
     * already holds tracks put there today adopts that mix and makes none. The list is made the first time (under the
     * shared id, named "Daily mix", no cover) and its tracks replaced after that; a mix kept under another id is
     * deleted. Needs at least [MIN_DAILY_MIX] items. Says whether it wrote the mix; the caller sends it.
     */
    fun writeDailyMix(items: List<com.muratkirazkaya.meiro.data.Item>, remake: Boolean = false): Boolean = lock.withLock {
        if (items.size < MIN_DAILY_MIX || (!remake && settings.dailyMixDate == today())) return false
        if (!remake && adoptDailyMix()) {
            saveSettings()
            if (settings.dailyMixDate == today()) return false
        }
        val shared = Ids.dailyMixId()
        val lib = localList(shared) ?: run {
            // A mix made before the lists were shared is a list of its own on the server: take it away, or the other
            // devices would show it beside the shared one.
            val old = settings.dailyMixId
            if (old.isNotEmpty() && libraries.get(old) != null) libraries.delete(old)
            libraries.addRemote(shared, "Daily mix", "", clock.now())
        }
        libraries.replaceItems(lib.id, items)
        update { it.copy(dailyMixId = lib.id, dailyMixDate = today()) }
        saveSettings()
        SyncLog.log("daily mix: wrote ${items.size} tracks to ${lib.id} for ${today()}")
        true
    }

    private fun madeToday(lib: LocalLibrary): Boolean = lib.items.any { it.updatedAt.atZone(zone).toLocalDate().toString() == today() }

    /**
     * Folds what the server reported into the local playlists, the later record of each list and each track winning
     * (`mergeChanges`). Says whether anything here changed and whether it queued something for the server. Lists come
     * first, so the tracks that follow have a list to go in. During the first read of a device that synced before
     * ([migrate]) it queues deletions for the lists and tracks the server has that this device lacks, and changes
     * nothing here.
     */
    internal fun mergeChanges(changes: Changes, migrate: Boolean): Pair<Boolean, Boolean> {
        val now = clock.now()
        val deleted = libraries.deleted()
        val staleLists = mutableListOf<Library>()
        var changed = false
        var queued = false
        for (remote in changes.libraries) {
            if (remote.kind != "custom") continue
            val local = localList(remote.id)
            if (migrate) {
                if (local == null && !remote.deleted) {
                    staleLists.add(Library(id = remote.id, kind = "custom", name = remote.name, updatedAt = now, deleted = true))
                }
                continue
            }
            when {
                local == null && !remote.deleted -> {
                    // A list deleted here after the server's record was made stays deleted: the push tells the server.
                    if (deleted.any { libraryServerId(it.id, it.serverId) == remote.id && it.deletedAt.isAfter(remote.updatedAt) }) continue
                    try { libraries.addRemote(remote.id, remote.name, remote.cover, remote.updatedAt) } catch (e: Exception) {
                        SyncLog.log("sync: keeping a list from the server: $e"); continue
                    }
                    changed = true
                }
                local != null && remote.deleted -> {
                    if (remote.updatedAt.isBefore(local.updatedAt)) continue
                    try { libraries.dropRemote(local.id) } catch (e: Exception) {
                        SyncLog.log("sync: dropping a list the server deleted: $e"); continue
                    }
                    changed = true
                }
                local != null && remote.updatedAt.isAfter(local.updatedAt) -> {
                    try { libraries.applyRemoteList(local.id, remote.name, remote.cover, remote.updatedAt) } catch (e: Exception) {
                        SyncLog.log("sync: taking a list's changes from the server: $e"); continue
                    }
                    changed = true
                }
            }
        }

        val order = mutableListOf<String>()
        val byList = HashMap<String, MutableList<ChangedItem>>()
        for (remote in changes.items) {
            if (remote.libraryId !in byList) order.add(remote.libraryId)
            byList.getOrPut(remote.libraryId) { mutableListOf() }.add(remote)
        }
        val staleItems = LinkedHashMap<String, MutableList<LibraryItem>>()
        for (serverId in order) {
            // A list the server holds that was not taken in is another kind of list, or one this device keeps
            // deleted. A list queued for deletion takes its tracks with it.
            val local = localList(serverId) ?: continue
            val applied = mutableListOf<RemoteItem>()
            for (remote in byList.getValue(serverId)) {
                val item = remoteItem(remote) ?: continue
                if (!migrate) {
                    applied.add(RemoteItem(item, remote.position, remote.deleted))
                    continue
                }
                val (source, id) = item.activeRef()
                if (remote.deleted || local.items.any { it.activeRef() == (source to id) }) continue
                staleItems.getOrPut(serverId) { mutableListOf() }.add(LibraryItem(remote.track, remote.position, "", now, true))
            }
            if (applied.isEmpty()) continue
            val n = try { libraries.applyRemoteItems(local.id, applied) } catch (e: Exception) {
                SyncLog.log("sync: taking a list's tracks from the server: $e"); continue
            }
            changed = changed || n > 0
        }

        if (adoptDailyMix()) changed = true
        if (staleLists.isNotEmpty()) { outbox.addLibraries(staleLists); queued = true }
        for ((serverId, items) in staleItems) { outbox.addLibraryItems(serverId, items); queued = true }
        return changed to queued
    }

    /**
     * Builds the list item a server's track is (`remoteItem`): the source is its provider, the key what the
     * provider names the track by. A provider this app cannot play is not built.
     */
    private fun remoteItem(change: ChangedItem): Item? {
        val track = change.track
        if (track.provider != Source.YTM && track.provider != Source.SOUNDCLOUD) return null
        val item = Item(
            source = track.provider, key = track.providerTrackId, title = track.title, artist = track.artist,
            durationMs = track.durationMs, state = LinkState.UNKNOWN, thumbnail = track.thumbnail,
            albumThumbnail = track.albumThumbnail, why = change.note, addedAt = change.updatedAt, updatedAt = change.updatedAt,
            url = if (track.provider == Source.SOUNDCLOUD) track.providerTrackId else "",
        )
        return if (item.key.isNotEmpty()) item else null
    }

    // ---- links ------------------------------------------------------------------------------------------------

    /** Links are kept in step with the server: an account is signed in and sending is on. */
    private fun linksSyncing() = settings.syncEmail.isNotEmpty() && settings.syncEnabled

    /** Files changed links to send, and sends them at once when it can (`queueLinks`). */
    fun queueLinks(l: Links) {
        lock.withLock {
            if (!linksSyncing() || l.isEmpty()) return
            outbox.addLinks(l)
        }
        followUp { syncNow() }
    }

    /** Files every link the store holds, so a device that signs in sends what it found before it had an account. */
    fun queueAllLinks() {
        val store = links ?: return
        queueLinks(LinksStore.linksOut(store.data()))
    }

    /**
     * Reads the links the server holds and folds them into the store, the later record of each winning, then sends
     * what only this device has (`pullLinks`).
     */
    suspend fun pullLinks() {
        val store = links ?: return
        val c: MeiroClient
        lock.withLock {
            if (token.isEmpty() || pull.busy) return
            c = client(settings.syncUrl)
        }
        startTask(pull, SYNC_CALL_TIMEOUT_MS, { c.fetchLinks() }) { r ->
            val l = r.getOrNull()
            if (l == null) {
                val err = r.exceptionOrNull()!!
                SyncLog.log("sync: reading the links: $err")
                endSyncSession(err)
                return@startTask
            }
            val took = try { store.merge(LinksStore.linksIn(l)) } catch (e: Exception) { SyncLog.log("sync: keeping the links: $e"); 0 }
            if (took > 0) mirrorStoreToLists()
            queueAllLinks()
        }
    }

    /** Brings the playlists' own copy of each link up to what the store says, after links came from the server. */
    private fun mirrorStoreToLists() {
        val store = links ?: return
        for (lib in libraries.list()) {
            for (item in lib.items) {
                val provider = item.source.ifEmpty { Source.YTM }
                val song = store.song(provider, item.key) ?: continue
                if (song.state == item.state && song.mbid == item.mbid) continue
                libraries.setLink(lib.id, item.key, song.state, song.mbid)
            }
        }
    }

    // ---- plays and history ----------------------------------------------------------------------------------

    /** Files a finished play with the sync, which sends it when the server is reachable (`queueEvent`). */
    fun queueEvent(e: HistoryEntry) {
        if (e.eventId.isEmpty()) return // recorded before plays were synced
        val track = trackFor(e.source, e.videoId, "", e.title, e.artist, e.durationMs).copy(thumbnail = e.thumbnail)
        outbox.addEvent(Event(e.eventId, track, e.playedMs, e.durationMs, e.completed, e.device, e.playedAt))
    }

    /** Appends a finished play to the history (cap 5000) and queues it: what the desktop does when a play ends. */
    fun recordPlay(e: HistoryEntry) {
        lock.withLock {
            history = History.append(history, e)
            saveHistory()
        }
        queueEvent(e)
    }

    /**
     * A play starts (`recordHistory`): it is in the history, with no time heard yet, from the moment audio starts.
     * The event goes to the server only when the play ends ([finishPlay]).
     */
    fun beginPlay(e: HistoryEntry) {
        if (e.eventId.isEmpty()) return
        lock.withLock {
            history = History.append(history, e)
            saveHistory()
        }
    }

    /**
     * A play ends (`finishPlayRecord`): the history entry gets how long it was heard and whether it reached the end
     * (`completed`), and the play is filed with the sync. Returns the finished entry, or null if the history no
     * longer holds it (it was cleared meanwhile).
     */
    fun finishPlay(eventId: String, playedMs: Long, completed: Boolean): HistoryEntry? {
        val entry = lock.withLock {
            val i = history.indexOfLast { it.eventId == eventId }
            if (i < 0) return null
            val done = history[i].copy(playedMs = playedMs, completed = completed)
            history = history.toMutableList().also { it[i] = done }
            saveHistory()
            done
        }
        queueEvent(entry)
        return entry
    }

    /**
     * Forgets every play, on disk too. When an account is signed in it is the account's history that is cleared, on
     * every device: the plays that wait to be sent are let go, and the server is told, now or, when it cannot be
     * reached, with the next sync (`clearHistory`).
     */
    fun clearHistory() {
        val clearedAt = clock.now()
        var signedIn = false
        lock.withLock {
            history = emptyList()
            outbox.dropEventsThrough(clearedAt)
            if (settings.syncEmail.isNotEmpty()) {
                update { it.copy(historyClearPending = clearedAt) }
                saveSettings()
                signedIn = true
            }
            saveHistory()
        }
        if (signedIn) followUp { syncNow() }
    }

    companion object {
        /** Bounds one request to the server. */
        const val SYNC_CALL_TIMEOUT_MS = 30_000L
        /** How many times a delivery that fails for a reason that may pass is tried, with backoff between tries. */
        const val SYNC_ATTEMPTS = 4
        /** The fewest picks that make a Daily mix worth showing (`minDailyMix`, recommendations.go:62). */
        const val MIN_DAILY_MIX = 5
        /** How often records that wait are tried again while the app runs (the WorkManager period later). */
        val SYNC_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
