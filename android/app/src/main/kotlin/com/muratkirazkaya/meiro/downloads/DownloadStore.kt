package com.muratkirazkaya.meiro.downloads

import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.data.SourceRef
import com.muratkirazkaya.meiro.playback.QItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * One downloaded track (`downloads.Item`): what plays it, what names it, its file inside the store's directory, its size
 * and when it was downloaded and last played (epoch milliseconds, 0 unset).
 */
@Serializable
data class DownloadItem(
    val source: String = Source.YTM,
    val key: String = "",
    val url: String = "",
    val title: String = "",
    val artist: String = "",
    val duration: String = "",
    val thumbnail: String = "",
    val sizeBytes: Long = 0,
    val downloadedAt: Long = 0,
    val lastListenedAt: Long = 0,
    val file: String = "",
    /** The providers that hold the track (provider to key), so a kept track keeps its other source. */
    val refs: Map<String, String> = emptyMap(),
) {
    fun toQItem(): QItem = QItem(
        source = source, key = key, title = title, artist = artist, duration = duration, thumbnail = thumbnail,
        sources = refs.keys.toList().takeIf { it.size > 1 }.orEmpty(), refs = refs,
    )

    /** The record a search shows for it, as the list items carry their providers. */
    fun refsList(): List<SourceRef> = refs.map { (s, k) -> if (s == Source.SOUNDCLOUD) SourceRef(s, k, k) else SourceRef(s, k) }
}

/** A download that was removed (`downloads.Tombstone`), kept so the removal can be told to the server. */
@Serializable
data class DownloadTombstone(
    val source: String = Source.YTM,
    val key: String = "",
    val title: String = "",
    val artist: String = "",
    val removedAt: Long = 0,
)

@Serializable
private data class DownloadIndex(val items: List<DownloadItem> = emptyList(), val removed: List<DownloadTombstone> = emptyList())

/**
 * The tracks the user chose to download and keep (`internal/downloads`): a JSON index beside the audio files in [dir],
 * never evicted, only removed by the user. A download is identified by its provider and key, the pair playback uses.
 * Removals are remembered for 90 days so the server can be told (`Removed`). Safe for use from any thread.
 */
