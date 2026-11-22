package com.muratkirazkaya.meiro.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browse parser against real signed-out responses recorded by `tools/record-ytm-fixtures.sh browse`: the album
 * "Random Access Memories" and the artist page of Daft Punk, as the search "daft punk" leads to them.
 */
class YtmBrowseTest {
    private fun fixture(name: String) = YtmBrowseTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

    @Test fun albumHeadingComesFromTheResponsiveHeader() {
        val p = YtmBrowse.parse(fixture("ytm_browse_album_ram.json"))
        assertEquals("Random Access Memories", p.title)
        assertEquals("Album • 2013", p.subtitle)
        assertEquals("Daft Punk", p.strapline)
        assertEquals("13 songs • 1 hour, 14 minutes", p.secondSubtitle)
        // The largest picture of the header.
        assertTrue(p.thumbnail, p.thumbnail.startsWith("https://yt3.googleusercontent.com/") && p.thumbnail.contains("=w544-h544"))
    }

    @Test fun albumTracksInOrderWithNumbersLengthsAndCredits() {
        val p = YtmBrowse.parse(fixture("ytm_browse_album_ram.json"))
        val t = p.tracks()
        assertEquals(13, t.size)
        assertEquals((1..13).map { "$it" }, t.map { it.index })
        assertEquals("Give Life Back to Music", t[0].title)
        assertEquals("IluRBvnYMoY", t[0].videoId)
        assertEquals("4:36", t[0].duration)
        assertEquals("Instant Crush (feat. Julian Casablancas)", t[4].title)
        assertEquals("Daft Punk, Julian Casablancas", t[4].artists)
        assertEquals("5:38", t[4].duration)
        assertEquals("Contact", t[12].title)
        // Album rows carry no picture; tracks(art) gives them the album's.
        assertTrue(t.all { it.thumbnail.isEmpty() })
        assertTrue(p.tracks("A").all { it.thumbnail == "A" })
        // The carousels below ("Other versions", "Releases for you") hold albums, not tracks.
        assertEquals(listOf("", "Other versions", "Releases for you"), p.sections.map { it.title })
    }

    @Test fun artistPageSections() {
        val p = YtmBrowse.parse(fixture("ytm_browse_artist_daft_punk.json"))
        assertEquals("Daft Punk", p.title)
        assertTrue(p.description.startsWith("Daft Punk were a French electronic music duo formed in 1993"))
        // The header picture is a wide banner; the microformat one is square.
        assertTrue(p.thumbnail.contains("=w1080-h450"))
        assertTrue(p.square, p.square.contains("=w544-h544"))
        assertEquals(
            listOf("Top songs", "Albums", "Singles & EPs", "Videos", "Live performances", "Featured on", "Playlists by Daft Punk", "Fans might also like"),
            p.sections.map { it.title },
        )
        val top = p.section("Top songs")!!.items
        assertEquals(5, top.size)
        assertEquals("Instant Crush (feat. Julian Casablancas)", top[0].title)
        assertEquals("khnokW3Mw24", top[0].videoId)
        assertTrue(top.all { it.isTrack() && it.duration.isEmpty() })
        val albums = p.section("Albums")!!.items
        assertEquals("Random Access Memories", albums[1].title)
        assertEquals("2013", albums[1].subtitle)
        assertEquals(TargetKind.Album to "MPREb_K8qWMWVqXGi", albums[1].target())
        val similar = p.section("Fans might also like")!!.items
        assertEquals(10, similar.size)
        assertEquals("Mr. Oizo", similar[0].title)
        assertEquals(TargetKind.Artist to "UCkjEqPY4fcDZFYER-rF8kmA", similar[0].target())
    }

    @Test fun searchRowsLeadToTheRecordedPages() {
        // The ids the search's artist row and album tile open are the ones these pages were read with.
        val r = YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json"))
        assertEquals(TargetKind.Artist to "UCRr1xG_2WIDs18a6cIiCxeA", r.top!!.target())
        assertTrue((r.items).any { it.target() == (TargetKind.Album to "MPREb_K8qWMWVqXGi") })
    }
}
