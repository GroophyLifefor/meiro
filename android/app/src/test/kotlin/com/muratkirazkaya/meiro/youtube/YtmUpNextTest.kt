package com.muratkirazkaya.meiro.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The up-next parser (`upnext.go`) against real signed-out `next` responses recorded by
 * `tools/record-ytm-fixtures.sh upnext` (the seed video's own mix `RDAMVM<id>` and its next page) and the bare `next`
 * of `related` (which names the automix link).
 */
class YtmUpNextTest {
    private fun fixture(name: String) = YtmUpNextTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

    @Test fun firstPageHoldsTheMixWithItsPlaylistAndNextToken() {
        val p = YtmUpNext.parse(fixture("ytm_upnext_seed.json"))
        assertEquals("RDAMVMOWiVJMgms9E", p.playlistId)
        assertTrue(p.continuation.isNotEmpty())
        assertTrue("${p.items.size} items", p.items.size >= 40)
        // The first entry is the seed itself, with a title, an artist and a length.
        assertEquals("OWiVJMgms9E", p.items[0].videoId)
        assertTrue(p.items.all { it.videoId.isNotEmpty() && it.title.isNotEmpty() })
        assertTrue(p.items.count { it.duration.isNotEmpty() } >= p.items.size - 2)
        assertEquals(p.items.size, p.items.map { it.videoId }.toSet().size)
    }

    @Test fun nextPageContinuesWithOtherTracks() {
        val first = YtmUpNext.parse(fixture("ytm_upnext_seed.json"))
        val next = YtmUpNext.parse(fixture("ytm_upnext_continuation.json"))
        assertTrue(next.items.size >= 40)
        assertTrue(next.continuation.isNotEmpty() && next.continuation != first.continuation)
        assertTrue(next.items.map { it.videoId }.toSet().minus(first.items.map { it.videoId }.toSet()).size >= 20)
    }

    @Test fun bareNextNamesTheAutomixPlaylist() {
        val root = OrderedJson.parse(fixture("ytm_next_related_seed.json"))
        assertEquals("", YtmUpNext.result(root).continuation)
        val payload = YtmUpNext.automixPayload(root)!!.toMap()
        assertEquals("RDAMVMOWiVJMgms9E", payload["playlistId"])
        assertTrue((payload["params"] as String).isNotEmpty())
    }
}