class DownloadStore(val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val indexFile = File(dir, "index.json")
    private val lock = Any()
    private var items: List<DownloadItem> = emptyList()
    private var removed: List<DownloadTombstone> = emptyList()
    private val _version = MutableStateFlow(0L)

    /** Grows on every change, so screens can redraw. */
    val version: StateFlow<Long> = _version

    private val _keys = MutableStateFlow<Set<String>>(emptySet())

    /** `source:key` of every download, for the rows' downloaded mark. */
    val keys: StateFlow<Set<String>> = _keys

    init {
        dir.mkdirs()
        try {
            if (indexFile.exists()) {
                val idx = JSON.decodeFromString(DownloadIndex.serializer(), indexFile.readText())
                items = idx.items; removed = idx.removed
            }
        } catch (e: Exception) {
            // A damaged index is replaced by the next save rather than stopping the app (`load`).
        }
        prune()
        publish()
    }

    private fun prune() {
        val t = now()
        removed = removed.filter { t - it.removedAt <= TOMBSTONE_LIFE_MS }
    }

    private fun publish() {
        _keys.value = items.map { mark(it.source, it.key) }.toSet()
        _version.value += 1
    }

    private fun save() {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(JSON.encodeToString(DownloadIndex.serializer(), DownloadIndex(items, removed)))
        if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
    }

    /** The downloads, newest first (`List`). */
    fun list(): List<DownloadItem> = synchronized(lock) { items.sortedByDescending { it.downloadedAt } }

    fun get(source: String, key: String): DownloadItem? = synchronized(lock) { items.firstOrNull { it.source == source.ifEmpty { Source.YTM } && it.key == key } }

    fun has(item: QItem): Boolean = get(item.provider(), item.key) != null

    /** The audio file of a downloaded track, when it is downloaded and its file is there. */
    fun file(source: String, key: String): File? {
        val rec = get(source, key) ?: return null
        if (rec.file.isEmpty()) return null
        return File(dir, rec.file).takeIf { it.isFile && it.length() > 0 }
    }

    /** Where a track's audio goes (`Path`): its name is the sha256 of `source:key` plus [ext]. */
    fun pathFor(source: String, key: String, ext: String): File = File(dir, fileName(source, key, ext))

    /** Records a finished download, replacing the entry of the same track; a track downloaded again is no removal any more. */
    fun add(item: DownloadItem) {
        require(item.key.isNotEmpty()) { "downloads: a key is needed" }
        val rec = item.copy(source = item.source.ifEmpty { Source.YTM })
        synchronized(lock) {
            items = items.filterNot { it.source == rec.source && it.key == rec.key } + rec
            removed = removed.filterNot { it.source == rec.source && it.key == rec.key }
            save(); publish()
        }
    }

    /** Takes a download out and deletes its file; the removal is remembered for the server. Returns the entry removed. */
    fun remove(source: String, key: String): DownloadItem? {
        val src = source.ifEmpty { Source.YTM }
        val gone = synchronized(lock) {
            val rec = items.firstOrNull { it.source == src && it.key == key } ?: return null
            items = items - rec
            prune()
            removed = removed.filterNot { it.source == src && it.key == key } +
                DownloadTombstone(src, key, rec.title, rec.artist, now())
            save(); publish()
            rec
        }
        if (gone.file.isNotEmpty()) File(dir, gone.file).delete()
        return gone
    }

    /** The removals the server has yet to hear of, oldest first. */
    fun removedList(): List<DownloadTombstone> = synchronized(lock) { prune(); removed.sortedBy { it.removedAt } }

    /** Records that a downloaded track was played now (`Touch`); nothing for a track that is not downloaded. */
    fun touch(source: String, key: String) {
        synchronized(lock) {
            val src = source.ifEmpty { Source.YTM }
            val i = items.indexOfFirst { it.source == src && it.key == key }
            if (i < 0) return
            items = items.toMutableList().also { it[i] = it[i].copy(lastListenedAt = now()) }
            save(); publish()
        }
    }

    /** The bytes the downloads take and how many there are. */
    fun usage(): Pair<Long, Int> = synchronized(lock) { items.sumOf { it.sizeBytes } to items.size }

    companion object {
        /** `tombstoneLife`. */
        const val TOMBSTONE_LIFE_MS = 90L * 24 * 3600 * 1000
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun mark(source: String, key: String) = "${source.ifEmpty { Source.YTM }}:$key"

        /** `fileName`: the same track always maps to the same name. */
        fun fileName(source: String, key: String, ext: String): String {
            val sum = MessageDigest.getInstance("SHA-256").digest("${source.ifEmpty { Source.YTM }}:$key".toByteArray())
            return sum.joinToString("") { "%02x".format(it) } + ext
        }
    }
}

/** The downloads as the server wants them (`syncSnapshot`): each kept track of this device and each removal as `deleted`. */
object DownloadSync {
    fun snapshot(store: DownloadStore, device: String): List<com.muratkirazkaya.meiro.sync.Download> {
        val at = { t: Long -> java.time.Instant.ofEpochMilli(t) }
        return store.list().map { rec ->
            val last = rec.lastListenedAt.takeIf { it > 0 }
            com.muratkirazkaya.meiro.sync.Download(
                track = com.muratkirazkaya.meiro.sync.trackFor(rec.source, rec.key, "", rec.title, rec.artist, com.muratkirazkaya.meiro.playback.parseDurationMs(rec.duration)),
                device = device, sizeBytes = rec.sizeBytes, lastListenedAt = last?.let(at),
                // A download's row changes when it is listened to again (`updated = max(DownloadedAt, LastListenedAt)`).
                updatedAt = at(maxOf(rec.downloadedAt, last ?: 0)),
            )
        } + store.removedList().map { gone ->
            com.muratkirazkaya.meiro.sync.Download(
                track = com.muratkirazkaya.meiro.sync.trackFor(gone.source, gone.key, "", gone.title, gone.artist, 0),
                device = device, updatedAt = at(gone.removedAt), deleted = true,
            )
        }
    }
}
