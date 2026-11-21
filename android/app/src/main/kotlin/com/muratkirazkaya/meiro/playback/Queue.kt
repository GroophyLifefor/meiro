package com.muratkirazkaya.meiro.playback

import kotlin.random.Random

/** What happens when a track, or the queue, ends (`repeatMode`). */
enum class RepeatMode {
    Off, // play on, and stop at the end of the queue
    Queue, // go back to the first track after the last
    Track; // play the same track again

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

/** The generated tracks a queue holds, the current one and those to come (`autoplayQueueLimit`). */
const val AUTOPLAY_QUEUE_LIMIT = 100

/** How far apart two uploads' lengths can be and still be the same song (`sameSongTolerance`). */
const val SAME_SONG_TOLERANCE_MS = 3000L

/**
 * Port of `queue.go`: what plays is the tracks the user chose, followed by the tracks auto-play generated after
 * them, and the place of the one playing. Fields are changed only through the methods, which keep them consistent:
 * [index] is a place in [items] whenever it is not empty, [recStart] is -1 or a place in [items], and every
 * generated track in [items] is in [generated].
 */
class Queue(private val random: Random = Random.Default) {
    var items: MutableList<QItem> = mutableListOf(); private set
    var index = 0
    /** The place of the first generated track, or -1 when the queue holds none. */
    var recStart = -1
    var generated: MutableSet<String> = mutableSetOf(); private set
    /** The track the user asked to play next, which plays before the rest even after a reorder. */
    var playNext = ""
    /** The order the queue held before it was arranged, so shuffle off puts it back. */
    private var shuffleKept: MutableList<QItem> = mutableListOf()
    var source = ""

    val size get() = items.size

    /** The track at the queue's place, or null when the queue is empty. */
    fun current(): QItem? = items.getOrNull(index)

    fun at(i: Int): QItem = items[i]

    /** How many tracks follow the current one. */
    fun remaining(): Int = items.size - index - 1

    /**
     * Makes the queue [tracks], chosen from [source], with [item] at its place: [index] when that is where item is,
     * or else wherever item first is. A queue without item in it holds item alone. Generated tracks and the
     * play-next mark of the queue before are dropped.
     */
    fun replace(item: QItem, tracks: List<QItem>, index: Int, source: String) {
        items = tracks.toMutableList(); generated = mutableSetOf(); recStart = -1; playNext = ""
        shuffleKept = mutableListOf(); this.source = source
        var i = index
        if (i < 0 || i >= items.size || items[i].queueKey() != item.queueKey()) {
            i = items.indexOfFirst { it.queueKey() == item.queueKey() }
        }
        if (i < 0) { items = mutableListOf(item); i = 0 }
        this.index = i
    }

    /** The end of what may play: the whole queue, or only the chosen tracks when auto-play is off. */
    fun limit(autoPlay: Boolean): Int = if (!autoPlay && recStart >= 0) recStart else items.size

    /** The place of the track that follows the current one, or null when the queue is over. */
    fun next(autoPlay: Boolean, repeat: RepeatMode): Int? {
        if (playNext.isNotEmpty()) {
            for (i in maxOf(index + 1, 0) until items.size) if (items[i].queueKey() == playNext) return i
        }
        val limit = limit(autoPlay)
        return when {
            limit == 0 || index >= limit -> null
            index + 1 < limit -> index + 1
            repeat == RepeatMode.Queue -> 0
            else -> null
        }
    }

    /** Makes the track at [i] the current one. */
    fun moveTo(i: Int): QItem {
        index = i
        if (items[i].queueKey() == playNext) playNext = ""
        return items[i]
    }

    fun find(key: String): Int = items.indexOfFirst { it.queueKey() == key }

    fun setSource(key: String, source: String) {
        val i = find(key)
        if (i >= 0) items[i] = items[i].copy(source = source)
    }

    /**
     * Adds a chosen track: after the current one when [playNext] is set, and otherwise at the end of the chosen
     * tracks, ahead of the generated ones.
     */
    fun insert(item: QItem, playNext: Boolean, autoPlay: Boolean) {
        var position = if (recStart >= 0) recStart else items.size
        if (playNext) position = index + 1
        position = position.coerceIn(0, items.size)
        items.add(position, item)
        if (position <= index) index++
        if (recStart >= 0 && position <= recStart) recStart++
        if (playNext) {
            this.playNext = item.queueKey()
            // With auto-play off the queue stops at the generated tracks, so a track to play next placed among them
            // moves the boundary past it.
            if (!autoPlay && recStart >= 0 && position >= recStart) recStart = position + 1
        }
    }

