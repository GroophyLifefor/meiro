package com.muratkirazkaya.meiro.musicbrainz

import com.muratkirazkaya.meiro.data.Clock
import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Match
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.Kind
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.playback.clockText
import com.muratkirazkaya.meiro.sync.ArtistInfo
import com.muratkirazkaya.meiro.sync.Links
import com.muratkirazkaya.meiro.sync.LinksStore
import com.muratkirazkaya.meiro.sync.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A MusicBrainz record a link can be made to (`linkCandidate`); [detail] is the line under its name. */
data class LinkCandidate(
    val id: String, val name: String, val detail: String, val similarity: Int,
    val listeners: Long = 0, val artistId: String = "",
)

/** What a search for a song's recording is made from (`songQuery`). */
data class SongQuery(val title: String, val artist: String, val durationMs: Long)

/** The pure rules of `mblink.go`, testable without a network. */
object LinkRules {
    /** `linkResultsShown`: how many results the sheet lists. */
    const val RESULTS_SHOWN = 6

    /** `linkTimeout`: one search for a link. */
    const val TIMEOUT_MS = 30_000L

    /** `queryOf`: the song's title, the artist its row names and its length. */
    fun queryOf(item: QItem) = SongQuery(item.title, item.artist, item.durationMs())

    /**
     * `songRefs`: the names a song goes by in the link store, one for each provider's copy it is known by (a SoundCloud
     * copy by its page URL), then the one it plays from.
     */
    fun songRefs(item: QItem): List<Pair<String, String>> {
        val refs = ArrayList<Pair<String, String>>()
        fun add(source: String, id: String) {
            if (id.isEmpty()) return
            val p = source.ifEmpty { Source.YTM }
            if (refs.none { it.first == p && it.second == id }) refs += p to id
        }
        for ((src, id) in item.refs) add(src, id)
        add(item.provider(), item.key)
        return refs
    }

    /**
     * `certainRecording`: the recording a song is, only when it is sure: the song's length is known and its artist folds
     * to something; a recording counts when its length is known and `Match` says matched (titles and artists the same
     * once folded, lengths within 4 s); recordings that count but name different first artists make it unsure; among
     * those that count, the highest MusicBrainz score.
     */
    fun certainRecording(q: SongQuery, recordings: List<RecordingMatch>): RecordingMatch? {
        if (q.durationMs <= 0 || Match.normalize(q.artist).isEmpty()) return null
        var best: RecordingMatch? = null
        for (r in recordings) {
            if (r.lengthMs <= 0) continue
            val same = Match.match(Match.Track(q.title, q.artist, q.durationMs), Match.Track(r.title, r.artist, r.lengthMs.toLong())) == Match.Status.MATCHED
            if (!same) continue
            val b = best
            if (b != null && r.artistId != b.artistId) return null
            if (b == null || r.score > b.score) best = r
        }
        return best
    }

    /**
     * `searchSongLinks`: the recordings [title] names with the song's artist (again without the artist when that finds
     * nothing), those that fit best first (by tens of MusicBrainz's score) and, among those that fit alike, the nearest
     * the song's length. Lines are the mock's "Kayıt · artist · 3:40 · 2019".
     */
    suspend fun searchSongLinks(client: MetadataClient, title: String, q: SongQuery): List<LinkCandidate> {
        var recordings = client.searchRecordings(title, q.artist)
        if (recordings.isEmpty() && q.artist.isNotBlank()) recordings = client.searchRecordings(title, "")
        val lengths = recordings.associate { it.id to it.lengthMs }
        val found = recordings.map { r ->
            val detail = listOf("Kayıt", r.artist, if (r.lengthMs > 0) clockText(r.lengthMs.toLong()) else "", r.year).filter { it.isNotEmpty() }.joinToString(" · ")
            LinkCandidate(r.id, r.title, detail, r.score, artistId = r.artistId)
        }
        fun distance(c: LinkCandidate): Long {
            val l = lengths[c.id] ?: 0
            return if (l > 0 && q.durationMs > 0) kotlin.math.abs(l - q.durationMs) else 1L shl 40
        }
        return found.sortedWith(compareByDescending<LinkCandidate> { it.similarity / 10 }.thenBy { distance(it) })
    }

