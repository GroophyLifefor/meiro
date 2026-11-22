package com.muratkirazkaya.meiro.recommend

import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.search.SoundCloudSearch
import com.muratkirazkaya.meiro.sync.Ids
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.YtmBrowse
import com.muratkirazkaya.meiro.youtube.YtmRelated
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private fun fixture(name: String) = DailyMixTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

/**
 * The Daily mix's gathering against real recorded answers (no network): YouTube Music's `next` and the related page it
 * names (`tools/record-ytm-fixtures.sh related`), and a real SoundCloud `/recommended` page read by yt-dlp. Ports of
 * the desktop's recommendations_test.go where they do not need the UI.
 */
class DailyMixTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Istanbul")
    private val now = Instant.parse("2026-11-22T18:00:00Z")

    private val related = YtmRelated.tracks(YtmBrowse.parse(fixture("ytm_browse_related.json")))

    /** Answers every related request with the recorded page and counts what it was asked. */
    private inner class Fake(val sc: List<MusicItem> = emptyList()) : RelatedSources {
        val asked = mutableListOf<String>()
        override suspend fun youtube(videoId: String): List<MusicItem> { asked += "ytm|$videoId"; return related }
        override suspend fun soundCloud(pageUrl: String): List<MusicItem> { asked += "sc|$pageUrl"; return sc }
    }

    private fun play(id: String, artist: String, daysAgo: Long, source: String = Source.YTM, title: String = "Seed song $artist") = HistoryEntry(
        videoId = id, title = title, artist = artist, source = source, playedAt = now.minus(Duration.ofDays(daysAgo)),
        durationMs = 200_000, playedMs = 200_000, completed = true, eventId = "e-$id-$daysAgo",
    )

    @Test fun nextNamesTheRelatedTabAndItsPageHoldsTracks() {
        assertEquals("MPTRt_7ltM34kr0mH-10", YtmRelated.tabBrowseId(fixture("ytm_next_related_seed.json"), YtmRelated.RELATED_PAGE))
        assertEquals("", YtmRelated.tabBrowseId(fixture("ytm_next_related_seed.json"), "MUSIC_PAGE_TYPE_NOPE"))
        // The page lists "You might also like" and the tracks of other shelves; the desktop counts the page's items and
        // every section's (`relatedTracks`), so each track is there twice, as on the desktop.
        assertTrue(related.size >= 40)
        assertTrue(related.all { it.isTrack() && it.videoId.isNotEmpty() && it.title.isNotEmpty() })
        assertEquals("Infinity Repeating (2013 Demo) [feat. Julian Casablancas+The Voidz]", related[0].title)
        assertEquals("Daft Punk", related[0].artist)
        assertEquals(related.size / 2, related.distinctBy { it.videoId }.size)
    }

    @Test fun soundCloudRelatedPagesNameNoArtistSoTheyGiveNoCandidates() = runBlocking {
        val sc = SoundCloudSearch.parse(fixture("sc_related_recommended.json"))
        assertEquals(50, sc.size)
        assertEquals("https://soundcloud.com/ralphlawson/daft-punk-feb-97-dj-mix/recommended", SoundCloudSearch.relatedUrl("https://soundcloud.com/ralphlawson/daft-punk-feb-97-dj-mix/"))
        assertEquals(listOf("--flat-playlist", "--dump-single-json", "--no-warnings", "https://soundcloud.com/a/b/recommended"), SoundCloudSearch.relatedArgs("https://soundcloud.com/a/b/recommended"))
        // yt-dlp's flat /recommended page carries no uploader, so, as on the desktop, none of it can be ranked.
        assertTrue(sc.all { it.artist.isEmpty() && it.title.isNotEmpty() })
        val maker = DailyMixMaker(Fake(sc), RelatedCache(), pause = {})
        val seed = SeedTrack(Entry("x", "X", 1.0), Source.SOUNDCLOUD, "https://soundcloud.com/x/seed")
        assertEquals(0, maker.gather(listOf(seed), now).size)
    }

    @Test fun aRunRanksTheRelatedPageIntoPicksWithReasons() = runBlocking {
        val fake = Fake()
        val pauses = mutableListOf<Long>()
        val maker = DailyMixMaker(fake, RelatedCache(), pause = { pauses += it })
        val history = listOf(play("OWiVJMgms9E", "Daft Punk", 1), play("v2", "Daft Punk", 3), play("v3", "Kavinsky", 2))
        val lists = listOf(Library("l1", "Gece", items = listOf(Item(source = Source.YTM, key = "k1", title = "Nightcall", artist = "Kavinsky", addedAt = now.minus(Duration.ofDays(20))))))
        val items = maker.make({ maker.profile(history, lists, "", now) }, { history }, now, zone)
        // Seeds are the latest song of each top artist, best first: Kavinsky (a play and a saved song) leads Daft Punk
        // (two plays), and Daft Punk's seed is its newest play.
        assertEquals(listOf("ytm|v3", "ytm|OWiVJMgms9E"), fake.asked)
        assertEquals(listOf(DailyMixMaker.PAUSE_YOUTUBE_MS, DailyMixMaker.PAUSE_YOUTUBE_MS), pauses)
        // Both seeds get the same recorded page, whose tracks are by few artists; at most two of an artist in any ten
        // in a row stops the list at 16, short of the 30 asked for (the desktop ranks the same way).
        assertEquals(16, items.size)
        assertEquals(items.size, items.distinctBy { it.key }.size)
        assertTrue(items.all { it.source == Source.YTM && it.key.isNotEmpty() && it.why.isNotEmpty() && it.artist.isNotEmpty() })
        assertTrue(items.all { it.why.startsWith("Because you listen to ") || it.why.startsWith("New to you, like ") })
        // Daft Punk is known, Modjo is not: the reasons say so.
        assertTrue(items.filter { it.artist == "Daft Punk" }.all { it.why.startsWith("Because you listen to ") })
        assertTrue(items.filter { it.artist == "Modjo" }.all { it.why.startsWith("New to you, like ") })
        // At most two of an artist in any ten in a row.
        for (start in 0..items.size - 10) assertTrue(items.subList(start, start + 10).groupingBy { it.artist }.eachCount().values.all { it <= 2 })
    }

    @Test fun relatedAnswersAreKeptForADayOnDisk() = runBlocking {
        val file = File(tmp.root, "cache.json")
        val fake = Fake()
        val history = listOf(play("OWiVJMgms9E", "Daft Punk", 1))
        val first = DailyMixMaker(fake, RelatedCache(file), pause = {})
        first.make({ first.profile(history, emptyList(), "", now) }, { history }, now, zone)
        assertEquals(1, fake.asked.size)
        // Another process the same day reads the file and asks nothing.
        val second = DailyMixMaker(fake, RelatedCache(file), pause = {})
        val again = second.make({ second.profile(history, emptyList(), "", now) }, { history }, now.plus(Duration.ofHours(23)), zone)
        assertEquals(1, fake.asked.size)
        assertTrue(again.isNotEmpty())
        // A day later it asks again.
        second.make({ second.profile(history, emptyList(), "", now) }, { history }, now.plus(Duration.ofHours(24)), zone)
        assertEquals(2, fake.asked.size)
        // An entry older than a week is dropped when the next one is written.
        val c = RelatedCache(file)
        c.put("other", emptyList(), now.plus(Duration.ofDays(9)))
        assertEquals(null, c.get("ytm|OWiVJMgms9E", now.plus(Duration.ofHours(25))))
        assertEquals(listOf("other"), org.json.JSONObject(file.readText()).keys().asSequence().toList())
    }

    @Test fun budgetsBoundTheRequestsOfARun() = runBlocking {
        val fake = Fake()
        val maker = DailyMixMaker(fake, RelatedCache(), pause = {})
        val yt = (1..12).map { SeedTrack(Entry("a$it", "A$it", 1.0), Source.YTM, "v$it") }
        val sc = (1..5).map { SeedTrack(Entry("s$it", "S$it", 1.0), Source.SOUNDCLOUD, "https://soundcloud.com/s/$it") }
        maker.gather(yt + sc, now)
        assertEquals(DailyMixMaker.BUDGET_YOUTUBE, fake.asked.count { it.startsWith("ytm|") })
        assertEquals(DailyMixMaker.BUDGET_SOUNDCLOUD, fake.asked.count { it.startsWith("sc|") })
    }

    @Test fun aFailedRequestIsSkippedAndTheRestGoOn() = runBlocking {
        val sources = object : RelatedSources {
            override suspend fun youtube(videoId: String): List<MusicItem> = if (videoId == "bad") throw java.io.IOException("HTTP 500") else related
            override suspend fun soundCloud(pageUrl: String): List<MusicItem> = emptyList()
        }
        val logs = mutableListOf<String>()
        val maker = DailyMixMaker(sources, RelatedCache(), pause = {}, log = { logs += it })
        val got = maker.gather(listOf(SeedTrack(Entry("a", "A", 1.0), Source.YTM, "bad"), SeedTrack(Entry("b", "B", 1.0), Source.YTM, "good")), now)
        assertEquals(related.size, got.size)
        assertTrue(logs.any { it.contains("HTTP 500") })
    }

    @Test fun nothingWithoutAHistory() = runBlocking {
        val fake = Fake()
        val maker = DailyMixMaker(fake, RelatedCache(), pause = {})
        assertTrue(maker.make({ maker.profile(emptyList(), emptyList(), "", now) }, { emptyList() }, now, zone).isEmpty())
        // Saved songs alone make a profile but no seed (a seed is a played song).
        val lists = listOf(Library("l", "L", items = listOf(Item(source = Source.YTM, key = "k", title = "T", artist = "A", addedAt = now))))
        assertTrue(maker.make({ maker.profile(emptyList(), lists, "", now) }, { emptyList() }, now, zone).isEmpty())
        assertTrue(fake.asked.isEmpty())
    }

    @Test fun theMixIsNotASignal() {
        val maker = DailyMixMaker(Fake(), RelatedCache(), pause = {})
        val mixItem = Item(source = Source.YTM, key = "m", title = "Cloud Song", artist = "X", addedAt = now)
        val libs = listOf(
            Library("local-mix", "Daily mix", items = listOf(mixItem)),
            Library("shared", "Daily mix", serverId = Ids.dailyMixId(), items = listOf(mixItem)),
            Library("mine", "Mine", items = listOf(Item(source = Source.YTM, key = "k", title = "Kept", artist = "Y", addedAt = now))),
        )
        assertEquals(listOf("Kept"), maker.tasteSignals(libs, "local-mix").map { it.title })
    }

    @Test fun durationsParse() {
        assertEquals(248_000L, DailyMixMaker.durationMs("4:08"))
        assertEquals(3_723_000L, DailyMixMaker.durationMs("1:02:03"))
        assertEquals(0L, DailyMixMaker.durationMs(""))
        assertEquals(0L, DailyMixMaker.durationMs("60K views"))
    }
}
