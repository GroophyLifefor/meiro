package com.muratkirazkaya.meiro.data

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

class LibraryException(message: String) : Exception(message)

/**
 * Port of internal/library/library.go: the user's own lists, with the UpdatedAt rules the sync depends on.
 *
 * Every change stamps the time it was made on the list and the tracks it touched, and a track or a list taken
 * away leaves a tombstone (kept 90 days). A list's UpdatedAt moves only on list-level changes (made, renamed,
 * cover): its tracks are records of their own. State is in memory and handed to [Persistence] after each change.
 * Thread safe: every method holds the store's lock.
 */
class LibraryStore(
    private val persistence: Persistence = MemoryPersistence(),
    private val clock: Clock = Clock.System,
) {
    private val lock = Any()
    private var libs: MutableList<Library> = mutableListOf()
    private var deleted: MutableList<DeletedList> = mutableListOf()

    init {
        val file = persistence.loadLibraries()
        libs = file.libraries.toMutableList()
        deleted = file.deleted.toMutableList()
        settle(clock.now())
    }

    /** Gives what an older file lacks the time it can be read as, and lets go of removals older than 90 days. */
    private fun settle(now: Instant) {
        for (i in libs.indices) {
            var lib = libs[i]
            val items = lib.items.map { if (it.updatedAt.isZero) it.copy(updatedAt = it.addedAt) else it }
            lib = lib.copy(
                items = items,
                updatedAt = if (lib.updatedAt.isZero) lib.createdAt else lib.updatedAt,
                removed = lib.removed.filterNot { Duration.between(it.removedAt, now) > TOMBSTONE_LIFE },
            )
            libs[i] = lib
        }
        deleted = deleted.filterNot { Duration.between(it.deletedAt, now) > TOMBSTONE_LIFE }.toMutableList()
    }

    private fun save() = persistence.saveLibraries(LibraryFile(libs.toList(), deleted.toList()))

    private fun index(id: String) = libs.indexOfFirst { it.id == id }

    private fun at(id: String): Int {
        val i = index(id)
        if (i < 0) throw LibraryException("library: no library with that ID")
        return i
    }

    /** The lists, oldest first. */
    fun list(): List<Library> = synchronized(lock) { libs.toList() }

    /** The lists taken away that the server has yet to be told of, oldest first. */
    fun deleted(): List<DeletedList> = synchronized(lock) { deleted.toList() }

    fun get(id: String): Library? = synchronized(lock) { libs.firstOrNull { it.id == id } }

    fun create(name: String): Library = synchronized(lock) {
        val n = name.trim()
        if (n.isEmpty()) throw LibraryException("library: a name is needed")
        val now = clock.now()
        val lib = Library(id = newLocalId(), name = n, createdAt = now, updatedAt = now)
        libs.add(lib)
        save()
        lib
    }

    /** Puts the given tracks in a list in place of what it held, for a list the app makes again each day. */
    fun replaceItems(id: String, items: List<Item>) = synchronized(lock) {
        val i = at(id)
        val now = clock.now()
        val kept = mutableListOf<Item>()
        for (raw in items) {
            var item = raw
            if (item.addedAt.isZero) item = item.copy(addedAt = now)
            item = item.copy(updatedAt = now)
            if (kept.any { sameItem(it, item) }) continue
            kept.add(item)
        }
        var lib = libs[i]
        var removed = lib.removed
        // The tracks the new list lacks are the ones removed; the ones it holds again, in whatever place, are told
        // to the server afresh, so a removal remembered for one of them is let go.
        for (old in lib.items) {
            if (kept.none { sameRef(it, old) }) removed = remember(removed, old.tombstone(now))
        }
        for (item in kept) removed = forget(removed, item)
        lib = lib.copy(items = kept, removed = removed)
        libs[i] = lib
        save()
    }

    fun setCover(id: String, cover: String) = synchronized(lock) {
        val i = at(id)
        libs[i] = libs[i].copy(cover = cover, updatedAt = clock.now())
        save()
    }

    fun rename(id: String, name: String) = synchronized(lock) {
        val n = name.trim()
        if (n.isEmpty()) throw LibraryException("library: a name is needed")
        val i = at(id)
        libs[i] = libs[i].copy(name = n, updatedAt = clock.now())
        save()
    }

    /** Takes a list away, and remembers it was there so the server can be told. */
    fun delete(id: String) = synchronized(lock) {
        val i = at(id)
        val lib = libs[i]
        deleted.add(DeletedList(id = lib.id, serverId = lib.serverId, name = lib.name, deletedAt = clock.now()))
        libs.removeAt(i)
        save()
    }

    /** Puts a track in a list unless it holds it already (same track, or same recording). True when added. */
    fun addItem(id: String, item: Item): Boolean = synchronized(lock) {
        val i = at(id)
        if (libs[i].items.any { sameItem(it, item) }) return false
        val now = clock.now()
        var it2 = item
        if (it2.addedAt.isZero) it2 = it2.copy(addedAt = now)
        it2 = it2.copy(updatedAt = now)
        val lib = libs[i]
        libs[i] = lib.copy(removed = forget(lib.removed, it2), items = lib.items + it2)
        save()
        true
    }

    /** Several tracks as [addItem] does one; writes once. Returns how many it added. */
    fun addItems(id: String, items: List<Item>): Int = synchronized(lock) {
        val i = at(id)
        var added = 0
        val now = clock.now()
        var lib = libs[i]
        for (raw in items) {
            if (lib.items.any { sameItem(it, raw) }) continue
            var item = raw
            if (item.addedAt.isZero) item = item.copy(addedAt = now)
            item = item.copy(updatedAt = now)
            lib = lib.copy(removed = forget(lib.removed, item), items = lib.items + item)
            added++
        }
        if (added == 0) return 0
        libs[i] = lib
        save()
        added
    }

    /** Takes the track with a key out of a list. */
    fun removeItem(id: String, key: String) = synchronized(lock) {
        val i = at(id)
        var lib = libs[i]
        val now = clock.now()
        var removed = lib.removed
        val items = lib.items.filter { item ->
            if (item.key != key) true else { removed = remember(removed, item.tombstone(now)); false }
        }
        if (items.size == lib.items.size) throw LibraryException("library: no such item")
        lib = lib.copy(items = items, removed = removed)
        libs[i] = lib
        save()
    }

    /**
     * Makes [source] the one a track in a list plays from. The server knows a track by the provider it plays from,
     * so a switch is a removal of the track as it was and an addition as it is. True if the list held the track.
     */
    fun setSource(id: String, key: String, source: String): Boolean = synchronized(lock) {
        val i = at(id)
        var lib = libs[i]
        val j = lib.items.indexOfFirst { it.key == key }
        if (j < 0) return false
        val now = clock.now()
        var item = lib.items[j]
        val was = item.tombstone(now)
        item = item.copy(source = source)
        var removed = lib.removed
        val (active, ref) = item.activeRef()
        if (active != was.source || ref != was.key) removed = remember(removed, was)
        removed = forget(removed, item)
        item = item.copy(updatedAt = now)
        lib = lib.copy(items = lib.items.toMutableList().also { it[j] = item }, removed = removed)
        libs[i] = lib
        save()
        true
    }

    /** Records what the track with a key is linked to on MusicBrainz. True if the list held the track. */
    fun setLink(id: String, key: String, state: String, mbid: String): Boolean = synchronized(lock) {
        val i = at(id)
        val lib = libs[i]
        val j = lib.items.indexOfFirst { it.key == key }
        if (j < 0) return false
        val item = lib.items[j].copy(state = state, mbid = if (state == LinkState.MATCHED) mbid else "")
        libs[i] = lib.copy(items = lib.items.toMutableList().also { it[j] = item })
        save()
        true
    }

    /** The artist's link in every track, in every list, that credits the artist by that name once folded. */
    fun setArtistLink(artist: String, state: String, mbid: String): Int = synchronized(lock) {
        val name = Match.normalize(artist)
        if (name.isEmpty()) return 0
        var changed = 0
        for (i in libs.indices) {
            val lib = libs[i]
            val items = lib.items.map { item ->
                if (Match.normalize(item.artist) != name) item else {
                    changed++
                    item.copy(artistState = state, artistMbid = if (state == LinkState.MATCHED) mbid else "")
                }
            }
            libs[i] = lib.copy(items = items)
        }
        if (changed == 0) return 0
        save()
        changed
    }

    /** Moves the track at [from] to [to]; every track between the two places moved, so each is re-stamped. */
    fun moveItem(id: String, from: Int, to: Int): Unit = synchronized(lock) {
        val i = at(id)
        val items = libs[i].items.toMutableList()
        if (from < 0 || from >= items.size || to < 0 || to >= items.size) throw LibraryException("library: no such place")
        if (from == to) return
        val item = items.removeAt(from)
        items.add(to, item)
        val now = clock.now()
        for (j in minOf(from, to)..maxOf(from, to)) items[j] = items[j].copy(updatedAt = now)
        libs[i] = libs[i].copy(items = items)
        save()
    }

    // ---- what the server reports --------------------------------------------------------------------------

    /**
     * Makes a list for one that came from the server, which keeps the identity the server gave it. A deletion
     * remembered for the same list is let go.
     */
    fun addRemote(serverId: String, name: String, cover: String, updated: Instant): Library = synchronized(lock) {
        val lib = Library(id = newLocalId(), serverId = serverId, name = name, createdAt = updated, updatedAt = updated, cover = cover)
        deleted = deleted.filterNot { it.serverId == serverId }.toMutableList()
        libs.add(lib)
        save()
        lib
    }

    /** A list's name and cover from the server, as of the time the server holds them at (not the clock's). */
    fun applyRemoteList(id: String, name: String, cover: String, updated: Instant) = synchronized(lock) {
        val i = at(id)
        libs[i] = libs[i].copy(name = name, cover = cover, updatedAt = updated)
        save()
    }

    /** Takes a list away because the server says it is gone: leaves nothing to tell. */
    fun dropRemote(id: String) = synchronized(lock) {
        val i = at(id)
        libs.removeAt(i)
        save()
    }

    /**
     * Folds what the server reports of a list's tracks into the list, the later record of each winning. None of it
     * stamps the clock or leaves a tombstone: a removal the server reported must not go back as a new one. Returns
     * how many tracks changed.
     */
    fun applyRemoteItems(id: String, changes: List<RemoteItem>): Int = synchronized(lock) {
        val i = at(id)
        var lib = libs[i]
        var items = lib.items.toMutableList()
        var removed = lib.removed
        // places is where each track asks to stand: the place the server gave it, or the one it already has.
        val places = MutableList(items.size) { it }
        var changed = 0
        for (change in changes) {
            val remote = change.item
            val j = items.indexOfFirst { sameRef(it, remote) }
            when {
                change.deleted -> {
                    if (j >= 0 && !remote.updatedAt.isBefore(items[j].updatedAt)) {
                        items.removeAt(j); places.removeAt(j); changed++
                    }
                }
                j >= 0 -> {
                    var held = items[j]
                    if (!remote.updatedAt.isAfter(held.updatedAt)) continue
                    held = held.copy(updatedAt = remote.updatedAt)
                    places[j] = change.position
                    if (held.thumbnail.isEmpty()) held = held.copy(thumbnail = remote.thumbnail)
                    if (held.albumThumbnail.isEmpty()) held = held.copy(albumThumbnail = remote.albumThumbnail)
                    // A newer record carries the reason it was chosen; one that carries none keeps the one held.
                    if (remote.why.isNotEmpty()) held = held.copy(why = remote.why)
                    items[j] = held
                    changed++
                }
                else -> {
                    val (src, ref) = remote.activeRef()
                    if (removed.any { it.source == src && it.key == ref && it.removedAt.isAfter(remote.updatedAt) }) continue
                    removed = forget(removed, remote)
                    items.add(remote); places.add(change.position)
                    changed++
                }
            }
        }
        if (changed == 0) return 0
        // Two tracks may ask for one place, as two devices each put one at the end: the one with the lower
        // reference goes first, so every device that sees both puts them in the same order.
        val order = items.indices.sortedWith { x, y ->
            val c = places[x].compareTo(places[y])
            if (c != 0) c else {
                val (xs, xid) = items[x].activeRef()
                val (ys, yid) = items[y].activeRef()
                val s = compareUtf8(xs, ys)
                if (s != 0) s else compareUtf8(xid, yid)
            }
        }
        items = order.map { items[it] }.toMutableList()
        lib = lib.copy(items = items, removed = removed)
        libs[i] = lib
        save()
        changed
    }

    companion object {
        /** How long a removal is remembered. */
        val TOMBSTONE_LIFE: Duration = Duration.ofHours(90 * 24)
        private val random = SecureRandom()

        /** A list's local id: a short random hex string (8 bytes). */
        fun newLocalId(): String {
            val b = ByteArray(8)
            random.nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        /** Go's strings.Compare orders by UTF-8 bytes, which is code point order. */
        fun compareUtf8(a: String, b: String): Int {
            val x = a.codePoints().toArray()
            val y = b.codePoints().toArray()
            for (k in 0 until minOf(x.size, y.size)) if (x[k] != y[k]) return x[k].compareTo(y[k])
            return x.size.compareTo(y.size)
        }

        /** Whether two tracks are the one the server knows by one reference. A track with no id is the same as none. */
        fun sameRef(a: Item, b: Item): Boolean {
            val (asrc, aid) = a.activeRef()
            val (bsrc, bid) = b.activeRef()
            return aid.isNotEmpty() && asrc == bsrc && aid == bid
        }

        /** Whether a list already holds a track: same provider and key, or same recording by folded names and length. */
        fun sameItem(a: Item, b: Item): Boolean {
            if (a.source == b.source && a.key.isNotEmpty() && a.key == b.key) return true
            if (Match.normalize(a.title).isEmpty() || Match.normalize(b.title).isEmpty()) return false
            return Match.match(track(a), track(b)) == Match.Status.MATCHED
        }

        private fun track(i: Item) = Match.Track(i.title, i.artist, i.durationMs)

        private fun remember(removed: List<Tombstone>, t: Tombstone): List<Tombstone> {
            if (t.key.isEmpty()) return removed // never sent, nothing to tell
            return removed.filterNot { it.source == t.source && it.key == t.key } + t
        }

        private fun forget(removed: List<Tombstone>, item: Item): List<Tombstone> {
            val (src, id) = item.activeRef()
            return removed.filterNot { it.source == src && it.key == id }
        }
    }
}
