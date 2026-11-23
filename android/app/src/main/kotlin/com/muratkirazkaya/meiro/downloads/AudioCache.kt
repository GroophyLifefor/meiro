package com.muratkirazkaya.meiro.downloads

import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.QItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** What is known of a cached track (`audio.TrackMeta`), so a search can find it without the network. */
@Serializable
data class CachedMeta(
    val source: String = Source.YTM,
    val key: String = "",
    val title: String = "",
    val artist: String = "",
    val duration: String = "",
    val thumbnail: String = "",
) {
    fun toQItem() = QItem(source = source, key = key, title = title, artist = artist, duration = duration, thumbnail = thumbnail)
}

/**
 * The playback cache of one provider (`internal/audio` `Cache`): complete audio files of recently played tracks, kept
 * least-recently-used first out once they pass [limitBytes] (the desktop's 512 MB per provider; 0 turns it off). A
 * track is added from the URL playback already resolved, in the background, one at a time, after its audio starts
 * (`audioStarted`); a file is used only once every byte arrived. A file's modification time is its last use, so the
 * order survives restarts. The desktop keeps no playlists in its cache; the phone joins SoundCloud's HLS segments as its
 * downloads do.
 */
class AudioCache(
    val dir: File,
    @Volatile var limitBytes: Long = DEFAULT_LIMIT_BYTES,
    private val fetch: AudioFetch,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val metaFile = File(dir, "meta.json")
    private val lock = Any()
    private var meta: Map<String, CachedMeta> = emptyMap()
    private val pending = mutableSetOf<String>()
    private val worker = Mutex()
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    init {
        dir.mkdirs()
        meta = try {
            if (metaFile.exists()) JSON.decodeFromString(MapSerializer(String.serializer(), CachedMeta.serializer()), metaFile.readText()) else emptyMap()
        } catch (e: Exception) { emptyMap() }
        // Leftovers of an earlier run: half-written files go, and the cache is brought within its limit.
        dir.listFiles()?.filter { it.name.startsWith(".part-") }?.forEach { it.delete() }
        trim(null)
    }

    private fun files(): List<File> = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && it.name != metaFile.name && !it.name.endsWith(".tmp") }.orEmpty()

    private fun nameOf(key: String) = DownloadStore.fileName("", key, "").take(64)

    /** The cached file of a track, moved to the most-recently-used end (`Get`); null when it is not cached or the cache is off. */
    fun get(key: String): File? {
        if (limitBytes <= 0 || key.isEmpty()) return null
        val name = nameOf(key)
        val f = files().firstOrNull { it.name.substringBefore('.') == name && it.length() > 0 } ?: return null
        f.setLastModified(System.currentTimeMillis())
        return f
    }

    fun has(key: String): Boolean = limitBytes > 0 && files().any { it.name.substringBefore('.') == nameOf(key) }

    /**
     * Caches [item] from [url] in the background unless it is cached or on its way (`Add`). The URL is used as it is,
     * so caching never resolves a track a second time.
     */
    fun add(item: QItem, url: String, hls: Boolean) {
        val key = item.key
        if (limitBytes <= 0 || key.isEmpty() || url.isEmpty() || has(key)) return
        synchronized(lock) { if (!pending.add(key)) return }
        scope.launch {
            try {
                worker.withLock {
                    val t0 = System.nanoTime()
                    val f = fetch.save(url, hls, File(dir, nameOf(key)))
                    synchronized(lock) {
                        meta = meta + (key to CachedMeta(item.provider(), key, item.title, item.artist, item.duration, item.thumbnail))
                        saveMeta()
                    }
                    log("cache: ${item.provider()}:$key cached ${f.length()} bytes in ${(System.nanoTime() - t0) / 1_000_000} ms")
                    trim(key)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // The track is already playing: a failed cache copy is just skipped.
                log("cache: could not cache ${item.provider()}:$key: ${e.message}")
            } finally {
                synchronized(lock) { pending.remove(key) }
            }
        }
    }

    /** Removes the least-recently-used files until the cache is within its limit, never [keep] (`trimLocked`). */
    fun trim(keep: String?) {
        val keepName = keep?.let { nameOf(it) }
        var all = files().sortedBy { it.lastModified() }
        var total = all.sumOf { it.length() }
        val gone = mutableListOf<String>()
        for (f in all) {
            if (total <= limitBytes.coerceAtLeast(0)) break
            if (f.name.substringBefore('.') == keepName) continue
            total -= f.length()
            gone += f.name.substringBefore('.')
            f.delete()
        }
        all = files()
        synchronized(lock) {
            val names = all.map { it.name.substringBefore('.') }.toSet()
            val kept = meta.filterKeys { nameOf(it) in names }
            if (kept.size != meta.size) { meta = kept; saveMeta() }
        }
        if (gone.isNotEmpty()) log("cache: ${gone.size} files evicted, ${total} bytes kept")
        _version.value += 1
    }

    /** What is known of every cached track, for a local search (`Meta`). */
    fun metas(): List<CachedMeta> = synchronized(lock) { meta.values.toList() }

    /** The bytes the cache takes. */
    fun usage(): Long = files().sumOf { it.length() }

    private fun saveMeta() {
        val tmp = File(dir, "meta.json.tmp")
        tmp.writeText(JSON.encodeToString(MapSerializer(String.serializer(), CachedMeta.serializer()), meta))
        if (!tmp.renameTo(metaFile)) { metaFile.delete(); tmp.renameTo(metaFile) }
    }

    companion object {
        /** `settings.DefaultCacheMB` (512) per provider. */
        const val DEFAULT_LIMIT_BYTES = 512L shl 20
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
