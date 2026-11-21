package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.Clock
import com.muratkirazkaya.meiro.data.Persistence
import com.muratkirazkaya.meiro.data.Rfc3339Nano
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * A batch the server turned down as malformed (400 or 422), taken out of the queue so the batches behind it can go,
 * and kept with why and when instead of dropped without a trace.
 */
@Serializable
data class Refused(
    /** What the batch held: "events", "libraries", "library items", "downloads" or "links". */
    val kind: String,
    val status: Int,
    val error: String,
    val count: Int,
    /** The batch as it was sent (JSON text). */
    val batch: String = "",
    @Serializable(with = InstantSerializer::class) val at: Instant = Rfc3339Nano.ZERO,
)

@Serializable
data class OutboxFile(
    val events: List<Event> = emptyList(),
    val libraries: List<Library> = emptyList(),
    val libraryItems: List<LibraryItems> = emptyList(),
    val downloads: List<Download> = emptyList(),
    val links: Links = Links(),
    val refused: List<Refused> = emptyList(),
)

/**
 * Port of internal/sync/outbox.go. Records wait here until a push is answered 200, and survive a restart (they are
 * handed to [Persistence] after every change). A record stays until a push takes it, so a record is never lost to
 * a network that is down. Thread safe.
 */
class Outbox(
    private val persistence: Persistence? = null,
    private val clock: Clock = Clock.System,
) {
    private val lock = Any()
    private var file: OutboxFile = persistence?.loadOutboxJson()?.let {
        try { SyncJson.decodeFromString(OutboxFile.serializer(), it) } catch (_: Exception) { OutboxFile() } // unreadable: start empty
    } ?: OutboxFile()

    private fun changed() {
        persistence?.saveOutboxJson(SyncJson.encodeToString(OutboxFile.serializer(), file))
    }

    /** Keeps one play to send; same id replaces; the oldest go once [MAX_EVENTS] is passed. */
    fun addEvent(e: Event) = synchronized(lock) {
        val events = file.events.toMutableList()
        val i = events.indexOfFirst { it.clientEventId == e.clientEventId }
        if (i >= 0) events[i] = e else events.add(e)
        val over = events.size - MAX_EVENTS
        file = file.copy(events = if (over > 0) events.drop(over) else events)
        changed()
    }

    /** Forgets the waiting plays at or before [at], as the history they belong to was cleared; returns how many. */
    fun dropEventsThrough(at: Instant): Int = synchronized(lock) {
        val before = file.events.size
        val kept = file.events.filter { it.playedAt.isAfter(at) }
        val n = before - kept.size
        if (n > 0) { file = file.copy(events = kept); changed() }
        n
    }

    /**
     * Keeps libraries to send, keyed by id. A list already waiting is replaced by the one given unless the waiting
     * one is the later record, and one that waits for another reason stays.
     */
    fun addLibraries(libraries: List<Library>) = synchronized(lock) {
        val libs = file.libraries.toMutableList()
        for (lib in libraries) {
            val i = libs.indexOfFirst { it.id == lib.id }
            when {
                i < 0 -> libs.add(lib)
                !lib.updatedAt.isBefore(libs[i].updatedAt) -> libs[i] = lib
            }
        }
        file = file.copy(libraries = libs)
        changed()
    }

    /** Keeps items of one library to send, keyed by their track, the later record of a track waiting. */
    fun addLibraryItems(libraryId: String, items: List<LibraryItem>) = synchronized(lock) {
        val groups = file.libraryItems.toMutableList()
        var gi = groups.indexOfFirst { it.libraryId == libraryId }
        if (gi < 0) { groups.add(LibraryItems(libraryId, emptyList())); gi = groups.size - 1 }
        val list = groups[gi].items.toMutableList()
        for (item in items) {
            val j = list.indexOfFirst { it.track.provider == item.track.provider && it.track.providerTrackId == item.track.providerTrackId }
            when {
                j < 0 -> list.add(item)
                !item.updatedAt.isBefore(list[j].updatedAt) -> list[j] = item
            }
        }
        groups[gi] = LibraryItems(libraryId, list)
        file = file.copy(libraryItems = groups)
        changed()
    }

    /** Replaces the pending downloads, keyed by track and device. */
    fun setDownloads(downloads: List<Download>) = synchronized(lock) {
        file = file.copy(downloads = downloads.toList())
        changed()
    }

    fun eventCount(): Int = synchronized(lock) { file.events.size }

    /** How many records of every kind wait. */
    fun pending(): Int = synchronized(lock) {
        file.events.size + file.libraries.size + file.downloads.size + file.links.size + file.libraryItems.sumOf { it.items.size }
    }

    internal fun snapshot(): OutboxFile = synchronized(lock) { file }

    fun refused(): List<Refused> = synchronized(lock) { file.refused }

    // ---- links ---------------------------------------------------------------------------------------------

    /** Keeps links to send, replacing a pending link of the same name with the newer one. */
    fun addLinks(links: Links) = synchronized(lock) {
        val songs = file.links.songs.toMutableList()
        for (song in links.songs) {
            val i = songs.indexOfFirst { it.provider == song.provider && it.providerTrackId == song.providerTrackId }
            when {
                i < 0 -> songs.add(song)
                !song.updatedAt.isBefore(songs[i].updatedAt) -> songs[i] = song
            }
        }
        val artists = file.links.artists.toMutableList()
        for (artist in links.artists) {
            val i = artists.indexOfFirst { it.artistKey == artist.artistKey }
            when {
                i < 0 -> artists.add(artist)
                !artist.updatedAt.isBefore(artists[i].updatedAt) -> artists[i] = artist
            }
        }
        val alts = file.links.alternates.toMutableList()
        for (alt in links.alternates) {
            val i = alts.indexOfFirst { it.provider == alt.provider && it.providerTrackId == alt.providerTrackId }
            when {
                i < 0 -> alts.add(alt)
                !alt.updatedAt.isBefore(alts[i].updatedAt) -> alts[i] = alt
            }
        }
        file = file.copy(links = Links(songs, artists, alts))
        changed()
    }

    /** Takes sent links out of the queue, but not one changed again after it was sent. */
    private fun removeLinks(sent: Links) = synchronized(lock) {
        val l = file.links
        file = file.copy(
            links = Links(
                songs = l.songs.filterNot { x -> sent.songs.any { s -> s.provider == x.provider && s.providerTrackId == x.providerTrackId && !x.updatedAt.isAfter(s.updatedAt) } },
                artists = l.artists.filterNot { x -> sent.artists.any { s -> s.artistKey == x.artistKey && !x.updatedAt.isAfter(s.updatedAt) } },
                alternates = l.alternates.filterNot { x -> sent.alternates.any { s -> s.provider == x.provider && s.providerTrackId == x.providerTrackId && !x.updatedAt.isAfter(s.updatedAt) } },
            ),
        )
        changed()
    }

    // ---- removal after a push --------------------------------------------------------------------------------

    private fun removeEvents(ids: Collection<String>) = synchronized(lock) {
        file = file.copy(events = file.events.filterNot { it.clientEventId in ids }); changed()
    }

    private fun removeLibraries(ids: Collection<String>) = synchronized(lock) {
        file = file.copy(libraries = file.libraries.filterNot { it.id in ids }); changed()
    }

    private fun removeLibraryItems(libraryId: String, sent: List<LibraryItem>) = synchronized(lock) {
        val groups = file.libraryItems.toMutableList()
        val gi = groups.indexOfFirst { it.libraryId == libraryId }
        if (gi >= 0) {
            groups[gi] = groups[gi].copy(items = groups[gi].items.filterNot { item ->
                sent.any { s -> item.track.provider == s.track.provider && item.track.providerTrackId == s.track.providerTrackId }
            })
        }
        file = file.copy(libraryItems = groups); changed()
    }

    /** Forgets every waiting track of a library. */
    private fun dropLibraryItems(libraryId: String) = synchronized(lock) {
        file = file.copy(libraryItems = file.libraryItems.filterNot { it.libraryId == libraryId }); changed()
    }

    private fun removeDownloads(sent: List<Download>) = synchronized(lock) {
        file = file.copy(downloads = file.downloads.filterNot { d ->
            sent.any { s -> d.device == s.device && d.track.provider == s.track.provider && d.track.providerTrackId == s.track.providerTrackId }
        }); changed()
    }

    /**
     * Keeps a batch the server turned down, with its answer and the time. The same batch refused again replaces its
     * earlier entry; the oldest entries go once [MAX_REFUSED] is passed.
     */
    private fun refuse(kind: String, count: Int, batch: String, api: ApiException) = synchronized(lock) {
        val reason = api.serverMessage.ifEmpty { api.message ?: "" }
        SyncLog.log("sync: the server refused $count $kind (${api.status}), set aside: $reason")
        val list = file.refused.filterNot { it.kind == kind && it.batch == batch }.toMutableList()
        list.add(Refused(kind, api.status, reason, count, batch, clock.now()))
        val over = list.size - MAX_REFUSED
        file = file.copy(refused = if (over > 0) list.drop(over) else list)
        changed()
    }

    /**
     * Sends everything that waits, in batches, oldest first (`Push`). A 200 takes the batch out of the queue. A batch
     * refused as malformed (400 or 422) would be refused every time and hold up all that follows, so it moves to the
     * refused list and the push goes on. Any other failure, a 401 included, stops the push and everything stays.
     * Returns how many records were taken this time and the failure that stopped the push, if any.
     */
    /** Result of [push]: how many records the server took, and the failure that stopped the push (if any). */
    data class PushResult(val sent: Int, val error: Throwable?)

    suspend fun push(c: MeiroClient): PushResult {
        val snap = snapshot()
        var sent = 0
        try {
            for (batch in chunks(snap.libraries, MAX_BATCH)) {
                try {
                    c.pushLibraries(batch)
                } catch (e: ApiException) {
                    if (isRefusedStatus(e)) { refuse("libraries", batch.size, SyncJson.encodeToString(libsSer, batch), e); removeLibraries(batch.map { it.id }); continue }
                    throw e
                }
                removeLibraries(batch.map { it.id })
                sent += batch.size
            }

            for (group in snap.libraryItems) {
                for (batch in chunks(group.items, MAX_BATCH)) {
                    val payload = LibraryItems(group.libraryId, batch)
                    try {
                        c.pushLibraryItems(payload)
                    } catch (e: ApiException) {
                        if (e.status == 404) {
                            // The server does not know the library, or has it deleted, as another device may have.
                            // It will never take these items, so they are let go; the next read of the changes
                            // brings the deletion.
                            SyncLog.log("sync: the server has no library ${group.libraryId}, dropping its ${group.items.size} waiting tracks")
                            dropLibraryItems(group.libraryId)
                            break
                        }
                        if (isRefusedStatus(e)) {
                            refuse("library items", batch.size, SyncJson.encodeToString(LibraryItems.serializer(), payload), e)
                            removeLibraryItems(group.libraryId, batch)
                            continue
                        }
                        throw e
                    }
                    removeLibraryItems(group.libraryId, batch)
                    sent += batch.size
                }
            }

            for (batch in chunks(snap.downloads, MAX_BATCH)) {
                try {
                    c.pushDownloads(batch)
                } catch (e: ApiException) {
                    if (isRefusedStatus(e)) { refuse("downloads", batch.size, SyncJson.encodeToString(dlSer, batch), e); removeDownloads(batch); continue }
                    throw e
                }
                removeDownloads(batch)
                sent += batch.size
            }

            for (batch in linkBatches(snap.links)) {
                try {
                    c.pushLinks(batch)
                } catch (e: ApiException) {
                    if (isRefusedStatus(e)) { refuse("links", batch.size, SyncJson.encodeToString(Links.serializer(), batch), e); removeLinks(batch); continue }
                    throw e
                }
                removeLinks(batch)
                sent += batch.size
            }

            for (batch in chunks(snap.events, MAX_BATCH)) {
                val (accepted, duplicates) = try {
                    c.pushEvents(batch)
                } catch (e: ApiException) {
                    if (isRefusedStatus(e)) { refuse("events", batch.size, SyncJson.encodeToString(evSer, batch), e); removeEvents(batch.map { it.clientEventId }); continue }
                    throw e
                }
                if (accepted.size + duplicates.size == 0) {
                    // The server took the request but named nothing: stop rather than loop on the same batch.
                    return PushResult(sent, java.io.IOException("sync: sunucu kabul edilen olay dondurmedi"))
                }
                removeEvents(accepted + duplicates)
                sent += batch.size
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return PushResult(sent, e)
        }
        return PushResult(sent, null)
    }

    companion object {
        /** How many records of one kind go in one request; well inside the server's limit of 1000 events. */
        const val MAX_BATCH = 200
        /** Caps the plays kept while the server is out of reach; the oldest go first. */
        const val MAX_EVENTS = 5000
        /** Caps the refused batches kept for a look. */
        const val MAX_REFUSED = 50

        private val libsSer = kotlinx.serialization.builtins.ListSerializer(Library.serializer())
        private val dlSer = kotlinx.serialization.builtins.ListSerializer(Download.serializer())
        private val evSer = kotlinx.serialization.builtins.ListSerializer(Event.serializer())

        /** A 400 or a 422: the server turned down the batch itself, not the sender. */
        private fun isRefusedStatus(e: ApiException) = e.status == 400 || e.status == 422

        fun <T> chunks(items: List<T>, size: Int): List<List<T>> = items.chunked(size)

        /** Splits links into requests of at most [MAX_BATCH] of each kind. */
        fun linkBatches(l: Links): List<Links> =
            l.songs.chunked(MAX_BATCH).map { Links(songs = it) } +
                l.artists.chunked(MAX_BATCH).map { Links(artists = it) } +
                l.alternates.chunked(MAX_BATCH).map { Links(alternates = it) }

        /** How long to wait before the attempt after [attempt] failures: 2 s doubling, capped at 5 minutes. */
        fun backoff(attempt: Int): Duration {
            val a = if (attempt < 1) 1 else attempt
            val base = 2_000L
            val cap = 5 * 60 * 1000L
            val d = base shl minOf(a - 1, 20)
            return Duration.ofMillis(if (d > cap || d <= 0) cap else d)
        }
    }
}
