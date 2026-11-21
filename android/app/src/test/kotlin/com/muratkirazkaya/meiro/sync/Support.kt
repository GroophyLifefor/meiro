package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.Clock
import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.MemoryPersistence
import com.muratkirazkaya.meiro.data.Persistence
import com.muratkirazkaya.meiro.data.Source
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.time.Instant
import java.time.ZoneId

/**
 * A Meiro server that keeps what it is sent, as the real one does (port of the desktop tests' `serverState`): per
 * record the later updated_at wins, a deletion is a tombstone an older live push cannot undo, a push that changes
 * nothing does not move the order of changes, and a read of the changes gives everything after a point.
 */
class FakeMeiroServer : Dispatcher() {
    class Rec<T>(val value: T, val seq: Long)

    val web = MockWebServer()
    val url: String get() = web.url("/").toString().trimEnd('/')

    var seq = 0L
    val libs = LinkedHashMap<String, Rec<Library>>()
    val items = LinkedHashMap<String, Rec<ChangedItem>>()
    /** How many records one read of the changes (or plays) gives, to make a read need several. */
    var page = 2
    val asked = mutableListOf<Long>()
    val plays = mutableListOf<PlayedEvent>()
    val askedPlays = mutableListOf<Long>()
    var clearedAt: Instant? = null
    var clears = 0
    var playSeq = 0L
    var offline = false
    val downloads = mutableListOf<Download>()
    val pushedLinks = mutableListOf<Links>()
    var serverLinks = Links()
    /** When set, every call but a sign in is answered 401 (the token ran out). */
    var ended = false
    var requests = 0
    /** Overrides the status of a request by path and body; 0 lets it through. */
    var refuse: (String, String) -> Int = { _, _ -> 0 }
    /** The token a sign in is given. */
    var loginToken = "TOKEN"
    /** The real server answers 404 to items of a list it does not hold or has deleted; the simple fakes of the desktop's sync package do not. */
    var strictItems = true

    fun start(): FakeMeiroServer { web.dispatcher = this; web.start(); return this }
    fun stop() { web.shutdown() }

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path!!.substringBefore('?')
        val query = request.requestUrl!!
        val body = request.body.readUtf8()
        fun json(s: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(s)
        if (path.endsWith("/auth/login")) return json("""{"token":"$loginToken"}""")
        requests++
        if (ended) return json("""{"error":"token expired"}""", 401)
        if (offline) return json("""{"error":"down"}""", 503)
        val forced = refuse(path, body)
        if (forced != 0) return json("""{"error":"boom"}""", forced)
        val m = request.method
        return when {
            path.endsWith("/sync/libraries") -> {
                val req = SyncJson.decodeFromString(LibrariesBody.serializer(), body)
                for (lib in req.libraries) {
                    val held = libs[lib.id]
                    if (held != null && lib.updatedAt.isBefore(held.value.updatedAt)) continue
                    if (held != null && held.value.name == lib.name && held.value.cover == lib.cover && held.value.deleted == lib.deleted && held.value.updatedAt == lib.updatedAt) continue
                    seq++
                    libs[lib.id] = Rec(lib, seq)
                }
                json("""{"accepted":[]}""")
            }
            path.endsWith("/sync/library_items") -> {
                val req = SyncJson.decodeFromString(LibraryItems.serializer(), body)
                val lib = libs[req.libraryId]
                if (strictItems && (lib == null || lib.value.deleted)) return json("""{"error":"library not found"}""", 404)
                for (item in req.items) {
                    val key = "${req.libraryId}|${item.track.provider}|${item.track.providerTrackId}"
                    val held = items[key]
                    if (held != null && item.updatedAt.isBefore(held.value.updatedAt)) continue
                    if (held != null && held.value.position == item.position && held.value.deleted == item.deleted && held.value.updatedAt == item.updatedAt && held.value.track == item.track && held.value.note == item.note) continue
                    seq++
                    items[key] = Rec(ChangedItem(req.libraryId, item.track, item.position, item.note, item.updatedAt, item.deleted, seq), seq)
                }
                json("""{"accepted":[]}""")
            }
            path.endsWith("/sync/downloads") -> {
                downloads += SyncJson.decodeFromString(DownloadsBody.serializer(), body).downloads
                json("""{"accepted":[]}""")
            }
            path.endsWith("/sync/changes") -> {
                val since = query.queryParameter("since")!!.toLong()
                asked += since
                json(SyncJson.encodeToString(Changes.serializer(), changesAfter(since)))
            }
            path.endsWith("/sync/links") -> {
                val l = SyncJson.decodeFromString(Links.serializer(), body)
                pushedLinks += l
                json("""{"accepted":${l.size}}""")
            }
            path.endsWith("/api/v1/links") -> json(SyncJson.encodeToString(Links.serializer(), serverLinks))
            path.endsWith("/sync/events") -> {
                val req = SyncJson.decodeFromString(EventsBody.serializer(), body)
                val accepted = mutableListOf<String>(); val dups = mutableListOf<String>()
                for (e in req.events) {
                    val c = clearedAt
                    if (c != null && !e.playedAt.isAfter(c)) { accepted += e.clientEventId; continue } // taken, not kept
                    if (plays.any { it.clientEventId == e.clientEventId }) { dups += e.clientEventId; continue }
                    plays += PlayedEvent(e.clientEventId, e.track, e.playedMs, e.durationMs, e.completed, e.device, e.playedAt, nextPlaySeq())
                    accepted += e.clientEventId
                }
                json("""{"accepted":${SyncJson.encodeToString(strList, accepted)},"duplicates":${SyncJson.encodeToString(strList, dups)}}""")
            }
            path.endsWith("/api/v1/events") && m == "DELETE" -> {
                val now = Instant.now()
                plays.clear(); clearedAt = now; clears++
                json("""{"cleared_at":"${com.muratkirazkaya.meiro.data.Rfc3339Nano.format(now)}"}""")
            }
            path.endsWith("/api/v1/events") -> {
                val since = query.queryParameter("since")!!.toLong()
                askedPlays += since
                var evs = plays.filter { it.seq > since }
                var more = false
                if (evs.size > page) { evs = evs.take(page); more = true }
                val next = if (evs.isNotEmpty()) evs.last().seq else since
                json(SyncJson.encodeToString(Plays.serializer(), Plays(evs, next, more, clearedAt)))
            }
            else -> json("""{"accepted":[],"duplicates":[]}""")
        }
    }