    /**
     * Drops the track at [i], which must not be the current one. The current place, the play-next mark and the
     * boundary of the generated tracks follow. Reports whether anything was removed.
     */
    fun remove(i: Int): Boolean {
        if (i < 0 || i >= items.size || i == index) return false
        val id = items[i].queueKey()
        items.removeAt(i)
        if (i < index) index--
        if (id == playNext) playNext = ""
        if (recStart >= 0) {
            if (i < recStart) recStart-- else generated.remove(id)
            if (recStart >= items.size) recStart = -1
        }
        return true
    }

    /** Drops every track that follows the current one, the generated tracks included. */
    fun clearUpcoming(): Boolean {
        if (remaining() < 1) return false
        items = items.subList(0, index + 1).toMutableList()
        playNext = ""; recStart = -1; generated = mutableSetOf()
        return true
    }

    /** Moves the track at [i] by [delta] places, across the divider if it is in the way. */
    fun shift(i: Int, delta: Int): Boolean {
        val target = i + delta
        if (i < 0 || i >= items.size || target < 0 || target >= items.size) return false
        var to = rowOf(target)
        if (delta > 0) to++
        return moveRows(listOf(rowOf(i)), to)
    }

    /** Counts the generated tracks again from the boundary, for a queue that goes back to auto-play. */
    fun regenerate() {
        generated = mutableSetOf()
        if (recStart < 0) return
        for (item in items.subList(recStart, items.size)) if (item.queueKey().isNotEmpty()) generated.add(item.queueKey())
    }

    /**
     * Adds the recommendations the provider returned that the queue does not already hold, keeping at most
     * [AUTOPLAY_QUEUE_LIMIT] generated tracks: played tracks are dropped first to make room, without touching the one
     * playing.
     */
    fun appendGenerated(incoming: List<QItem>) {
        val seen = HashSet<String>()
        val versions = HashMap<SongId, MutableList<Long>>()
        for (queued in items) {
            if (queued.queueKey().isNotEmpty()) seen.add(queued.queueKey())
            noteVersion(versions, queued)
        }
        val fresh = mutableListOf<QItem>()
        for (item in incoming) {
            if (item.queueKey().isEmpty() || item.kind == Kind.Video) continue
            if (!seen.add(item.queueKey())) continue
            if (hasVersion(versions, item)) continue
            noteVersion(versions, item)
            fresh.add(item)
            if (fresh.size > AUTOPLAY_QUEUE_LIMIT) break
        }
        if (generated.size + fresh.size > AUTOPLAY_QUEUE_LIMIT && index > 0) {
            val played = index
            for (queued in items.subList(0, played)) generated.remove(queued.queueKey())
            items = items.subList(played, items.size).toMutableList()
            index = 0
            if (recStart >= 0) recStart = maxOf(0, recStart - played)
        }
        for (item in fresh) {
            if (generated.size >= AUTOPLAY_QUEUE_LIMIT) break
            if (recStart < 0) recStart = items.size
            items.add(item)
            generated.add(item.queueKey())
        }
    }

    /**
     * Arranges the whole queue behind the track that is playing, which moves to the top; the songs before it are not
     * left out. The generated tracks keep their place at the end. The order the chosen tracks were in is kept for
     * [restoreQueue].
     */
    fun shuffleQueue() {
        var limit = items.size
        if (recStart in 0 until limit) limit = recStart
        if (limit <= 1 || index < 0 || index >= limit) return
        shuffleKept = items.subList(0, limit).toMutableList()
        val current = items[index]
        val rest = (items.subList(0, index) + items.subList(index + 1, limit)).toMutableList()
        rest.shuffle(random)
        val ordered = listOf(current) + rest
        for (i in 0 until limit) items[i] = ordered[i]
        index = 0
        if (recStart >= 0) recStart = limit
    }

