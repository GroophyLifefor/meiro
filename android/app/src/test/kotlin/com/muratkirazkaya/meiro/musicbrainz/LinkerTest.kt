package com.muratkirazkaya.meiro.musicbrainz

import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.ManualClock
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.data.SourceRef
import com.muratkirazkaya.meiro.playback.Kind
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.sync.Links
import com.muratkirazkaya.meiro.sync.LinksStore
import com.muratkirazkaya.meiro.sync.Song
import com.muratkirazkaya.meiro.ui.LinkMapping
import com.muratkirazkaya.meiro.ui.LinkTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** MusicBrainz answers kept in memory, by "title|artist"; [limitedAfter] answers 503 from that request on. */
class FakeMb(
    private val recordings: Map<String, List<RecordingMatch>> = emptyMap(),
    private val artists: List<MbArtist> = emptyList(),
    private val popularity: Map<String, Popularity>? = emptyMap(),
    private val limitedAfter: Int = Int.MAX_VALUE,
    /** The first this many requests are refused as busy. */
    private var busyFirst: Int = 0,
) : MetadataClient {
    val asked = mutableListOf<String>()
    override suspend fun searchRecordings(title: String, artist: String): List<RecordingMatch> {
        if (busyFirst > 0) { busyFirst--; throw RateLimitedException(503, 1000) }
        if (asked.size >= limitedAfter) throw RateLimitedException(503, 0)
        asked += "$title|$artist"
        return recordings["$title|$artist"].orEmpty()
    }
    override suspend fun searchArtists(query: String): List<MbArtist> { asked += "artist:$query"; return artists }
    override suspend fun artistPopularity(mbids: List<String>): Map<String, Popularity> = popularity ?: throw java.io.IOException("down")
}

/** Ports of `mblink_test.go` and the rules of PORTING.md 4.5, on the phone's queue tracks and lists. */
class LinkerTest {
    private val lucky = QItem(Source.YTM, "vid-lucky", "Get Lucky", "Daft Punk", "4:08")
    private val same = RecordingMatch("r1", "Get Lucky", "Daft Punk", "a1", 247_000, 100)

    @Test fun certainRecordingOnlyWhenSure() {
        val q = SongQuery("Get Lucky", "Daft Punk", 248_000)
        assertEquals("r1", LinkRules.certainRecording(q, listOf(same))?.id)
        assertNull("another length", LinkRules.certainRecording(q, listOf(same.copy(id = "r2", lengthMs = 300_000))))
        assertNull("no length", LinkRules.certainRecording(q, listOf(same.copy(lengthMs = 0))))
        assertNull("song without length", LinkRules.certainRecording(q.copy(durationMs = 0), listOf(same)))
        assertNull("no artist", LinkRules.certainRecording(q.copy(artist = " ()"), listOf(same)))
        assertNull("two artists", LinkRules.certainRecording(q, listOf(same, same.copy(id = "r3", artistId = "a9"))))
        assertEquals("the better fit", "r1", LinkRules.certainRecording(q, listOf(same.copy(id = "r4", score = 80), same))?.id)
    }

    @Test fun certainRecordingOnTheRecordedAnswer() {
        val lucky = MusicBrainzClient.parseRecordings(fixture("mb_recording_get_lucky.json"))
        // A 4:08 "Get Lucky": the nearest recordings are 4:02 and 3:57, more than 4 s away.
        assertNull(LinkRules.certainRecording(SongQuery("Get Lucky", "Daft Punk", 248_000), lucky))
        // A 4:02 one is the 242 213 ms recording.
        assertEquals(242_213, LinkRules.certainRecording(SongQuery("Get Lucky", "Daft Punk", 242_000), lucky)?.lengthMs)
        val crush = MusicBrainzClient.parseRecordings(fixture("mb_recording_instant_crush.json"))
        // YouTube Music credits "Daft Punk"; MusicBrainz "Daft Punk feat. Julian Casablancas": not the same once folded.
        assertNull(LinkRules.certainRecording(SongQuery("Instant Crush", "Daft Punk", 337_000), crush))
        // With the same credit, three recordings within 4 s by one artist: the first of the best score (99) is chosen.
        val r = LinkRules.certainRecording(SongQuery("Instant Crush", "Daft Punk feat. Julian Casablancas", 337_000), crush)!!
        assertEquals(99, r.score); assertEquals(337_632, r.lengthMs)
        assertEquals(emptyList<RecordingMatch>(), MusicBrainzClient.parseRecordings(fixture("mb_recording_none.json")))
    }

