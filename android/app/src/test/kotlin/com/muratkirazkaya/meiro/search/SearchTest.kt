package com.muratkirazkaya.meiro.search

import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.ui.SearchMapping
import com.muratkirazkaya.meiro.ui.SearchMapping.toQItem
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.screens.SearchChip
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.Ref
import com.muratkirazkaya.meiro.youtube.SearchResult
import com.muratkirazkaya.meiro.youtube.YtmSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(name: String) = SearchMergeTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

private fun yt(title: String, artist: String, d: String, id: String = title.lowercase().replace(' ', '_')) =
    MusicItem(videoId = id, id = id, title = title, artist = artist, artists = artist, duration = d, subtitle = "Song • $artist")

private fun sc(title: String, artist: String, d: String) =
    MusicItem(source = Source.SOUNDCLOUD, id = title, url = "https://soundcloud.com/x/${title.lowercase().replace(' ', '-')}", title = title, subtitle = artist, artist = artist, artists = artist, duration = d)

class SearchMergeTest {
    @Test fun soundCloudParseFromRealOutput() {
        val items = SoundCloudSearch.parse(fixture("sc_search_daft_punk.json"))
        assertEquals(8, items.size)
        val lucky = items[1]
        assertEquals("Get Lucky [Original Version] - Daft Punk", lucky.title)
        assertEquals("DJ KB", lucky.artist)
        assertEquals("4:06", lucky.duration)
        assertEquals(Source.SOUNDCLOUD, lucky.source)
        assertTrue(lucky.url.startsWith("https://soundcloud.com/"))
        assertTrue(lucky.isTrack())
        assertEquals("1:00:39", items[0].duration)
        assertEquals("0:30", items[2].duration)
        assertTrue(items[2].thumbnail.endsWith("t500x500.jpg"))
        assertEquals(listOf("--flat-playlist", "--dump-single-json", "--no-warnings", "scsearch50:daft punk"), SoundCloudSearch.args("daft punk"))
    }

    @Test fun soundCloudSkipsEntriesWithoutIdOrPage() {
        val items = SoundCloudSearch.parse("""{"entries":[{"title":"nothing"},{"id":"7","url":"https://soundcloud.com/a/b","duration":0}]}""")
        assertEquals(1, items.size)
        assertEquals("https://soundcloud.com/a/b", items[0].url)
        assertEquals("", items[0].duration)
    }

    @Test fun interleaveOneAfterEveryThree() {
        assertEquals(listOf(1, 2, 3, 100, 4, 5, 6, 101, 7, 102, 103), SearchMerge.interleave(listOf(1, 2, 3, 4, 5, 6, 7), listOf(100, 101, 102, 103), 3))
        assertEquals(listOf(1, 2), SearchMerge.interleave(listOf(1, 2), emptyList(), 3))
        assertEquals(listOf(1, 2, 100), SearchMerge.interleave(listOf(1, 2), listOf(100), 3))
    }

    @Test fun mergeAddsSoundCloudRefToTheSameRecordingAndInterleavesTheRest() {
        val ytm = listOf(yt("Get Lucky", "Daft Punk", "4:08"), yt("Instant Crush", "Daft Punk", "5:37"), yt("Within", "Daft Punk", ""), yt("Voyager", "Daft Punk", "3:48"))
        val scs = listOf(sc("Get Lucky", "Daft Punk", "4:10"), sc("Around the World (remix)", "DJ KB", "4:06"), sc("Voyager", "Daft Punk", "3:00"))
        val m = SearchMerge.mergeProviders(ytm, scs)
        assertEquals(listOf("Get Lucky", "Instant Crush", "Within", "Around the World (remix)", "Voyager", "Voyager"), m.map { it.title })
        // Matched within 4 s: one row with both sources; the YTM fields win and the source is left to the default.
        assertEquals(listOf(Source.YTM, Source.SOUNDCLOUD), m[0].sources.map { it.source })
        assertEquals("", m[0].source)
        assertEquals("get_lucky", m[0].videoId)
        // 48 s apart: not the same recording, kept as its own row.
        assertEquals(Source.SOUNDCLOUD, m[5].source)
        assertEquals(ytm, SearchMerge.mergeProviders(ytm, emptyList()))
        assertEquals(scs, SearchMerge.mergeProviders(emptyList(), scs))
    }

