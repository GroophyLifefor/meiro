package com.muratkirazkaya.meiro.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.musicbrainz.Artists
import com.muratkirazkaya.meiro.musicbrainz.MbArtist
import com.muratkirazkaya.meiro.musicbrainz.MbLink
import com.muratkirazkaya.meiro.musicbrainz.MbRecording
import com.muratkirazkaya.meiro.musicbrainz.MusicBrainzClient
import com.muratkirazkaya.meiro.musicbrainz.SimilarArtist
import com.muratkirazkaya.meiro.search.MbArtistsAnswer
import com.muratkirazkaya.meiro.search.ProviderAnswer
import com.muratkirazkaya.meiro.search.SearchState
import com.muratkirazkaya.meiro.ui.compare.stressScreen
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.MiniState
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.Tab
import com.muratkirazkaya.meiro.ui.screens.ArtistScreen
import com.muratkirazkaya.meiro.ui.screens.DetailActions
import com.muratkirazkaya.meiro.ui.screens.MeiroShell
import com.muratkirazkaya.meiro.ui.screens.SearchActions
import com.muratkirazkaya.meiro.ui.screens.SearchScreen
import com.muratkirazkaya.meiro.ui.screens.SystemBars
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun fixture(name: String) = M6dMappingTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

/** The page the mock's `artist` shows, as MusicBrainz would give it: Daft Punk, two linked recordings and one without a link. */
internal val MockMbPage = Artists.Page(
    mbid = "056e4f3e-d505-4dad-8ec1-d04f521cbb56",
    artist = MbArtist("056e4f3e-d505-4dad-8ec1-d04f521cbb56", "Daft Punk", "FR", "French electronic duo", begin = "1993", end = "2021-02-22", ended = true),
    loading = false,
    rows = listOf(
        Artists.Row(MbRecording("r1", "Get Lucky", "Daft Punk", 248_000), Artists.Playable(Source.YTM, "5NV6Rdv1a3I", MbLink("free streaming", "https://music.youtube.com/watch?v=5NV6Rdv1a3I"))),
        Artists.Row(MbRecording("r2", "Instant Crush", "Daft Punk", 337_000), Artists.Playable(Source.SOUNDCLOUD, "https://soundcloud.com/daftpunk/instant-crush", MbLink("free streaming", "https://soundcloud.com/daftpunk/instant-crush"))),
        Artists.Row(MbRecording("r3", "Around the World", "Daft Punk", 429_506)),
    ),
    similar = listOf(SimilarArtist("j", "Justice", 1.0), SimilarArtist("k", "Kavinsky", 0.8)),
)

/** What the MusicBrainz artist page and the search's MusicBrainz rows show (M6d). */
class M6dMappingTest {
    @Test fun pageAsTheMockShowsIt() {
        val d = MbArtistMapping.page(MockMbPage, linkState = { src, key -> if (src == Source.YTM && key == "5NV6Rdv1a3I") LinkState.MATCHED else null }, playingKey = "5NV6Rdv1a3I")
        val ui = d.ui
        assertEquals("Daft Punk", ui.name)
        assertEquals(MockArtist.kind, ui.kind) // "MusicBrainz ile eşleşti · Fransa · 1993–2021"
        assertEquals(listOf("Get Lucky", "Instant Crush", "Around the World"), ui.popular.map { it.title })
        val (lucky, crush, atw) = ui.popular
        assertEquals("Daft Punk · 4:08", lucky.subtitle); assertTrue(lucky.ytm && !lucky.sc && lucky.activeYtm); assertEquals(LinkBadge.None, lucky.link); assertTrue(lucky.now)
        assertTrue(!crush.ytm && crush.sc && !crush.activeYtm); assertEquals(LinkBadge.Unknown, crush.link)
        val mockNa = MockArtist.popular[2]
        assertEquals(mockNa.subtitle, atw.subtitle); assertEquals(mockNa.dim, atw.dim); assertEquals(mockNa.menu, atw.menu)
        assertEquals(mockNa.ytm, atw.ytm); assertEquals(mockNa.sc, atw.sc); assertEquals(LinkBadge.None, atw.link)
        assertTrue(ui.albums.isEmpty())
        assertEquals(listOf("Justice", "Kavinsky"), ui.similar.map { it.title })
        assertTrue(ui.similar.all { it.meta == "ListenBrainz" && it.logo == Provider.Meiro })
        // Rows play the playable recordings only, from the tapped one.
        assertEquals(listOf("5NV6Rdv1a3I", "https://soundcloud.com/daftpunk/instant-crush"), d.queue.map { it.key })
        assertEquals(listOf(0, 1, -1), (0..2).map { d.queueIndex(it) })
        assertEquals(Source.SOUNDCLOUD, d.queue[1].source); assertEquals("5:37", d.queue[1].duration); assertEquals("Daft Punk", d.queue[1].artist)
        assertEquals(listOf("j", "k"), d.similarIds)
    }

