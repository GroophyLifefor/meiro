package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.Rfc3339Nano
import com.muratkirazkaya.meiro.data.Source
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant

/** RFC 3339 with nanoseconds on the wire, any offset accepted on the way in. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(Rfc3339Nano.format(value))
    override fun deserialize(decoder: Decoder): Instant = Rfc3339Nano.parse(decoder.decodeString())
}

/**
 * The one JSON configuration of the sync, matching Go's `encoding/json` as the desktop uses it: unknown fields are
 * ignored, `null` reads as the field's default (a Go zero value), and a field equal to its default is left out
 * unless it is marked [EncodeDefault] (Go's `omitempty` versus a field that is always written).
 */
@OptIn(ExperimentalSerializationApi::class)
val SyncJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}


/** `internal/sync.Track`: the server's reference to a track. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Track(
    @EncodeDefault val provider: String = "",
    @SerialName("provider_track_id") @EncodeDefault val providerTrackId: String = "",
    @EncodeDefault val title: String = "",
    @EncodeDefault val artist: String = "",
    val album: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    val isrc: String = "",
    val thumbnail: String = "",
    @SerialName("album_thumbnail") val albumThumbnail: String = "",
)

/** `TrackFor`: provider defaults to ytm; the id is the key playback uses, or the page URL when there is none. */
fun trackFor(source: String, key: String, url: String, title: String, artist: String, durationMs: Long): Track =
    Track(
        provider = source.ifEmpty { Source.YTM },
        providerTrackId = key.ifEmpty { url },
        title = title, artist = artist, durationMs = durationMs,
    )

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Event(
    @SerialName("client_event_id") @EncodeDefault val clientEventId: String = "",
    @EncodeDefault val track: Track = Track(),
    @SerialName("played_ms") @EncodeDefault val playedMs: Long = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @EncodeDefault val completed: Boolean = false,
    val device: String = "",
    @SerialName("played_at") @EncodeDefault val playedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Library(
    val id: String = "",
    @EncodeDefault val kind: String = "",
    val provider: String = "",
    @SerialName("provider_id") val providerId: String = "",
    @EncodeDefault val name: String = "",
    val cover: String = "",
    @SerialName("updated_at") @EncodeDefault val updatedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
    val deleted: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class LibraryItem(
    @EncodeDefault val track: Track = Track(),
    @EncodeDefault val position: Int = 0,
    /** The line a track carries with it, such as the reason the Daily mix chose it. */
    val note: String = "",
    @SerialName("updated_at") @EncodeDefault val updatedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
    val deleted: Boolean = false,
)

@Serializable
data class LibraryItems(
    @SerialName("library_id") val libraryId: String = "",
    val items: List<LibraryItem> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Download(
    @EncodeDefault val track: Track = Track(),
    @EncodeDefault val device: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("last_listened_at") val lastListenedAt: @Serializable(with = InstantSerializer::class) Instant? = null,
    @SerialName("updated_at") @EncodeDefault val updatedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
    val deleted: Boolean = false,
)

// ---- reads -------------------------------------------------------------------------------------------------

/** A list as the server holds it (`ChangedLibrary`), flat as on the wire. */
@Serializable
data class ChangedLibrary(
    val id: String = "",
    val kind: String = "",
    val provider: String = "",
    @SerialName("provider_id") val providerId: String = "",
    val name: String = "",
    val cover: String = "",
    @SerialName("updated_at") val updatedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
    val deleted: Boolean = false,
    val seq: Long = 0,
)

@Serializable
data class ChangedItem(
    @SerialName("library_id") val libraryId: String = "",
    val track: Track = Track(),
    val position: Int = 0,
    val note: String = "",
    @SerialName("updated_at") val updatedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
    val deleted: Boolean = false,
    val seq: Long = 0,
) {
    fun toLibraryItem() = LibraryItem(track, position, note, updatedAt, deleted)
}

@Serializable
data class Changes(
    val libraries: List<ChangedLibrary> = emptyList(),
    val items: List<ChangedItem> = emptyList(),
    val next: Long = 0,
    val more: Boolean = false,
)

@Serializable
data class PlayedEvent(
    @SerialName("client_event_id") val clientEventId: String = "",
    val track: Track = Track(),
    @SerialName("played_ms") val playedMs: Long = 0,
    @SerialName("duration_ms") val durationMs: Long = 0,
    val completed: Boolean = false,
    val device: String = "",
    @SerialName("played_at") val playedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
    val seq: Long = 0,
) {
    fun toEvent() = Event(clientEventId, track, playedMs, durationMs, completed, device, playedAt)
}

/** What the server holds of an account's plays after a point; [clearedAt] is when the history was last cleared. */
@Serializable
data class Plays(
    val events: List<PlayedEvent> = emptyList(),
    val next: Long = 0,
    val more: Boolean = false,
    @SerialName("cleared_at") val clearedAt: @Serializable(with = InstantSerializer::class) Instant? = null,
)

// ---- links -------------------------------------------------------------------------------------------------

@Serializable
data class SongLink(
    val provider: String = "",
    @SerialName("provider_track_id") val providerTrackId: String = "",
    val state: String = "",
    val mbid: String = "",
    val artist: String = "",
    @SerialName("artist_mbid") val artistMbid: String = "",
    @SerialName("artist_state") val artistState: String = "",
    @SerialName("checked_at") val checkedAt: @Serializable(with = InstantSerializer::class) Instant? = null,
    @SerialName("updated_at") val updatedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
)

@Serializable
data class ArtistLink(
    @SerialName("artist_key") val artistKey: String = "",
    val name: String = "",
    val state: String = "",
    val mbid: String = "",
    @SerialName("updated_at") val updatedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
)

@Serializable
data class AltLink(
    val provider: String = "",
    @SerialName("provider_track_id") val providerTrackId: String = "",
    @SerialName("other_provider") val otherProvider: String = "",
    @SerialName("other_track_id") val otherTrackId: String = "",
    @SerialName("other_url") val otherUrl: String = "",
    @SerialName("checked_at") val checkedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
    @SerialName("updated_at") val updatedAt: @Serializable(with = InstantSerializer::class) Instant = Rfc3339Nano.ZERO,
)

/** Every kind of link in one value, as it is sent and read. Go writes all three arrays (nil as null); so do we. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Links(
    @EncodeDefault val songs: List<SongLink> = emptyList(),
    @EncodeDefault val artists: List<ArtistLink> = emptyList(),
    @EncodeDefault val alternates: List<AltLink> = emptyList(),
) {
    fun isEmpty() = songs.size + artists.size + alternates.size == 0
    val size: Int get() = songs.size + artists.size + alternates.size
}

/** `GET /api/v1/stats/summary` (meiro-server `statsSummaryResp`): counted plays of one month or year, every device. */
@Serializable
data class StatsSummary(
    val totals: StatsTotals = StatsTotals(),
    @SerialName("top_tracks") val topTracks: List<StatsTrack> = emptyList(),
    @SerialName("top_artists") val topArtists: List<StatsArtist> = emptyList(),
    val series: List<StatsBucket> = emptyList(),
)

@Serializable
data class StatsTotals(
    @SerialName("played_ms") val playedMs: Long = 0,
    val plays: Int = 0,
    @SerialName("unique_tracks") val uniqueTracks: Int = 0,
    @SerialName("unique_artists") val uniqueArtists: Int = 0,
)

@Serializable
data class StatsTrack(val title: String = "", val artist: String = "", val plays: Int = 0, @SerialName("played_ms") val playedMs: Long = 0)

@Serializable
data class StatsArtist(val artist: String = "", val plays: Int = 0, @SerialName("played_ms") val playedMs: Long = 0)

/** One day ("2026-10-05") of a month, or one month ("2026-10") of a year; buckets with no plays are left out. */
@Serializable
data class StatsBucket(val bucket: String = "", @SerialName("played_ms") val playedMs: Long = 0)