    /**
     * `searchArtistLinks`: the artists [query] names, those that fit best first and, among those that fit alike, the most
     * listened to on ListenBrainz (a failure to read that keeps MusicBrainz's order). Lines: "Sanatçı · country ·
     * disambiguation · N dinleyici".
     */
    suspend fun searchArtistLinks(client: MetadataClient, query: String): List<LinkCandidate> {
        val artists = client.searchArtists(query)
        val pop = try { client.artistPopularity(artists.map { it.id }) } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
        val found = artists.map { a ->
            val listeners = pop[a.id]?.listeners ?: 0
            val detail = listOf("Sanatçı", a.country, a.disambiguation, if (listeners > 0) "$listeners dinleyici" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            LinkCandidate(a.id, a.name, detail, a.score, listeners)
        }
        return found.sortedWith(compareByDescending<LinkCandidate> { it.similarity / 10 }.thenByDescending { it.listeners })
    }
}

/**
 * The phone's `mblink.go`: what is known of a song's and an artist's MusicBrainz link, the searches the link sheet
 * makes, the listener's answer, and auto-linking (a song tied to its recording only when the match is certain). Every
 * change is kept in [links] under each name the song goes by, mirrored into the lists that hold it, and handed to
 * [queue] (the sync's `/sync/links`). [version] grows with every change, for the screens that show link state.
 */
class Linker(
    private val client: MetadataClient,
    private val links: LinksStore,
    private val libraries: LibraryStore,
    private val queue: (Links) -> Unit,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val log: (String) -> Unit = {},
) {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version
    private val batches = Mutex()

    /** `linkOf`: the first of the song's names the store has a record for. */
    fun linkOf(item: QItem): Song? = LinkRules.songRefs(item).firstNotNullOfOrNull { (p, id) -> links.song(p, id) }

    /** The song's state: the store's, else what a list holding it says, else "". */
    fun stateOf(item: QItem): String {
        linkOf(item)?.state?.takeIf { it.isNotEmpty() }?.let { return it }
        val refs = LinkRules.songRefs(item)
        return libraries.list().asSequence().flatMap { it.items.asSequence() }
            .firstOrNull { it.activeRef() in refs }?.state.orEmpty()
    }

    fun artistState(name: String): String = links.artist(name)?.state.orEmpty()

    /** `importListLinks`: links the lists already hold (matched or other) go into the store unless it knows the song. */
    fun importListLinks() {
        var n = 0
        for (lib in libraries.list()) for (item in lib.items) {
            if (item.state != LinkState.MATCHED && item.state != LinkState.OTHER) continue
            val provider = item.source.ifEmpty { Source.YTM }
            if (links.song(provider, item.key) != null) continue
            links.setSong(Song(provider, item.key, item.state, item.mbid, item.artist, item.artistMbid, item.artistState, updatedAt = item.addedAt))
            n++
        }
        if (n > 0) { log("link: took $n links from the lists"); _version.value += 1 }
    }

    /**
     * `recordSong`: the song's link under every name it goes by, keeping what was known of the artist unless [artist]
     * says otherwise, checked now; the artist too, then the lists that hold the song; all of it queued for the server.
     */
    fun recordSong(item: QItem, state: String, mbid: String, artist: ArtistInfo?) {
        val name = LinkRules.queryOf(item).artist
        val prior = linkOf(item)
        val now = clock.now()
        val out = ArrayList<Song>()
        for ((p, id) in LinkRules.songRefs(item)) {
            var song = Song(
                provider = p, id = id, state = state, mbid = if (state == LinkState.MATCHED) mbid else "", artist = name,
                artistMbid = prior?.artistMbid.orEmpty(), artistState = prior?.artistState.orEmpty(), checkedAt = now,
            )
            if (artist != null) song = song.copy(artistMbid = artist.mbid, artistState = artist.state)
            out += links.setSong(song)
        }
        var artistOut: ArtistInfo? = null
        if (artist != null) {
            artistOut = links.setArtist(artist)
            libraries.setArtistLink(artist.name, artist.state, artist.mbid)
        }
        mirrorToLists(item, state, mbid)
        queue(Links(songs = out.map(LinksStore::songLinkOut), artists = listOfNotNull(artistOut).map(LinksStore::artistLinkOut)))
        _version.value += 1
        log("link: ${item.provider()}:${item.key} \"${item.title}\" -> $state${if (state == LinkState.MATCHED) " $mbid" else ""} (${out.size} names)")
    }

    /** `recordArtist`: what an artist is, in the store and in every list track that credits it; queued. */
    fun recordArtist(artist: ArtistInfo) {
        if (LinksStore.artistKey(artist.name).isEmpty()) return
        val saved = links.setArtist(artist)
        libraries.setArtistLink(artist.name, artist.state, artist.mbid)
        queue(Links(artists = listOf(LinksStore.artistLinkOut(saved))))
        _version.value += 1
        log("link: artist \"${artist.name}\" -> ${artist.state}${if (artist.state == LinkState.MATCHED) " ${artist.mbid}" else ""}")
    }

    /** `mirrorToLists`: every list that holds the song (by any of its names) takes its link. */
    private fun mirrorToLists(item: QItem, state: String, mbid: String) {
        val refs = LinkRules.songRefs(item)
        for (lib in libraries.list()) for (held in lib.items) {
            if (held.key == item.key || held.activeRef() in refs) libraries.setLink(lib.id, held.key, state, mbid)
        }
    }

    /** `applyLink` for a song: the chosen recording (its first artist linked too), or "other" with no record. */
    fun applySong(item: QItem, state: String, chosen: LinkCandidate?) {
        val q = LinkRules.queryOf(item)
        val artist = if (state == LinkState.MATCHED && chosen != null && chosen.artistId.isNotEmpty()) ArtistInfo(q.artist, LinkState.MATCHED, chosen.artistId) else null
        recordSong(item, state, chosen?.id.orEmpty(), artist)
    }

    /** `applyLink` for an artist. */
    fun applyArtist(name: String, state: String, chosen: LinkCandidate?) =
        recordArtist(ArtistInfo(name, state, if (state == LinkState.MATCHED) chosen?.id.orEmpty() else ""))

    suspend fun searchSong(item: QItem, title: String): List<LinkCandidate> = busyRetry { LinkRules.searchSongLinks(client, title, LinkRules.queryOf(item)) }

    suspend fun searchArtist(query: String): List<LinkCandidate> = busyRetry { LinkRules.searchArtistLinks(client, query) }

    /**
     * The sheet's search asks again (at most [BUSY_TRIES] times in all) when MusicBrainz says it is busy (503/429): the
     * queue then waits the pause the answer asked for (Retry-After, 1 s when MusicBrainz is busy for everyone) before
     * the next request. The desktop's dialog shows the error at once; auto-linking still stops at the first refusal.
     */
    private suspend fun <T> busyRetry(block: suspend () -> T): T {
        var tries = 0
        while (true) {
            try { return block() } catch (e: RateLimitedException) {
                if (++tries >= BUSY_TRIES) throw e
                log("link: MusicBrainz busy (${e.status}), asking again")
            }
        }
    }

    companion object {
        const val BUSY_TRIES = 3
    }

    /** `needsAutoLink`: a track with a title that is neither linked nor marked "other", and not tried lately. */
    fun needsAutoLink(item: QItem, ignoreRecent: Boolean = false): Boolean {
        if (item.kind != Kind.Track || item.title.isBlank()) return false
        val song = linkOf(item) ?: return stateOf(item).let { it != LinkState.MATCHED && it != LinkState.OTHER }
        if (song.state == LinkState.MATCHED || song.state == LinkState.OTHER) return false
        return ignoreRecent || !links.fresh(song.checkedAt)
    }

    /**
     * `autoLinkAll`: each song that needs it, one after another in the background (MusicBrainz allows a request a
     * second); a certain match links the song and its artist, anything else only notes that it was tried. A refusal for
     * asking too fast stops the batch. [ignoreRecent] ("Hepsini MusicBrainz'e bağla") also asks for songs tried in the
     * last 30 days. Returns how many songs it will ask about.
     */
    fun autoLinkAll(items: List<QItem>, ignoreRecent: Boolean = false, done: (linked: Int, asked: Int) -> Unit = { _, _ -> }): Int {
        val todo = items.filter { needsAutoLink(it, ignoreRecent) }.distinctBy { it.provider() to it.key }
        if (todo.isEmpty()) return 0
        scope.launch {
            batches.withLock {
                var linked = 0
                var asked = 0
                for (item in todo) {
                    if (!needsAutoLink(item, ignoreRecent)) continue // linked meanwhile
                    val q = LinkRules.queryOf(item)
                    val recordings = try {
                        kotlinx.coroutines.withTimeout(LinkRules.TIMEOUT_MS) { client.searchRecordings(q.title, q.artist) }
                    } catch (e: RateLimitedException) {
                        log("link: auto-link stopped, MusicBrainz asked to slow down (${e.status})"); break
                    } catch (e: CancellationException) {
                        if (e is kotlinx.coroutines.TimeoutCancellationException) { log("link: auto-link of ${item.key} timed out"); continue }
                        throw e
                    } catch (e: Exception) {
                        log("link: auto-link of ${item.key} failed: $e"); continue
                    }
                    asked++
                    val r = LinkRules.certainRecording(q, recordings)
                    if (r != null) linked++
                    applyAutoLink(item, r)
                }
                log("link: auto-link batch done, $linked of $asked linked")
                done(linked, asked)
            }
        }
        return todo.size
    }

    /** `applyAutoLink`: the recording and its artist when sure; otherwise only that it was tried. */
    fun applyAutoLink(item: QItem, recording: RecordingMatch?) {
        if (recording == null) {
            val prior = linkOf(item)
            recordSong(item, prior?.state?.ifEmpty { null } ?: LinkState.UNKNOWN, prior?.mbid.orEmpty(), null)
            return
        }
        val artist = if (recording.artistId.isNotEmpty()) ArtistInfo(LinkRules.queryOf(item).artist, LinkState.MATCHED, recording.artistId) else null
        recordSong(item, LinkState.MATCHED, recording.id, artist)
    }
}