    @Test fun kindLineAndHeadingWhileTheArtistIsUnknown() {
        val p = MockMbPage.copy(artist = null)
        assertEquals("MusicBrainz ile eşleşti", MbArtistMapping.kind(p))
        assertEquals("Daft Punk", MbArtistMapping.page(p, name = "Daft Punk").ui.name)
        assertEquals("Sanatçı", MbArtistMapping.page(p).ui.name)
        assertEquals("1:02:03", MbArtistMapping.clock(3_723_000)); assertEquals("", MbArtistMapping.clock(0))
    }

    /** The recorded Daft Punk page: 12 rows, the ones MusicBrainz has no YouTube or SoundCloud link for faded. */
    @Test fun recordedPage() {
        val top = MusicBrainzClient.parseTopRecordings(fixture("lb_top_recordings_daft_punk.json")).take(Artists.RECORDINGS_SHOWN)
        val rows = top.map { r -> Artists.Row(r, Artists.playable(MusicBrainzClient.parseLinks(fixture("mb_links_${r.id}.json")))) }
        val ui = MbArtistMapping.page(MockMbPage.copy(rows = rows)).ui
        assertEquals(listOf("Digital Love", "Derezzed"), ui.popular.filter { it.dim }.map { it.title })
        assertEquals("Daft Punk · 4:58 · çalma bağlantısı yok", ui.popular[1].subtitle)
        assertTrue(ui.popular.filter { !it.dim }.all { it.ytm && it.menu })
    }
}