    /**
     * Puts the queue back in the order it held before it was arranged, with the track that is playing where it was,
     * and the tracks the queue gained while it was arranged after the rest.
     */
    fun restoreQueue() {
        if (shuffleKept.isEmpty()) return
        var limit = items.size
        if (recStart in 0 until limit) limit = recStart
        if (limit == 0) return
        val playing = if (index in 0 until limit) items[index].queueKey() else ""
        val left = items.subList(0, limit).toMutableList()
        val ordered = mutableListOf<QItem>()
        // Each arranged track once, in the order it is put back: a playlist holding a song twice keeps both copies.
        for (want in shuffleKept) {
            val i = left.indexOfFirst { it.queueKey() == want.queueKey() }
            if (i >= 0) { ordered.add(left[i]); left.removeAt(i) }
        }
        ordered.addAll(left)
        for (i in 0 until limit) items[i] = ordered[i]
        shuffleKept = mutableListOf()
        if (playing.isEmpty()) return
        for (i in 0 until limit) if (items[i].queueKey() == playing) { index = i; break }
    }

    // The queue's list shows a divider between the chosen and the generated tracks, when there are both. Rows of
    // that list are not places in the queue: these translate between the two.

    fun hasDivider(): Boolean = recStart > 0 && recStart < items.size
    fun rows(): Int = if (hasDivider()) items.size + 1 else items.size
    fun isDivider(row: Int): Boolean = hasDivider() && row == recStart

    /** The place in the queue a row of the list shows, or null for the divider or a row past the end. */
    fun trackAt(row: Int): Int? {
        if (isDivider(row)) return null
        val r = if (hasDivider() && row > recStart) row - 1 else row
        return if (r in 0 until items.size) r else null
    }

    fun rowOf(i: Int): Int = if (hasDivider() && i >= recStart) i + 1 else i

    /**
     * Carries out a reorder of the list: the tracks the rows show move to before the row [to], as the list stood
     * before the move. The current track, the play-next mark and the boundary of the generated tracks follow.
     */
    fun moveRows(rows: List<Int>, to: Int): Boolean {
        val indices = rows.mapNotNull { trackAt(it) }
        if (indices.isEmpty()) return false
        var toIndex = to
        if (hasDivider() && to > recStart) toIndex--
        val current = current()
        val moved = reorder(items, indices, toIndex)
        val idx = moved.indexOfFirst { it.queueKey() == (current?.queueKey() ?: "") }
        if (idx < 0) return false
        items = moved.toMutableList(); index = idx
        recStart = -1
        if (generated.isNotEmpty()) recStart = moved.indexOfFirst { generated.contains(it.queueKey()) }
        return true
    }

    companion object {
        /** Moves the items at [indices], in any order, to before [to] (a place before the move); a new list. */
        fun reorder(items: List<QItem>, indices: List<Int>, to: Int): List<QItem> {
            val at = indices.filter { it in items.indices }.toSortedSet()
            val moving = mutableListOf<QItem>()
            val rest = mutableListOf<QItem>()
            for ((i, item) in items.withIndex()) if (i in at) moving.add(item) else rest.add(item)
            // Each item taken from before the destination pulls it back by one.
            var insert = to
            for (i in at) if (i < to) insert--
            insert = insert.coerceIn(0, rest.size)
            return rest.subList(0, insert) + moving + rest.subList(insert, rest.size)
        }
    }
}

/** A song by its title and artist, ignoring case, spacing and punctuation, so two uploads of it compare equal. */
data class SongId(val title: String, val artist: String)

fun songIdOf(item: QItem) = SongId(normalizeName(item.title), normalizeName(item.artist))

/** Lowercases text and keeps only its letters and digits. */
fun normalizeName(text: String): String = buildString { for (ch in text) if (ch.isLetterOrDigit()) append(ch.lowercaseChar()) }

private fun noteVersion(versions: MutableMap<SongId, MutableList<Long>>, item: QItem) {
    val key = songIdOf(item)
    if (key.title.isNotEmpty()) versions.getOrPut(key) { mutableListOf() }.add(item.durationMs())
}

/** Another upload of a song already noted: same title and artist and nearly the same length (or a length unknown). */
private fun hasVersion(versions: Map<SongId, List<Long>>, item: QItem): Boolean {
    val key = songIdOf(item)
    if (key.title.isEmpty()) return false
    val d = item.durationMs()
    return versions[key].orEmpty().any { o -> d <= 0 || o <= 0 || Math.abs(d - o) <= SAME_SONG_TOLERANCE_MS }
}