    @Test fun songRefsNameEveryCopyThenTheOneItPlaysFrom() {
        val both = QItem(Source.YTM, "v1", "T", "A", refs = linkedMapOf(Source.YTM to "v1", Source.SOUNDCLOUD to "https://soundcloud.com/a/t"))
        assertEquals(listOf(Source.YTM to "v1", Source.SOUNDCLOUD to "https://soundcloud.com/a/t"), LinkRules.songRefs(both))
        assertEquals(listOf(Source.YTM to "v2"), LinkRules.songRefs(QItem(Source.YTM, "v2", "T")))
    }

    @Test fun songSearchRanksByFitThenLengthAndRetriesWithoutTheArtist() = runBlocking {
        val mb = FakeMb(
            mapOf(
                "Gece Yarısı|" to listOf(
                    RecordingMatch("far", "Gece Yarısı", "Ece Kaya", "e", 242_000, 61),
                    RecordingMatch("near", "Gece Yarısı", "Anıl Kaya", "a", 218_000, 93, "2017"),
                    RecordingMatch("live", "Gece Yarısı (live)", "Anıl Kaya", "a", 220_000, 97, "2019"),
                    RecordingMatch("noLen", "Gece Yarısı", "Anıl Kaya", "a", 0, 95),
                ),
            ),
        )
        val found = LinkRules.searchSongLinks(mb, "Gece Yarısı", SongQuery("Gece Yarısı", "Anıl Kaya", 221_000))
        assertEquals(listOf("Gece Yarısı|Anıl Kaya", "Gece Yarısı|"), mb.asked)
        // 97 and 95 and 93 are all in the 90s: nearest length first, the one without a length last of them.
        assertEquals(listOf("live", "near", "noLen", "far"), found.map { it.id })
        assertEquals("Kayıt · Anıl Kaya · 3:40 · 2019", found[0].detail)
        assertEquals("Kayıt · Anıl Kaya", found[2].detail)
    }

    @Test fun artistSearchRanksByFitThenListenersAndKeepsOrderWithoutPopularity() = runBlocking {
        val artists = listOf(MbArtist("x", "Daft Punk", "", "tribute", 100), MbArtist("dp", "Daft Punk", "FR", "", 100), MbArtist("low", "Daft", "", "", 70))
        val ranked = LinkRules.searchArtistLinks(FakeMb(artists = artists, popularity = mapOf("dp" to Popularity("dp", 9, 5000), "x" to Popularity("x", 1, 3))), "Daft Punk")
        assertEquals(listOf("dp", "x", "low"), ranked.map { it.id })
        assertEquals("Sanatçı · FR · 5000 dinleyici", ranked[0].detail)
        val unranked = LinkRules.searchArtistLinks(FakeMb(artists = artists, popularity = null), "Daft Punk")
        assertEquals(listOf("x", "dp", "low"), unranked.map { it.id })
    }

    @Test fun artistSearchOnTheRecordedAnswers() = runBlocking {
        val artists = MusicBrainzClient.parseArtists(fixture("mb_artist_daft_punk.json"))
        val pop = MusicBrainzClient.parsePopularity(fixture("lb_popularity_daft_punk.json")).associateBy { it.mbid }
        val ranked = LinkRules.searchArtistLinks(FakeMb(artists = artists, popularity = pop), "Daft Punk")
        assertEquals("Daft Punk", ranked[0].name)
        assertEquals("056e4f3e-d505-4dad-8ec1-d04f521cbb56", ranked[0].id)
        assertTrue(ranked[0].detail, ranked[0].detail.startsWith("Sanatçı · FR"))
    }

    private class Rig(mb: MetadataClient) {
        val clock = ManualClock()
        val links = LinksStore(clock = clock)
        val libs = LibraryStore(clock = clock)
        val queued = mutableListOf<Links>()
        val linker = Linker(mb, links, libs, { queued += it }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock)
    }

