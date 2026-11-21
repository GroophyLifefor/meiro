package com.muratkirazkaya.meiro.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(name: String) = YtmSearchTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

/** The ordered reader (`orderedjson.go`): member order, first-wins `get`, a repeated name replacing in place. */
class OrderedJsonTest {
    @Test fun keepsOrderAndReplacesRepeatsInPlace() {
        val o = OrderedJson.parse("""{"b":1,"a":"x","b":2,"c":[true,false,null,-1.5e2,"ç\n\"q\""]}""") as JObj
        assertEquals(listOf("b", "a", "c"), o.members.map { it.first })
        assertEquals(2.0, o["b"])
        val c = o.arr("c")!!
        assertEquals(listOf(true, false, null, -150.0, "ç\n\"q\""), c)
    }

    @Test fun notJsonIsNull() {
        assertNull(OrderedJson.parse("{\"a\":"))
        assertNull(OrderedJson.parse("<html>"))
        assertNull(OrderedJson.parse("{} trailing"))
    }

    @Test fun firstMatchInDocumentOrderWins() {
        val root = OrderedJson.parse("""{"x":{"thumbnails":[{"url":"small"},{"url":"big"}]},"y":{"thumbnails":[{"url":"other"}]}}""")
        assertEquals("big", Renderers.rendererThumbnail(root))
        assertEquals("a", Renderers.nestedString(OrderedJson.parse("""[{"k":""},{"z":{"k":"a"}},{"k":"b"}]"""), "k"))
    }
}

/** The search parser against real signed-out responses recorded by `tools/record-ytm-fixtures.sh`. */
class YtmSearchTest {
    @Test fun searchWithoutFilter() {
        val r = YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json"))
        // The lead card is the artist, kept apart from the list.
        val top = r.top!!
        assertEquals("Daft Punk", top.title)
        assertEquals("Artist • 65.2M monthly audience", top.subtitle)
        assertEquals(TargetKind.Artist, top.target().first)
        assertTrue(top.thumbnail.startsWith("https://"))
        assertTrue(r.items.none { it.title == "Daft Punk" && it.target().first == TargetKind.Artist })

        // The card's songs name no artist of their own: they are credited with the card's artist, the length read
        // from the subtitle's end.
        val voyager = r.items[0]
        assertEquals("Voyager", voyager.title)
        assertEquals("OWiVJMgms9E", voyager.videoId)
        assertEquals("3:48", voyager.duration)
        assertEquals("Song", voyager.subtitle)
        assertEquals("Daft Punk", voyager.artists)
        assertEquals(TargetKind.Track, voyager.target().first)

        // A song row: artist linked, no length in the generic list.
        val within = r.items.first { it.title == "Within" }
        assertEquals("Daft Punk", within.artist)
        assertEquals("", within.duration)
        assertTrue(isArtistId(within.browseId))
        assertEquals(TargetKind.Track, within.target().first)

        val infinity = r.items.first { it.title.startsWith("Infinity Repeating") }
        assertEquals("Daft Punk, Julian Casablancas, The Voidz", infinity.artists)

        val ram = r.items.first { it.title == "Random Access Memories" }
        assertEquals(TargetKind.Album, ram.target().first)
        assertEquals("Album • Daft Punk • 2013", ram.subtitle)
        assertTrue(ram.browseId.startsWith("MPRE"))

        val weeknd = r.items.first { it.title == "The Weeknd" }
        assertEquals(TargetKind.Artist, weeknd.target().first)

        val presenting = r.items.first { it.title == "Presenting Daft Punk" }
        assertEquals(TargetKind.Playlist, presenting.target().first)
        assertEquals("Playlist • YouTube Music • 35 songs", presenting.subtitle)

        val video = r.items.first { it.title == "Harder, Better, Faster, Stronger" }
        assertEquals(TargetKind.Track, video.target().first)
        assertTrue(video.subtitle.startsWith("Video • Daft Punk"))

        val episode = r.items.first { it.subtitle.startsWith("Episode") }
        assertEquals(ItemKind.Episode, episode.kind)

        assertEquals(
            listOf("Artists", "Albums", "Songs", "Community playlists", "Videos", "Featured playlists", "Episodes", "Profiles", "Podcasts"),
            r.filters.map { it.label },
        )
        assertTrue(r.filters.all { it.params.isNotEmpty() })
        assertEquals(32, r.items.size)
    }

    @Test fun filteredSearchAndItsNextPage() {
        val r = YtmSearch.parseSearch(fixture("ytm_search_daft_punk_songs.json"))
        assertEquals(20, r.items.size)
        val first = r.items[0]
        assertEquals("Instant Crush (feat. Julian Casablancas)", first.title)
        assertEquals("Daft Punk, Julian Casablancas", first.artists)
        assertEquals("Daft Punk", first.artist)
        assertEquals("Random Access Memories", first.album)
        assertEquals("5:38", first.duration)
        assertEquals("Daft Punk & Julian Casablancas • Random Access Memories", first.subtitle)
        assertTrue(r.items.all { it.target().first == TargetKind.Track && it.duration.isNotEmpty() && it.thumbnail.startsWith("https://") })
        assertTrue(r.continuationToken.isNotEmpty())

        val next = YtmSearch.parseSearch(fixture("ytm_search_songs_continuation.json"))
        assertEquals(20, next.items.size)
        assertEquals("GLBTM (Studio Outtakes) [Edit]", next.items[0].title)
        assertEquals("2:39", next.items[0].duration)
        assertTrue(next.continuationToken.isNotEmpty())
        assertTrue(next.continuationToken != r.continuationToken)
    }

    @Test fun suggestions() {
        val s = YtmSearch.parseSuggestions(fixture("ytm_suggestions_daft_p.json"))
        assertEquals(
            listOf("daft punk", "daft punk veridis quo", "daft punk instant crush", "daft punk giorgio moroder", "daft punk get lucky", "daft punk one more time"),
            s.queries,
        )
        assertEquals(listOf("Daft Punk", "Veridis Quo", "Instant Crush (feat. Julian Casablancas)", "Giorgio by Moroder", "Get Lucky (feat. Pharrell Williams and Nile Rodgers)"), s.items.map { it.title })
        assertEquals(TargetKind.Artist, s.items[0].target().first)
    }

    @Test fun durationText() {
        assertTrue(Renderers.isDurationText("3:42")); assertTrue(Renderers.isDurationText("1:02:03"))
        assertTrue(!Renderers.isDurationText("3")); assertTrue(!Renderers.isDurationText("123:00")); assertTrue(!Renderers.isDurationText("1:2:3:4"))
    }
}