    @kotlinx.serialization.Serializable private data class LibrariesBody(val libraries: List<Library> = emptyList())
    @kotlinx.serialization.Serializable private data class DownloadsBody(val downloads: List<Download> = emptyList())
    @kotlinx.serialization.Serializable private data class EventsBody(val events: List<Event> = emptyList())
    private val strList = ListSerializer(String.serializer())

    private fun nextPlaySeq(): Long { playSeq = maxOf(playSeq, plays.size.toLong()); return ++playSeq }

    /** The first page of the records changed after [since], in order. */
    private fun changesAfter(since: Long): Changes {
        class E(val seq: Long, val lib: Library?, val item: ChangedItem?)
        var all = (libs.values.filter { it.seq > since }.map { E(it.seq, it.value, null) } +
            items.values.filter { it.seq > since }.map { E(it.seq, null, it.value) }).sortedBy { it.seq }
        var more = false
        if (all.size > page) { all = all.take(page); more = true }
        var next = since
        val ls = mutableListOf<ChangedLibrary>(); val its = mutableListOf<ChangedItem>()
        for (e in all) {
            next = e.seq
            if (e.lib != null) ls += ChangedLibrary(e.lib.id, e.lib.kind, e.lib.provider, e.lib.providerId, e.lib.name, e.lib.cover, e.lib.updatedAt, e.lib.deleted, e.seq)
            else its += e.item!!.copy(seq = e.seq)
        }
        return Changes(ls, its, next, more)
    }

    @Synchronized fun item(libraryId: String, provider: String, id: String): ChangedItem? = items["$libraryId|$provider|$id"]?.value
    @Synchronized fun library(id: String): Library? = libs[id]?.value
    @Synchronized fun playIds(): List<String> = plays.map { it.clientEventId }
    @Synchronized fun <R> locked(f: () -> R): R = f()
}

/** One device of an account: its own stores, outbox, engine and (in memory) database. */
class Device(
    server: FakeMeiroServer? = null,
    url: String = server?.url ?: "http://127.0.0.1:1",
    val persistence: Persistence = MemoryPersistence(),
    val tokens: TokenStore = InMemoryTokenStore(),
    val clock: Clock = Clock.strictlyIncreasing(),
    name: String = "laptop",
    signedIn: Boolean = true,
    withLinks: Boolean = false,
) {
    val libraries = LibraryStore(persistence, clock)
    val outbox = Outbox(persistence, clock)
    val links = if (withLinks) LinksStore(persistence, clock) else null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val engine = SyncEngine(
        libraries, outbox, tokens, persistence, links, clock, ZoneId.of("UTC"), name,
        scope = scope, sleep = { }, // backoff costs no time in tests
    )

    init { if (signedIn) engine.testSignIn(url) }

    /** Sends what the app has and reads what the server has, until both are done (the desktop tests' `syncBoth`). */
    fun syncBoth() = runBlocking {
        engine.syncNow(); engine.settled() // a push is followed by reads of what changed meanwhile
        engine.pullChanges(); engine.settled()
        engine.pullEvents(); engine.settled()
    }

    fun settled() = runBlocking { engine.settled() }

    fun addSong(listId: String, key: String) {
        val added = libraries.addItem(
            listId,
            Item(source = Source.YTM, key = key, title = "Song $key", artist = "Artist", durationMs = 180_000, thumbnail = "https://pic/$key", albumThumbnail = "https://album/$key"),
        )
        check(added) { "adding $key" }
    }

    /** A finished play of a track at a time, filed for syncing as a play that ended is (`playAt`). */
    fun playAt(id: String, at: Instant): HistoryEntry {
        val e = HistoryEntry(
            eventId = id, device = engine.deviceName, source = "ytm", videoId = "v-$id", title = "Song $id", artist = "Artist",
            playedMs = 200_000, durationMs = 200_000, completed = true, playedAt = at,
        )
        engine.recordPlay(e)
        return e
    }

    fun historyIds(): List<String> = engine.history().map { it.eventId }
    fun keys(listId: String): List<String> = libraries.get(listId)!!.items.map { it.key }
}

fun listKeys(lib: com.muratkirazkaya.meiro.data.Library): List<String> = lib.items.map { it.key }