    @Test fun choosingARecordingLinksTheSongUnderEveryNameItsArtistAndTheLists() {
        val r = Rig(FakeMb())
        val lib = r.libs.create("Night drive")
        r.libs.addItem(lib.id, Item(Source.YTM, "v1", title = "Get Lucky", artist = "Daft Punk", durationMs = 248_000, state = LinkState.UNKNOWN,
            sources = listOf(SourceRef(Source.YTM, "v1"), SourceRef(Source.SOUNDCLOUD, url = "https://soundcloud.com/dp/gl"))))
        r.libs.addItem(lib.id, Item(Source.YTM, "v9", title = "Other", artist = "Daft Punk", state = LinkState.UNKNOWN))
        val item = QItem(Source.YTM, "v1", "Get Lucky", "Daft Punk", "4:08", refs = mapOf(Source.YTM to "v1", Source.SOUNDCLOUD to "https://soundcloud.com/dp/gl"))
        r.linker.applySong(item, LinkState.MATCHED, LinkCandidate("rec-1", "Get Lucky", "", 100, artistId = "art-1"))

        for ((p, id) in listOf(Source.YTM to "v1", Source.SOUNDCLOUD to "https://soundcloud.com/dp/gl")) {
            val s = r.links.song(p, id)!!
            assertEquals(LinkState.MATCHED, s.state); assertEquals("rec-1", s.mbid); assertEquals("Daft Punk", s.artist)
            assertEquals("art-1", s.artistMbid); assertEquals(LinkState.MATCHED, s.artistState); assertEquals(r.clock.t, s.checkedAt)
        }
        assertEquals("art-1", r.links.artist("daft punk")?.mbid)
        val items = r.libs.get(lib.id)!!.items
        assertEquals(LinkState.MATCHED, items[0].state); assertEquals("rec-1", items[0].mbid)
        // The other song by the artist got the artist's link, not the recording.
        assertEquals(LinkState.UNKNOWN, items[1].state); assertEquals("art-1", items[1].artistMbid)
        // One push: both names of the song and the artist, in the server's shape.
        assertEquals(1, r.queued.size)
        assertEquals(2, r.queued[0].songs.size); assertEquals("soundcloud", r.queued[0].songs[1].provider)
        assertEquals("daft punk", r.queued[0].artists.single().artistKey)
        assertEquals(1, r.linker.version.value)
        assertEquals(LinkState.MATCHED, r.linker.stateOf(item))
    }

    @Test fun markingOtherKeepsNoRecordAndAnArtistLinksByName() {
        val r = Rig(FakeMb())
        r.linker.applySong(lucky, LinkState.OTHER, null)
        val s = r.links.song(Source.YTM, "vid-lucky")!!
        assertEquals(LinkState.OTHER, s.state); assertEquals("", s.mbid)
        r.linker.applyArtist("Daft Punk", LinkState.MATCHED, LinkCandidate("dp", "Daft Punk", "", 100))
        assertEquals("dp", r.links.artist("Daft Punk")!!.mbid)
        assertEquals(LinkState.MATCHED, r.linker.artistState("DAFT PUNK"))
        r.linker.applyArtist("Daft Punk", LinkState.OTHER, LinkCandidate("dp", "Daft Punk", "", 100))
        assertEquals("", r.links.artist("Daft Punk")!!.mbid)
    }

    @Test fun autoLinkLinksACertainSongAndOnlyNotesAnUnsureOne() {
        val mb = FakeMb(mapOf("Get Lucky|Daft Punk" to listOf(same)))
        val r = Rig(mb)
        val unsure = QItem(Source.YTM, "v2", "Nothing", "Nobody", "3:00")
        val done = CountDownLatch(1)
        assertEquals(2, r.linker.autoLinkAll(listOf(lucky, unsure)) { linked, asked -> assertEquals(1, linked); assertEquals(2, asked); done.countDown() })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("r1", r.links.song(Source.YTM, "vid-lucky")!!.mbid)
        assertEquals("a1", r.links.artist("Daft Punk")!!.mbid)
        val noted = r.links.song(Source.YTM, "v2")!!
        assertEquals(LinkState.UNKNOWN, noted.state); assertEquals(r.clock.t, noted.checkedAt)
        // Asked within 30 days: not asked again, unless "Hepsini" asks for it; a linked song never.
        assertFalse(r.linker.needsAutoLink(unsure)); assertTrue(r.linker.needsAutoLink(unsure, ignoreRecent = true))
        assertFalse(r.linker.needsAutoLink(lucky, ignoreRecent = true))
        r.clock.t = r.clock.t.plus(Duration.ofDays(31))
        assertTrue(r.linker.needsAutoLink(unsure))
        // Videos and empty titles are never asked about.
        assertFalse(r.linker.needsAutoLink(unsure.copy(key = "v3", kind = Kind.Video)))
        assertFalse(r.linker.needsAutoLink(unsure.copy(key = "v4", title = " ")))
    }