/**
 * Mock screens `artist` (MusicBrainz variant) and `search-done` fed by the M6d mapping: the controls of the MusicBrainz
 * page, and the search's MusicBrainz row opening the MusicBrainz page.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6dScreenTest {
    @get:Rule val rule = createComposeRule()

    /** A faded recording has no ⋮ and no link button; play, shuffle, rows and a similar artist reach their actions. */
    @Test fun mbArtistPageControls() {
        val calls = mutableListOf<String>()
        val state = LazyListState()
        val ui = MbArtistMapping.page(MockMbPage).ui
        rule.setContent {
            MeiroTheme {
                ArtistScreen(
                    ui, true,
                    DetailActions(
                        onPlay = { calls += "play" }, onShuffle = { calls += "shuffle" }, onRow = { calls += "row$it" },
                        onArtist = { calls += "artist$it" }, onMore = { calls += "more$it" },
                    ),
                    state = state,
                )
            }
        }
        for (t in listOf("hero.playBtn", "hero.shuffleBtn", "song1.title", "song1.moreBtn", "song3.title")) rule.onNodeWithTag(t, useUnmergedTree = true).performClick()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("song3.moreBtn"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("song3.link"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("albums.title"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        rule.runOnIdle { runBlocking { state.scrollBy(520f) } }
        rule.onNodeWithTag("similar2.title", useUnmergedTree = true).performClick()
        // The faded row's tap reaches the route, which plays nothing for it (MbArtistData.queueIndex = -1).
        assertEquals(listOf("play", "shuffle", "row0", "more0", "row2", "artist1"), calls)
    }

    /** The search's MusicBrainz row: no logo, no link button, "MusicBrainz · Fransa · …"; a tap gives the "mb:" key. */
    @Test fun searchMusicBrainzRowOpensTheMusicBrainzPage() {
        val s = SearchState(submitted = "daft punk", field = "daft punk", ytm = ProviderAnswer(done = true), sc = ProviderAnswer(done = true),
            mb = MbArtistsAnswer("daft punk", artists = listOf(MockMbPage.artist!!)))
        val ui = SearchMapping.toUi(s)
        val opened = mutableListOf<String>()
        rule.setContent { MeiroTheme { SearchScreen(ui, false, SearchActions(onArtist = { i -> opened += ui.artists[i].key })) } }
        rule.onNodeWithTag("artist1.title", useUnmergedTree = true).performClick()
        assertEquals(listOf("mb:056e4f3e-d505-4dad-8ec1-d04f521cbb56"), opened)
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("artist1.link"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        assertEquals("MusicBrainz · Fransa · French electronic duo", ui.artists[0].meta)
        assertFalse(ui.artists[0].link)
    }
}

private const val TITLE_150 = "Bu bir şarkı adı değil de neredeyse bir paragraf: gece yarısı sahilde, rüzgâr eserken, uzaktan bir radyodan gelen eski bir türkü (canlı kayıt, 1978, yeniden"
private const val NAME_LONG = "Çok Uzun İsimli Bir Sanatçı ve Onun Daha da Uzun İsimli Orkestrası, Korosu ve Konuk Müzisyenleri Topluluğu (Yeniden Kuruluş)"

/**
 * M6d stress (structural rules only): the MusicBrainz artist page with a 120-character name, a long kind line, twelve
 * recordings with 150-character titles (playable and faded), ten similar artists with long names; the search's
 * MusicBrainz rows from the recorded "Daft Punk" answer plus a 120-character one; 370/320dp, font 1.0/1.3, light and
 * dark, the artist page from the top and scrolled to the end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class M6dStressTest {
    @get:Rule val rule = createComposeRule()

    private val mini = MiniState(TITLE_150, NAME_LONG, CoverSpec(null, 10), listOf(Provider.Ytm), Provider.Ytm, 0.38f)

    private val page = Artists.Page(
        mbid = "x",
        artist = MbArtist("x", NAME_LONG, "CF", "", begin = "1950", end = "2024", ended = true),
        loading = false,
        rows = (1..12).map { i ->
            val r = MbRecording("r$i", if (i % 3 == 1) TITLE_150 else if (i == 2) "Ö" else "Şarkı $i", if (i % 2 == 0) NAME_LONG else "a", if (i == 5) 3_723_000 else 0)
            Artists.Row(r, if (i % 4 == 0) null else Artists.Playable(Source.YTM, "v$i", MbLink("free streaming", "https://youtu.be/v$i")))
        },
        similar = (1..10).map { SimilarArtist("s$it", if (it % 2 == 0) NAME_LONG else "A", 1.0) },
    )

    private fun searchUi(): com.muratkirazkaya.meiro.ui.screens.SearchUi {
        val artists = Artists.sortArtists(MusicBrainzClient.parseArtists(fixture("mb_artist_daft_punk.json")))
        val s = SearchState(submitted = "daft punk", field = TITLE_150, chip = com.muratkirazkaya.meiro.ui.screens.SearchChip.Artists,
            ytm = ProviderAnswer(done = true), sc = ProviderAnswer(done = true),
            mb = MbArtistsAnswer("daft punk", artists = listOf(MbArtist("long", NAME_LONG, "CF", TITLE_150)) + artists))
        return SearchMapping.toUi(s)
    }

    /** [which]: 0 the artist page from the top, 1 scrolled to its end, 2 the search's artist rows. */
    private fun run(theme: String, tag: String, w: Int, fs: Float, which: Int) {
        val state = LazyListState()
        val name = listOf("mbartist", "mbartist-end", "search-mb")[which] + "-$tag"
        val keys = listOf("artist", "artist-scrolled", "search-done")[which]
        val ui = MbArtistMapping.page(page).ui
        val search = if (which == 2) searchUi() else null
        val fails = stressScreen(rule, name, keys, theme, w, fs) {
            if (which == 1) androidx.compose.runtime.LaunchedEffect(Unit) { state.scrollBy(100000f) }
            MeiroShell(Tab.Search, {}, SystemBars(36.dp, 16.dp), mini) {
                if (search != null) SearchScreen(search, true, SearchActions(), state) else ArtistScreen(ui, true, DetailActions(), state = state)
            }
        }
        assertTrue("stress $name $theme (${fails.size} failures):\n" + fails.joinToString("\n") { " - $it" }, fails.isEmpty())
    }

    @Test fun top_light_w370() = run("light", "w370", 370, 1.0f, 0)
    @Test fun top_dark_w320_f1_3() = run("dark", "w320-f1.3", 320, 1.3f, 0)
    @Test fun top_light_w320() = run("light", "w320", 320, 1.0f, 0)
    @Test fun top_dark_w370_f1_3() = run("dark", "w370-f1.3", 370, 1.3f, 0)
    @Test fun end_light_w320_f1_3() = run("light", "w320-f1.3", 320, 1.3f, 1)
    @Test fun end_dark_w370() = run("dark", "w370", 370, 1.0f, 1)
    @Test fun search_light_w320_f1_3() = run("light", "w320-f1.3", 320, 1.3f, 2)
    @Test fun search_dark_w370() = run("dark", "w370", 370, 1.0f, 2)
    @Test fun search_light_w370() = run("light", "w370", 370, 1.0f, 2)
    @Test fun search_dark_w320() = run("dark", "w320", 320, 1.0f, 2)
}
