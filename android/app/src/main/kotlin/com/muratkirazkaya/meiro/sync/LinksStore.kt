package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.Clock
import com.muratkirazkaya.meiro.data.Match
import com.muratkirazkaya.meiro.data.Persistence
import com.muratkirazkaya.meiro.data.Rfc3339Nano
import com.muratkirazkaya.meiro.data.isZero
import java.time.Duration
import java.time.Instant

/** Port of internal/mblinks: what is known of which MusicBrainz record a song and its artist are. */
data class Song(
    val provider: String, val id: String, val state: String = "", val mbid: String = "",
    val artist: String = "", val artistMbid: String = "", val artistState: String = "",
    val checkedAt: Instant = Rfc3339Nano.ZERO, val updatedAt: Instant = Rfc3339Nano.ZERO,
)

data class ArtistInfo(val name: String, val state: String = "", val mbid: String = "", val updatedAt: Instant = Rfc3339Nano.ZERO)

data class Alt(
    val provider: String, val id: String, val otherProvider: String = "", val otherId: String = "", val otherUrl: String = "",
    val checkedAt: Instant = Rfc3339Nano.ZERO, val updatedAt: Instant = Rfc3339Nano.ZERO,
)

data class LinkData(val songs: List<Song> = emptyList(), val artists: List<ArtistInfo> = emptyList(), val alts: List<Alt> = emptyList())

/** Two copies of the store merge by keeping the later record of each song, artist and pair of providers. */
class LinksStore(private val persistence: Persistence? = null, private val clock: Clock = Clock.System) {
    private val lock = Any()
    private val songs = LinkedHashMap<String, Song>()
    private val artists = LinkedHashMap<String, ArtistInfo>()
    private val alts = LinkedHashMap<String, Alt>()

    init {
        persistence?.loadLinksJson()?.let { json ->
            try {
                linksIn(SyncJson.decodeFromString(Links.serializer(), json)).let { d ->
                    d.songs.forEach { songs[songKey(it.provider, it.id)] = it }
                    d.artists.forEach { artists[artistKey(it.name)] = it }
                    d.alts.forEach { alts[songKey(it.provider, it.id)] = it }
                }
            } catch (_: Exception) { /* unreadable: start empty */ }
        }
    }

    private fun save() {
        persistence?.saveLinksJson(SyncJson.encodeToString(Links.serializer(), linksOut(dataLocked())))
    }

    private fun dataLocked() = LinkData(songs.values.toList(), artists.values.toList(), alts.values.toList())

    fun song(provider: String, id: String): Song? = synchronized(lock) { songs[songKey(provider, id)] }

    fun setSong(song: Song): Song = synchronized(lock) {
        val s = if (song.updatedAt.isZero) song.copy(updatedAt = clock.now()) else song
        songs[songKey(s.provider, s.id)] = s
        save(); s
    }

    fun artist(name: String): ArtistInfo? = synchronized(lock) { artists[artistKey(name)] }

    fun setArtist(artist: ArtistInfo): ArtistInfo = synchronized(lock) {
        val key = artistKey(artist.name)
        if (key.isEmpty()) return artist
        val a = if (artist.updatedAt.isZero) artist.copy(updatedAt = clock.now()) else artist
        artists[key] = a
        save(); a
    }

    fun alt(provider: String, id: String): Alt? = synchronized(lock) { alts[songKey(provider, id)] }

    fun setAlt(alt: Alt): Alt = synchronized(lock) {
        val a = if (alt.updatedAt.isZero) alt.copy(updatedAt = clock.now()) else alt
        alts[songKey(a.provider, a.id)] = a
        save(); a
    }

    fun data(): LinkData = synchronized(lock) { dataLocked() }

    /** Folds another copy in, keeping the later record of each; returns how many it took. */
    fun merge(d: LinkData): Int = synchronized(lock) {
        var took = 0
        for (song in d.songs) {
            val key = songKey(song.provider, song.id)
            val have = songs[key]
            if (have == null || song.updatedAt.isAfter(have.updatedAt)) { songs[key] = song; took++ }
        }
        for (artist in d.artists) {
            val key = artistKey(artist.name)
            if (key.isEmpty()) continue
            val have = artists[key]
            if (have == null || artist.updatedAt.isAfter(have.updatedAt)) { artists[key] = artist; took++ }
        }
        for (alt in d.alts) {
            val key = songKey(alt.provider, alt.id)
            val have = alts[key]
            if (have == null || alt.updatedAt.isAfter(have.updatedAt)) { alts[key] = alt; took++ }
        }
        if (took > 0) save()
        took
    }

    /** Whether a time is recent enough that what was checked then need not be checked again. */
    fun fresh(checked: Instant): Boolean = !checked.isZero && Duration.between(checked, clock.now()) < RECHECK_AFTER

    companion object {
        val RECHECK_AFTER: Duration = Duration.ofHours(30 * 24)
        private fun songKey(provider: String, id: String) = provider + "\u0000" + id
        fun artistKey(name: String): String = Match.normalize(name)
        /** A provider name trimmed; blank reads as YouTube Music's. */
        fun clean(provider: String): String = provider.trim().ifEmpty { "ytm" }

        // ---- the shapes the server takes and gives (syncpage.go songLinkOut ... linksIn) ----
        fun songLinkOut(s: Song) = SongLink(
            provider = clean(s.provider), providerTrackId = s.id, state = s.state, mbid = s.mbid, artist = s.artist,
            artistMbid = s.artistMbid, artistState = s.artistState,
            checkedAt = if (s.checkedAt.isZero) null else s.checkedAt, updatedAt = s.updatedAt,
        )

        fun artistLinkOut(a: ArtistInfo) = ArtistLink(artistKey(a.name), a.name, a.state, a.mbid, a.updatedAt)

        fun altLinkOut(a: Alt) = AltLink(
            provider = clean(a.provider), providerTrackId = a.id, otherProvider = a.otherProvider,
            otherTrackId = a.otherId, otherUrl = a.otherUrl, checkedAt = a.checkedAt, updatedAt = a.updatedAt,
        )

        fun linksOut(d: LinkData) = Links(d.songs.map(::songLinkOut), d.artists.map(::artistLinkOut), d.alts.map(::altLinkOut))

        fun linksIn(l: Links) = LinkData(
            songs = l.songs.map {
                Song(it.provider, it.providerTrackId, it.state, it.mbid, it.artist, it.artistMbid, it.artistState, it.checkedAt ?: Rfc3339Nano.ZERO, it.updatedAt)
            },
            // The server names an artist by its folded name; the store folds the name it is given, so the display
            // name is what goes in.
            artists = l.artists.map { ArtistInfo(it.name, it.state, it.mbid, it.updatedAt) },
            alts = l.alternates.map { Alt(it.provider, it.providerTrackId, it.otherProvider, it.otherTrackId, it.otherUrl, it.checkedAt, it.updatedAt) },
        )
    }
}
