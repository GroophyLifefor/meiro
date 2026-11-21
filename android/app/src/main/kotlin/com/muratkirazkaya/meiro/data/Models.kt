package com.muratkirazkaya.meiro.data

import java.time.Instant

/** Provider strings the server and the desktop use (`media.Source`). */
object Source {
    const val YTM = "ytm"
    const val SOUNDCLOUD = "soundcloud"
}

/** What the app made of a track (internal/library `State`), kept as the same strings. */
object LinkState {
    const val MATCHED = "matched"
    const val UNKNOWN = "unknown"
    const val OTHER = "other"
}

/** A provider's copy of a track (`media.SourceRef`). */
data class SourceRef(val source: String, val id: String = "", val url: String = "")

/**
 * One track in a list (internal/library `Item`). [source] and [key] identify it in its provider: a video ID for
 * YouTube Music, a page URL for SoundCloud.
 */
data class Item(
    val source: String = "",
    val key: String = "",
    val url: String = "",
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    val state: String = "",
    val thumbnail: String = "",
    val albumThumbnail: String = "",
    /** The reason a track was recommended (the server's `note`). */
    val why: String = "",
    val mbid: String = "",
    val artistMbid: String = "",
    val artistState: String = "",
    val addedAt: Instant = Rfc3339Nano.ZERO,
    /** When the track was last added, moved or switched provider: what a sync weighs it by. */
    val updatedAt: Instant = Rfc3339Nano.ZERO,
    val sources: List<SourceRef> = emptyList(),
) {
    /**
     * The provider a track plays from and the id the provider names it by (`Item.ActiveRef`): the video ID of a
     * YouTube Music track, the page URL of a SoundCloud one. It is how the server tells one track from another.
     */
    fun activeRef(): Pair<String, String> {
        var src = source
        var k = key
        var u = url
        if (src.isEmpty()) src = Source.YTM
        if (source.isNotEmpty()) {
            val ref = sources.firstOrNull { it.source == source }
            if (ref != null) { k = ref.id; u = ref.url }
        }
        return if (src == Source.SOUNDCLOUD) src to u else src to k
    }

    internal fun tombstone(at: Instant): Tombstone {
        val (src, id) = activeRef()
        return Tombstone(source = src, key = id, url = if (src == Source.SOUNDCLOUD) id else "", title = title, artist = artist, removedAt = at)
    }
}

/** A track taken out of a list, kept so the removal can be told to the server (`Tombstone`). */
data class Tombstone(
    val source: String = "",
    val key: String = "",
    val url: String = "",
    val title: String = "",
    val artist: String = "",
    val removedAt: Instant = Rfc3339Nano.ZERO,
)

/** A list taken away, kept for the same reason. [serverId] is empty for a list made here. */
data class DeletedList(val id: String, val serverId: String = "", val name: String = "", val deletedAt: Instant = Rfc3339Nano.ZERO)

/** One of the user's lists. */
data class Library(
    val id: String,
    val name: String,
    val createdAt: Instant = Rfc3339Nano.ZERO,
    /** The identity the server knows the list by when the list came from the server; empty for one made here. */
    val serverId: String = "",
    /**
     * When the list itself last changed: made, renamed or given a cover. Its tracks have their own times, so
     * changing one leaves this alone.
     */
    val updatedAt: Instant = Rfc3339Nano.ZERO,
    val cover: String = "",
    val items: List<Item> = emptyList(),
    /** Tracks taken out that the server has yet to be told of, oldest first. */
    val removed: List<Tombstone> = emptyList(),
)

/** A change the server reports to one track of a list (`RemoteItem`). */
data class RemoteItem(val item: Item, val position: Int = 0, val deleted: Boolean = false)

/** One played track (internal/history `Entry`). */
data class HistoryEntry(
    /** The provider's own id for the track (named VideoID on the desktop). */
    val videoId: String = "",
    val title: String = "",
    val artist: String = "",
    val playedAt: Instant = Rfc3339Nano.ZERO,
    val source: String = "",
    val durationMs: Long = 0,
    val playedMs: Long = 0,
    val completed: Boolean = false,
    val device: String = "",
    val thumbnail: String = "",
    val eventId: String = "",
)