    @Test fun addSourceRefIsANoOpForAProviderAlreadyThere() {
        val a = yt("A", "B", "1:00")
        val other = a.copy(id = "x", videoId = "other")
        assertEquals(a, SearchMerge.addSourceRef(a, other))
        val withSc = SearchMerge.addSourceRef(a, sc("A", "B", "1:00"))
        assertEquals(withSc, SearchMerge.addSourceRef(withSc, sc("A", "B", "1:00")))
    }

    @Test fun localFilter() {
        val items = listOf(yt("Nightcall", "Kavinsky", "4:18"), yt("Gece", "Anıl Kaya", "3:41"))
        assertEquals(listOf("Nightcall"), SearchMerge.filterLocal(items, " KAVIN ").map { it.title })
        assertEquals(2, SearchMerge.filterLocal(items, "").size)
    }
}

class RecentSearchTest {
    @Test fun newestFirstCaseInsensitiveRepeatsAtMostEight() {
        var r = emptyList<String>()
        for (q in listOf("a", "b", "c", "A ", "", "  ")) r = SearchController.remember(r, q)
        assertEquals(listOf("A", "c", "b"), r)
        for (i in 1..10) r = SearchController.remember(r, "q$i")
        assertEquals(8, r.size)
        assertEquals("q10", r[0])
    }
}

private class FakeRecent(var saved: List<String> = emptyList()) : RecentStore {
    override fun load() = saved
    override fun save(queries: List<String>) { saved = queries }
}

private class FakeProviders : SearchProviders {
    var ytmAnswer = CompletableDeferred<SearchResult>()
    var scAnswer = CompletableDeferred<List<MusicItem>>()
    val ytmCalls = mutableListOf<Pair<String, String>>()
    var scCalls = 0
    var moreCalls = 0
    var moreAnswer: SearchResult = SearchResult(emptyList())
    override suspend fun ytm(query: String, params: String): SearchResult { ytmCalls += query to params; return ytmAnswer.await() }
    override suspend fun ytmMore(token: String): SearchResult { moreCalls++; return moreAnswer }
    override suspend fun soundCloud(query: String): List<MusicItem> { scCalls++; return scAnswer.await() }
}

/**
 * The controller on a real event loop ([runBlocking]); the provider answers are completed by hand, and the timeout is
 * shortened to [TIMEOUT] ms so the "left out after the timeout" rule runs in real time.
 */
