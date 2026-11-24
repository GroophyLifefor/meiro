package com.muratkirazkaya.meiro.musicbrainz

import com.muratkirazkaya.meiro.data.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URI
import java.util.Locale

/**
 * Port of `artists.go`: the MusicBrainz artists a search matched (the search's "Sanatçılar"), and an artist's page
 * (its top recordings, where the first [MB_LINK_LIMIT] of them play, and the artists like it).
 */
object Artists {
    /** `artistTimeout`: the bound on the search's artist part. */
    const val ARTIST_TIMEOUT_MS = 30_000L

    /** `mbLinkLimit`: how many of an artist's top recordings the page resolves links for (one request a second each). */
    const val MB_LINK_LIMIT = 12

    /**
     * MusicBrainz answers "busy" (503, `x-ratelimit-zone: global`) to about half of all requests at times (M6a). A busy
     * answer is asked again, the queue waiting as the answer says, up to this many times in all; the desktop asks once.
     */
    const val BUSY_TRIES = 3

    /** [block], asked again after a refusal for coming too fast, [BUSY_TRIES] times in all. */
    suspend fun <T> busyRetry(block: suspend () -> T): T {
        var n = 1
        while (true) {
            try { return block() } catch (e: RateLimitedException) { if (n >= BUSY_TRIES) throw e; n++ }
        }
    }

    /** `suggestionsShown`: the most MusicBrainz artists a search shows. */
    const val ARTISTS_SHOWN = 5

    /**
     * The page lists this many top recordings: the ones whose links it resolves. ListenBrainz names every recording it
     * knows (1003 for Daft Punk); the desktop lists them all, the rest always greyed out (a deviation, PROGRESS.md M6d).
     */
    const val RECORDINGS_SHOWN = MB_LINK_LIMIT

    /** "Benzer sanatçılar" shows this many of ListenBrainz's similar artists (it names up to 100; the mock shows 2). */
    const val SIMILAR_SHOWN = 10

