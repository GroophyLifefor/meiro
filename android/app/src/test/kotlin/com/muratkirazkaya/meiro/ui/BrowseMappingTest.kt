package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.youtube.BrowsePage
import com.muratkirazkaya.meiro.youtube.Renderers
import com.muratkirazkaya.meiro.youtube.YtmBrowse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the album and artist pages show for the recorded YouTube Music pages. */
class BrowseMappingTest {
    private fun page(name: String) = YtmBrowse.parse(BrowseMappingTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString())
    private val albumId = "MPREb_K8qWMWVqXGi"
    private val artistId = "UCRr1xG_2WIDs18a6cIiCxeA"

    @Test fun albumHeroAsTheMockWritesIt() {
        val d = BrowseMapping.album(albumId, page("ytm_browse_album_ram.json"))
        assertEquals("Random Access Memories", d.ui.title)
        assertEquals("Albüm · Daft Punk · 2013 · 13 parça", d.ui.kind)
        assertTrue(d.ui.cover.url!!.contains("=w544-h544"))
    }

    @Test fun albumRowsShowTheAlbumArtistLengthNumberAndCover() {
        val d = BrowseMapping.album(albumId, page("ytm_browse_album_ram.json"))
        val r = d.ui.rows[4]
        assertEquals("Instant Crush (feat. Julian Casablancas)", r.title)
        // The mock's "Daft Punk · 5:37": the album's artist, not the track's guest credit.
        assertEquals("Daft Punk · 5:38", r.subtitle)
        assertEquals("5", d.ui.nums[4])
        assertEquals(d.cover, r.cover.url)
        assertTrue(r.ytm && !r.sc && r.activeYtm)
        // No MusicBrainz link known: the link button, as on every other unlinked row.
        assertEquals(LinkBadge.Unknown, r.link)
    }

    @Test fun albumRowLinkStateAndPlayingRow() {
        val d = BrowseMapping.album(albumId, page("ytm_browse_album_ram.json"), { src, id -> if (src == Source.YTM && id == "a5uQMwRMHcs") LinkState.MATCHED else null }, "a5uQMwRMHcs")
        assertEquals(LinkBadge.None, d.ui.rows[4].link)
        assertTrue(d.ui.rows[4].now)
        assertEquals(listOf(4), d.ui.rows.indices.filter { d.ui.rows[it].now })
    }

    @Test fun albumQueueAndCopy() {
        val d = BrowseMapping.album(albumId, page("ytm_browse_album_ram.json"))
        val q = d.queue
        assertEquals(13, q.size)
        assertEquals("IluRBvnYMoY", q[0].key)
        assertEquals(Source.YTM, q[0].source)
        assertEquals("Daft Punk, Julian Casablancas", q[4].artist)
        assertEquals(d.cover, q[4].thumbnail)
        val items = d.listItems()
        assertEquals(13, items.size)
        assertEquals("a5uQMwRMHcs", items[4].key)
        assertEquals(LinkState.UNKNOWN, items[4].state)
        assertEquals(338_000L, items[4].durationMs)
        assertEquals(d.cover, items[4].albumThumbnail)
    }

    @Test fun albumTracksWithoutArtistTakeTheAlbumArtist() {
        // Some albums (Discovery, Homework) list tracks that name no artist; the server refuses a track without one.
        val p = page("ytm_browse_album_ram.json")
        val bare = p.copy(
            items = p.items.map { it.copy(artist = "", artists = "") },
            sections = p.sections.map { s -> Renderers.Section(s.title, s.kind, s.items.map { it.copy(artist = "", artists = "") }, s.continuationToken) },
        )
        val d = BrowseMapping.album(albumId, bare)
        assertTrue(d.queue.isNotEmpty())
        assertTrue(d.queue.all { it.artist == "Daft Punk" })
        assertTrue(d.listItems().all { it.artist == "Daft Punk" })
        assertEquals("Daft Punk · 5:38", d.ui.rows[4].subtitle)
        // A guest credit stays the track's own.
        assertEquals("Daft Punk, Julian Casablancas", BrowseMapping.album(albumId, p).queue[4].artist)
    }

    @Test fun albumKindWithoutStraplineOrYear() {
        assertEquals("Single · 2023 · 1 parça", BrowseMapping.albumKind(BrowsePage("x", "", subtitle = "Single • 2023"), 1))
        assertEquals("EP · Hidra", BrowseMapping.albumKind(BrowsePage("x", "", subtitle = "EP", strapline = "Hidra"), 0))
    }

    @Test fun artistPage() {
        val d = BrowseMapping.artist(artistId, page("ytm_browse_artist_daft_punk.json"))
        assertEquals("Daft Punk", d.ui.name)
        assertEquals(BrowseMapping.ARTIST_KIND, d.ui.kind)
        assertTrue(d.ui.cover.url!!.contains("=w544-h544"))
        assertEquals(5, d.ui.popular.size)
        assertEquals("Instant Crush (feat. Julian Casablancas)", d.ui.popular[0].title)
        // Top songs come without a length: the artists alone.
        assertEquals("Daft Punk, Julian Casablancas", d.ui.popular[0].subtitle)
        assertEquals(10, d.ui.albums.size)
        assertEquals("Random Access Memories", d.ui.albums[1].title)
        assertEquals("2013", d.ui.albums[1].meta)
        assertEquals(Provider.Ytm, d.ui.albums[1].logo)
        assertEquals("MPREb_K8qWMWVqXGi", d.albumIds[1])
        assertEquals("Mr. Oizo", d.ui.similar[0].title)
        assertEquals(BrowseMapping.SIMILAR_META, d.ui.similar[0].meta)
        assertEquals("UCkjEqPY4fcDZFYER-rF8kmA", d.artistIds[0])
        assertEquals(listOf("khnokW3Mw24", "Rgrt_8mXrK8", "wU26xVT_vBU", "Jb6gcoR266U", "JhulBGMA7G4"), d.queue.map { it.key })
    }

    @Test fun artistWithoutSectionsShowsOnlyTheHero() {
        val d = BrowseMapping.artist("UCx", BrowsePage("Yalnız", "https://a/b.jpg"))
        assertEquals("Yalnız", d.ui.name)
        assertEquals("https://a/b.jpg", d.ui.cover.url)
        assertTrue(d.ui.popular.isEmpty() && d.ui.albums.isEmpty() && d.ui.similar.isEmpty() && d.queue.isEmpty())
    }
}