class SearchControllerTest {
    private val p = FakeProviders()
    private val recent = FakeRecent(listOf("eski"))
    private val local = listOf(yt("Nightcall", "Kavinsky", "4:18"), yt("Gece", "Anıl Kaya", "3:41"))
    private lateinit var ctl: SearchController

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        ctl = SearchController(this, p, recent, { local }, timeoutMs = TIMEOUT, scTimeoutMs = TIMEOUT)
        block()
        coroutineContext.cancelChildren()
    }

    /** Lets the launched provider jobs run. */
    private suspend fun runCurrent() = delay(30)
    private suspend fun advanceUntilIdle() = delay(60)

    @Test fun eachProviderShowsAsItArrives() = test {
        assertEquals(listOf("eski"), ctl.state.value.recent)
        ctl.setField("daft punk"); ctl.submit()
        runCurrent()
        assertEquals(listOf("daft punk", "eski"), recent.saved)
        assertEquals("daft punk", ctl.state.value.submitted)
        assertFalse(ctl.state.value.ytm.done || ctl.state.value.sc.done)

        p.scAnswer.complete(listOf(sc("Around the World (remix)", "DJ KB", "4:06")))
        runCurrent()
        assertTrue(ctl.state.value.sc.done); assertFalse(ctl.state.value.ytm.done)
        assertEquals(listOf("Around the World (remix)"), SearchMapping.songs(ctl.state.value).map { it.title })

        p.ytmAnswer.complete(YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json")))
        runCurrent()
        val ui = SearchMapping.toUi(ctl.state.value)
        assertEquals(listOf("Daft Punk", "The Weeknd", "Kavinsky"), ui.artists.map { it.name })
        assertEquals(SearchMapping.ALL_SONGS, ui.songs.size)
        assertEquals("Around the World (remix)", ui.songs[3].title) // interleaved after three YouTube Music rows
        assertEquals(listOf("Random Access Memories", "Discovery", "Homework (25th Anniversary Edition)"), ui.albums.map { it.title })
        assertEquals("Daft Punk · 2013", ui.albums[0].meta)
        assertTrue(ui.lists.map { it.name }.containsAll(listOf("Presenting Daft Punk", "Daft Punk")))
        assertTrue(ui.lists.all { it.meta == "YouTube Music listesi" && it.logo == Provider.Ytm })
        assertNull(ui.note)
    }

    @Test fun timeouts() {
        assertEquals(7_000L, SearchController.SEARCH_TIMEOUT_MS)
        assertEquals(45_000L, SearchController.SOUNDCLOUD_TIMEOUT_MS)
    }

    @Test fun aProviderThatDoesNotAnswerInTimeIsLeftOut() = test {
        ctl.submit("daft punk")
        p.scAnswer.complete(listOf(sc("Starboy", "The Weeknd", "0:30")))
        runCurrent()
        assertFalse(ctl.state.value.ytm.done)
        delay(TIMEOUT + 50)
        assertTrue(ctl.state.value.ytm.failed)
        assertNull(ctl.state.value.local) // SoundCloud answered: no fallback
        val ui = SearchMapping.toUi(ctl.state.value)
        assertEquals(listOf("Starboy"), ui.songs.map { it.title })
        assertTrue(ui.songs[0].preview)
        assertEquals(LinkBadge.Unknown, ui.songs[0].link)
    }

    @Test fun bothFailingFallsBackToThisDevice() = test {
        ctl.submit("kavinsky")
        p.ytmAnswer.completeExceptionally(java.io.IOException("offline"))
        p.scAnswer.completeExceptionally(RuntimeException("yt-dlp failed"))
        advanceUntilIdle()
        val ui = SearchMapping.toUi(ctl.state.value)
        assertEquals(SearchMapping.LOCAL_NOTE, ui.note)
        assertEquals(listOf("Nightcall"), ui.songs.map { it.title })
        assertTrue(ui.artists.isEmpty() && ui.albums.isEmpty())
    }

    /** M5: without a network neither provider is asked; the results come from this device at once. */
    @Test fun offlineGoesStraightToThisDevice() = runBlocking {
        ctl = SearchController(this, p, recent, { local }, online = { false }, timeoutMs = TIMEOUT, scTimeoutMs = TIMEOUT)
        ctl.submit("gece")
        val ui = SearchMapping.toUi(ctl.state.value)
        assertEquals(SearchMapping.LOCAL_NOTE, ui.note)
        assertEquals(listOf("Gece"), ui.songs.map { it.title })
        assertTrue(p.ytmCalls.isEmpty()); assertEquals(0, p.scCalls)
        coroutineContext.cancelChildren()
    }

    @Test fun songsChipSearchesWithItsParamsKeepsSoundCloudAndLoadsMore() = test {
        ctl.submit("daft punk")
        p.ytmAnswer.complete(YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json")))
        p.scAnswer.complete(listOf(sc("Around the World (remix)", "DJ KB", "4:06")))
        advanceUntilIdle()
        p.ytmAnswer = CompletableDeferred()
        ctl.selectChip(SearchChip.Songs)
        runCurrent()
        assertEquals("daft punk" to "EgWKAQIIAWoSEAUQCRADEAoQBBAOEBAQFRAR", p.ytmCalls.last())
        assertEquals(1, p.scCalls) // the same query's SoundCloud answer is kept
        val songs = YtmSearch.parseSearch(fixture("ytm_search_daft_punk_songs.json"))
        p.ytmAnswer.complete(songs)
        advanceUntilIdle()
        var ui = SearchMapping.toUi(ctl.state.value)
        assertEquals(21, ui.songs.size)
        assertTrue(ui.artists.isEmpty() && ui.albums.isEmpty() && ui.lists.isEmpty())
        p.moreAnswer = YtmSearch.parseSearch(fixture("ytm_search_songs_continuation.json"))
        ctl.loadMore(); advanceUntilIdle()
        ui = SearchMapping.toUi(ctl.state.value)
        // 20 more, of which 4 are already on the first page (the same video id is listed once).
        assertEquals(37, ui.songs.size)
        assertEquals(1, p.moreCalls)

        // Albums: no SoundCloud search.
        p.ytmAnswer = CompletableDeferred()
        ctl.selectChip(SearchChip.Albums); runCurrent()
        assertEquals("EgWKAQIYAWoSEAUQCRADEAoQBBAOEBAQFRAR", p.ytmCalls.last().second)
        assertEquals(1, p.scCalls)
        assertTrue(ctl.state.value.sc.done && ctl.state.value.sc.items.isEmpty())
    }

    @Test fun providerChipsHideOneProviderButNeverBoth() = test {
        ctl.submit("daft punk")
        p.ytmAnswer.complete(YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json")))
        p.scAnswer.complete(listOf(sc("Around the World (remix)", "DJ KB", "4:06")))
        advanceUntilIdle()
        ctl.toggleProvider(Provider.Ytm)
        var ui = SearchMapping.toUi(ctl.state.value)
        assertFalse(ui.ytmOn)
        assertEquals(listOf("Around the World (remix)"), ui.songs.map { it.title })
        assertTrue(ui.artists.isEmpty())
        ctl.toggleProvider(Provider.Sc) // the last one stays on
        assertTrue(ctl.state.value.scOn)
        ctl.toggleProvider(Provider.Ytm); ctl.toggleProvider(Provider.Sc)
        ui = SearchMapping.toUi(ctl.state.value)
        assertTrue(ui.songs.none { it.sc && !it.ytm })
    }

    @Test fun recentForgetClearAndClearField() = test {
        ctl.submit("a"); ctl.submit("b")
        assertEquals(listOf("b", "a", "eski"), ctl.state.value.recent)
        ctl.forget("a")
        assertEquals(listOf("b", "eski"), recent.saved)
        ctl.clear()
        assertEquals("", ctl.state.value.submitted)
        assertFalse(SearchMapping.toUi(ctl.state.value).results)
        ctl.clearRecent()
        assertEquals(emptyList<String>(), recent.saved)
        ctl.submit("   ")
        assertEquals("", ctl.state.value.submitted)
    }

    @Test fun aStaleAnswerIsDropped() = test {
        ctl.submit("first")
        val old = p.ytmAnswer
        p.ytmAnswer = CompletableDeferred()
        ctl.submit("second")
        old.complete(SearchResult(listOf(yt("Old", "X", "1:00"))))
        runCurrent()
        assertFalse(ctl.state.value.ytm.done)
    }

    /** M6c, the mock's `error`: YouTube Music failed, SoundCloud answered; "Tekrar dene" asks YouTube Music again. */
    @Test fun aFailedYouTubeMusicShowsTheErrorAndTekrarDeneAsksAgain() = runBlocking {
        ctl = SearchController(this, p, recent, { local }, timeoutMs = TIMEOUT, scTimeoutMs = TIMEOUT, retryMs = 60_000)
        ctl.submit("hidra")
        p.ytmAnswer.completeExceptionally(java.io.IOException("Unable to resolve host music.youtube.com"))
        p.scAnswer.complete(listOf(sc("Gece Yarısı (canlı)", "Anıl Kaya", "3:41")))
        runCurrent()
        var ui = SearchMapping.toUi(ctl.state.value)
        assertEquals(SearchMapping.YTM_ERROR, ui.ytmError)
        assertNull(ui.note)
        assertEquals(listOf("Gece Yarısı (canlı)"), ui.songs.map { it.title })
        assertTrue(ui.artists.isEmpty() && ui.albums.isEmpty() && ui.lists.isEmpty())

        p.ytmAnswer = CompletableDeferred()
        ctl.retryYtm()
        runCurrent()
        assertEquals(listOf("hidra" to "", "hidra" to ""), p.ytmCalls)
        assertEquals(1, p.scCalls) // SoundCloud's rows stay, it is not asked again
        ui = SearchMapping.toUi(ctl.state.value)
        assertNull(ui.ytmError) // while it is asked again the block goes away
        p.ytmAnswer.complete(YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json")))
        runCurrent()
        ui = SearchMapping.toUi(ctl.state.value)
        assertNull(ui.ytmError)
        assertTrue(ui.artists.isNotEmpty())
        assertTrue(ui.songs.any { it.title == "Gece Yarısı (canlı)" })
        coroutineContext.cancelChildren()
    }

    /** "Biraz sonra tekrar denenecek": a failure is asked again by itself, at most YTM_AUTO_RETRIES times. */
    @Test fun aFailedYouTubeMusicIsAskedAgainByItselfAFewTimes() = runBlocking {
        ctl = SearchController(this, p, recent, { local }, timeoutMs = TIMEOUT, scTimeoutMs = TIMEOUT, retryMs = 40)
        ctl.submit("hidra")
        p.ytmAnswer.completeExceptionally(java.io.IOException("timeout"))
        p.scAnswer.complete(listOf(sc("Gece Yarısı (canlı)", "Anıl Kaya", "3:41")))
        delay(40L * (SearchController.YTM_AUTO_RETRIES + 4) + 200)
        assertEquals(1 + SearchController.YTM_AUTO_RETRIES, p.ytmCalls.size)
        assertEquals(SearchMapping.YTM_ERROR, SearchMapping.toUi(ctl.state.value).ytmError)
        assertEquals(30_000L, SearchController.YTM_RETRY_MS)
        coroutineContext.cancelChildren()
    }

    /** A filter SoundCloud is not asked for: the error says only that it is asked again; both failing is still the offline note. */
    @Test fun theErrorLineWithoutSoundCloudAndNoRetryWhenBothFailed() = runBlocking {
        ctl = SearchController(this, p, recent, { local }, timeoutMs = TIMEOUT, scTimeoutMs = TIMEOUT, retryMs = 60_000)
        ctl.submit("daft punk")
        p.ytmAnswer.complete(YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json")))
        p.scAnswer.complete(emptyList())
        runCurrent()
        p.ytmAnswer = CompletableDeferred()
        ctl.selectChip(com.muratkirazkaya.meiro.ui.screens.SearchChip.Albums)
        p.ytmAnswer.completeExceptionally(java.io.IOException("timeout"))
        runCurrent()
        assertEquals(SearchMapping.YTM_ERROR_NO_SC, SearchMapping.toUi(ctl.state.value).ytmError)
        ctl.retryYtm()
        runCurrent()
        assertEquals("a filter keeps its params when asked again", p.ytmCalls[1].second, p.ytmCalls.last().second)
        coroutineContext.cancelChildren()

        p.ytmAnswer = CompletableDeferred(); p.scAnswer = CompletableDeferred()
        ctl = SearchController(this, p, recent, { local }, timeoutMs = TIMEOUT, scTimeoutMs = TIMEOUT, retryMs = 60_000)
        ctl.submit("kavinsky")
        p.ytmAnswer.completeExceptionally(java.io.IOException("offline"))
        p.scAnswer.completeExceptionally(RuntimeException("yt-dlp failed"))
        advanceUntilIdle()
        val calls = p.ytmCalls.size
        ctl.retryYtm() // the offline note stands; nothing is asked
        assertEquals(calls, p.ytmCalls.size)
        assertNull(SearchMapping.toUi(ctl.state.value).ytmError)
        coroutineContext.cancelChildren()
    }

    @Test fun queueItemsPlayFromYouTubeMusicWhenTheTrackHasIt() {
        val merged = SearchMerge.mergeProviders(listOf(yt("Get Lucky", "Daft Punk", "4:08", "abc")), listOf(sc("Get Lucky", "Daft Punk", "4:08"), sc("Solo", "DJ", "2:00")))
        val q = merged.map { it.toQItem() }
        assertEquals(Source.YTM, q[0].source); assertEquals("abc", q[0].key)
        assertEquals(listOf(Source.YTM, Source.SOUNDCLOUD), q[0].sources)
        assertEquals(Source.SOUNDCLOUD, q[1].source); assertEquals("https://soundcloud.com/x/solo", q[1].key)
        val rows = with(SearchMapping) { merged.map { it.toSongRow({ _, _ -> "matched" }, "abc") } }
        assertTrue(rows[0].ytm && rows[0].sc && rows[0].activeYtm && rows[0].now)
        assertEquals("Daft Punk · 4:08", rows[0].subtitle)
        assertEquals(LinkBadge.None, rows[0].link)
        assertFalse(rows[1].preview)
    }
}

private const val TIMEOUT = 400L