    @Test fun aRefusalForAskingTooFastStopsTheBatch() {
        val mb = FakeMb(limitedAfter = 1)
        val r = Rig(mb)
        val done = CountDownLatch(1)
        val items = (1..4).map { QItem(Source.YTM, "v$it", "Song $it", "A", "3:00") }
        r.linker.autoLinkAll(items) { _, asked -> assertEquals(1, asked); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, mb.asked.size)
        assertTrue(r.links.song(Source.YTM, "v1") != null)
        assertNull(r.links.song(Source.YTM, "v2"))
    }

    @Test fun theSheetsSearchAsksAgainWhileMusicBrainzIsBusy() = runBlocking {
        val ok = Rig(FakeMb(mapOf("Get Lucky|Daft Punk" to listOf(same)), busyFirst = 2))
        assertEquals(listOf("r1"), ok.linker.searchSong(lucky, "Get Lucky").map { it.id })
        val busy = Rig(FakeMb(busyFirst = 3))
        try { busy.linker.searchSong(lucky, "Get Lucky"); org.junit.Assert.fail() } catch (e: RateLimitedException) { assertEquals(503, e.status) }
    }

    @Test fun listLinksAreImportedIntoTheStore() {
        val r = Rig(FakeMb())
        val lib = r.libs.create("L")
        r.libs.addItem(lib.id, Item(Source.YTM, "m", title = "M", state = LinkState.MATCHED, mbid = "rec"))
        r.libs.addItem(lib.id, Item(Source.YTM, "u", title = "U", state = LinkState.UNKNOWN))
        r.links.setSong(Song(Source.YTM, "known", LinkState.OTHER))
        r.linker.importListLinks()
        assertEquals("rec", r.links.song(Source.YTM, "m")!!.mbid)
        assertNull(r.links.song(Source.YTM, "u"))
    }

    @Test fun sheetWordsAndQueries() {
        val t = LinkTarget.song(QItem(Source.SOUNDCLOUD, "https://soundcloud.com/x", "Gece Yarısı (canlı)", "Anıl Kaya", "3:41"))
        assertEquals("Gece Yarısı (canlı) Anıl Kaya", LinkMapping.initialQuery(t))
        val ui = LinkMapping.ui(t, "q", listOf(LinkCandidate("id", "Gece Yarısı (live)", "Kayıt · Anıl Kaya · 3:40 · 2019", 97)), false, 0)
        assertEquals("\"Gece Yarısı (canlı)\" · Anıl Kaya. Popülerlik ve benzerliğe göre sıralı, doğru olanı seç.", ui.note)
        assertEquals("MusicBrainz kaydı", ui.title); assertEquals("%97", ui.rows[0].percent)
        assertTrue(LinkMapping.ui(t, "q", null, false, 0).loading)
        assertEquals(LinkMapping.NOTHING_FOUND, LinkMapping.ui(t, "q", emptyList(), false, 0).message)
        assertEquals(LinkMapping.UNAVAILABLE, LinkMapping.ui(t, "q", emptyList(), true, 0).message)
        // The first text searches the song's title with its artist; anything typed is the title searched.
        assertEquals("Gece Yarısı (canlı)", LinkMapping.searchTitle(t.song!!, "Gece Yarısı (canlı) Anıl Kaya", LinkMapping.initialQuery(t)))
        assertEquals("Gece", LinkMapping.searchTitle(t.song!!, " Gece ", LinkMapping.initialQuery(t)))
        assertEquals("MusicBrainz sanatçısı", LinkMapping.ui(LinkTarget.artist("Daft Punk"), "Daft Punk", emptyList(), false, 0).title)
    }

    companion object {
        fun fixture(name: String) = LinkerTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()
    }
}
