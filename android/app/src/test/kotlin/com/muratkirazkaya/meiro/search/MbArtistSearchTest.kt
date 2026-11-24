package com.muratkirazkaya.meiro.search

import com.muratkirazkaya.meiro.musicbrainz.MbArtist
import com.muratkirazkaya.meiro.musicbrainz.MusicBrainzClient
import com.muratkirazkaya.meiro.ui.SearchMapping
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.screens.SearchChip
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.SearchResult
import com.muratkirazkaya.meiro.youtube.YtmSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(name: String) = MbArtistSearchTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

/** The search's "Sanatçılar" from MusicBrainz (M6d, `artists.go` `loadArtists`, `artistRowsShown`, `artistsWanted`). */
class MbArtistSearchTest {
    private class Providers : SearchProviders {
        val ytm = CompletableDeferred<SearchResult>()
        override suspend fun ytm(query: String, params: String): SearchResult = ytm.await()
        override suspend fun ytmMore(token: String): SearchResult = SearchResult(emptyList())
        override suspend fun soundCloud(query: String): List<MusicItem> = emptyList()
    }

    private class Recent : RecentStore { override fun load() = emptyList<String>(); override fun save(queries: List<String>) {} }

    /** The recorded MusicBrainz answer for "Daft Punk" in ListenBrainz order, as `Artists.search` would sort it. */
    private val daftPunk: List<MbArtist> by lazy {
        val artists = MusicBrainzClient.parseArtists(fixture("mb_artist_daft_punk.json"))
        val pop = MusicBrainzClient.parsePopularity(fixture("lb_popularity_daft_punk.json")).associateBy { it.mbid }
        com.muratkirazkaya.meiro.musicbrainz.Artists.sortArtists(artists.map { a -> pop[a.id]?.let { a.copy(listeners = it.listeners) } ?: a })
    }

    @Test fun submitAsksMusicBrainzOnceAndAChipKeepsTheAnswer() = runBlocking {
        val p = Providers()
        val asked = mutableListOf<String>()
        val answer = CompletableDeferred<List<MbArtist>>()
        val ctl = SearchController(this, p, Recent(), mbArtists = { asked += it; answer.await() })
        ctl.submit("daft punk")
        assertTrue(ctl.state.value.mb.loading)
        p.ytm.complete(YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json")))
        delay(30)
        // While MusicBrainz has not answered, YouTube Music's artists show.
        var ui = SearchMapping.toUi(ctl.state.value)
        assertTrue(ui.artists.isNotEmpty() && ui.artists.all { it.logo == Provider.Ytm })
        answer.complete(daftPunk)
        delay(30)
        ui = SearchMapping.toUi(ctl.state.value)
        assertEquals(SearchMapping.ALL_ARTISTS, ui.artists.size)
        val first = ui.artists[0]
        assertEquals("Daft Punk", first.name)
        assertEquals("MusicBrainz · Fransa · French electronic duo", first.meta)
        assertEquals(SearchMapping.MB_ARTIST_PREFIX + "056e4f3e-d505-4dad-8ec1-d04f521cbb56", first.key)
        assertEquals(null, first.logo); assertEquals(false, first.link)
        // YouTube Music's "Daft Punk" is the same artist: not shown twice.
        assertEquals(1, ui.artists.count { it.name.equals("Daft Punk", ignoreCase = true) })
        ctl.selectChip(SearchChip.Artists)
        ctl.selectChip(SearchChip.Songs)
        assertEquals(listOf("daft punk"), asked)
        assertTrue(SearchMapping.toUi(ctl.state.value).artists.isEmpty()) // "Şarkılar" has no use for artists
        coroutineContext.cancelChildren()
    }

    @Test fun artistsChipShowsUpToFiveFromMusicBrainzThenYouTubeMusicsOthers() {
        val ytm = YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json"))
        val s = SearchState(submitted = "daft punk", chip = SearchChip.Artists, ytm = ProviderAnswer(ytm.items, done = true), top = ytm.top,
            mb = MbArtistsAnswer("daft punk", artists = daftPunk))
        val rows = SearchMapping.toUi(s).artists
        val mb = rows.takeWhile { it.key.startsWith(SearchMapping.MB_ARTIST_PREFIX) }
        assertEquals(com.muratkirazkaya.meiro.musicbrainz.Artists.ARTISTS_SHOWN, mb.size)
        assertEquals(daftPunk.take(5).map { it.name }, mb.map { it.name })
        assertTrue(rows.drop(mb.size).all { it.logo == Provider.Ytm })
        // A row MusicBrainz has no country or note for says only "MusicBrainz".
        assertTrue(mb.any { it.meta == "MusicBrainz" } || mb.all { it.meta.startsWith("MusicBrainz · ") })
    }

    /** A MusicBrainz failure leaves YouTube Music's artists in place (the mock has no "unavailable" line). */
    @Test fun aMusicBrainzFailureKeepsYouTubeMusicsArtists() = runBlocking {
        val p = Providers()
        val ctl = SearchController(this, p, Recent(), mbArtists = { throw java.io.IOException("musicbrainz: answered 503") })
        ctl.submit("daft punk")
        p.ytm.complete(YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json")))
        delay(30)
        val s = ctl.state.value
        assertEquals("musicbrainz: answered 503", s.mb.error)
        val ui = SearchMapping.toUi(s)
        assertTrue(ui.artists.isNotEmpty() && ui.artists.all { it.logo == Provider.Ytm })
        coroutineContext.cancelChildren()
    }

    /** An answer for an earlier query is not shown for a new one; without a network MusicBrainz is not asked. */
    @Test fun staleAndOffline() = runBlocking {
        val s = SearchState(submitted = "justice", mb = MbArtistsAnswer("daft punk", artists = daftPunk))
        assertTrue(SearchMapping.toUi(s.copy(ytm = ProviderAnswer(done = true))).artists.isEmpty())
        var asked = 0
        val ctl = SearchController(this, Providers(), Recent(), online = { false }, mbArtists = { asked++; daftPunk })
        ctl.submit("daft punk")
        delay(30)
        assertEquals(0, asked)
        coroutineContext.cancelChildren()
    }
}