    /**
     * `loadArtists`' task: the artists [query] names, with their ListenBrainz popularity (a failure to get it keeps
     * MusicBrainz's order), most listened to first.
     */
    suspend fun search(client: MetadataClient, query: String): List<MbArtist> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        var artists = busyRetry { client.searchArtists(q) }
        if (artists.isNotEmpty()) {
            val popular = try { client.artistPopularity(artistIds(artists)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (popular != null) artists = artists.map { a -> popular[a.id]?.let { a.copy(listeners = it.listeners) } ?: a }
        }
        return sortArtists(artists)
    }

    /** `artistIDs`. */
    fun artistIds(artists: List<MbArtist>): List<String> = artists.map { it.id }.filter { it.isNotEmpty() }

    /** `sortArtists`: most listeners first, MusicBrainz's order among equals (a stable sort). */
    fun sortArtists(artists: List<MbArtist>): List<MbArtist> = artists.sortedByDescending { it.listeners }

    /** A place a recording plays: the provider, the key the player takes (video id or page URL), the link it came from. */
    data class Playable(val source: String, val key: String, val link: MbLink)

    /**
     * `playableRecording`: free streaming links first, then all of them in order; the first that is a YouTube (or
     * YouTube Music) watch link plays as YouTube Music, a soundcloud.com link as SoundCloud.
     */
    fun playable(links: List<MbLink>): Playable? {
        val prefer = links.filter { it.type == "free streaming" } + links
        for (l in prefer) {
            youtubeWatchId(l.url)?.let { return Playable(Source.YTM, it, l) }
            if (isSoundCloudUrl(l.url)) return Playable(Source.SOUNDCLOUD, l.url, l)
        }
        return null
    }

    private fun host(raw: String): String? = try { URI(raw).host?.lowercase(Locale.ROOT) } catch (_: Exception) { null }

    /** `youtubeWatchID`: the video id of a youtube.com (any subdomain) or youtu.be link, else null. */
    fun youtubeWatchId(raw: String): String? {
        val uri = try { URI(raw) } catch (_: Exception) { return null }
        val h = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (h != "youtube.com" && !h.endsWith(".youtube.com") && h != "youtu.be") return null
        val id = if (h == "youtu.be") uri.path.orEmpty().trim('/')
        else uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.substring(2)?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
        return id.ifEmpty { null }
    }

    /** `isSoundCloudURL`. */
    fun isSoundCloudUrl(raw: String): Boolean = host(raw)?.let { it == "soundcloud.com" || it.endsWith(".soundcloud.com") } == true

    /** One recording of the page and where it plays ([play] null: nothing found yet, or nothing playable). */
    data class Row(val recording: MbRecording, val play: Playable? = null)

    /** The artist page as it loads (`mbArtistState`, plus what the mock's page shows beside it). */
    data class Page(
        val mbid: String,
        val artist: MbArtist? = null,
        val loading: Boolean = true,
        val error: String = "",
        val rows: List<Row> = emptyList(),
        val similar: List<SimilarArtist> = emptyList(),
    )

    /**
     * `loadMBArtist`: the top recordings first (shown at once, none playable yet), then the artist itself when [known]
     * does not say (country and years for the kind line; not in the desktop) and its similar artists, then the links of
     * the first [MB_LINK_LIMIT] recordings one MusicBrainz request at a time, each row lighting up as its answer comes. A
     * busy answer is asked again ([busyRetry]); a failed link (still busy, a 404, no playable link) leaves that row
     * greyed out. Only a failure to read the
     * recordings is the page's error.
     */
    fun load(client: MetadataClient, mbid: String, known: MbArtist?, log: (String) -> Unit = {}): Flow<Page> = flow {
        var page = Page(mbid, artist = known)
        emit(page)
        val recordings = try {
            client.topRecordings(mbid)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            emit(page.copy(loading = false, error = firstLine(e.message ?: e.javaClass.simpleName))); return@flow
        }
        page = page.copy(loading = false, rows = recordings.take(RECORDINGS_SHOWN).map { Row(it) })
        emit(page)
        if (known == null) {
            val a = try { client.lookupArtist(mbid) } catch (e: CancellationException) { throw e } catch (e: Exception) { log("artist: lookup $mbid failed: ${e.message}"); null }
            if (a != null) { page = page.copy(artist = a); emit(page) }
        }
        val similar = try { client.similarArtists(mbid) } catch (e: CancellationException) { throw e } catch (e: Exception) { log("artist: similar $mbid failed: ${e.message}"); emptyList() }
        if (similar.isNotEmpty()) { page = page.copy(similar = similar.take(SIMILAR_SHOWN)); emit(page) }
        for (i in page.rows.indices) {
            if (i >= MB_LINK_LIMIT) break
            val links = try { busyRetry { client.recordingLinks(page.rows[i].recording.id) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("artist: links of ${page.rows[i].recording.id} failed: ${e.message}"); continue
            }
            val play = playable(links) ?: continue
            page = page.copy(rows = page.rows.toMutableList().also { it[i] = it[i].copy(play = play) })
            emit(page)
        }
        log("artist: $mbid ${page.rows.size} recordings, ${page.rows.count { it.play != null }} playable, ${page.similar.size} similar")
    }

    private fun firstLine(s: String) = s.lineSequence().firstOrNull().orEmpty()

    /** "Fransa" for "FR" (the country's Turkish name), "" for none; an unknown code stays as it is. */
    fun countryName(code: String): String {
        val c = code.trim()
        if (c.isEmpty()) return ""
        val name = Locale("", c.uppercase(Locale.ROOT)).getDisplayCountry(Locale.forLanguageTag("tr"))
        return name.ifEmpty { c }
    }

    /** "1993–2021" from the life-span; "1993–" while it has not ended; "" when MusicBrainz gives no start. */
    fun years(a: MbArtist): String {
        val b = a.begin.take(4).takeIf { it.length == 4 } ?: return ""
        val e = a.end.take(4).takeIf { it.length == 4 }
        return when {
            e != null -> if (e == b) b else "$b–$e"
            a.ended -> b
            else -> "$b–"
        }
    }
}
