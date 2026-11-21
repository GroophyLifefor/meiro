package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.MemoryPersistence
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Ports of internal/mblinks/mblinks_test.go. */
class LinksStoreTest {
    @Test fun linksSurviveARestart() {
        val p = MemoryPersistence()
        val s = LinksStore(p)
        s.setSong(Song("ytm", "v1", "matched", "rec-1", "Daft Punk", "art-1", "matched"))
        s.setArtist(ArtistInfo("Daft Punk", "matched", "art-1"))
        s.setAlt(Alt("ytm", "v1", "soundcloud", "https://soundcloud.com/x/y", "https://soundcloud.com/x/y"))
        val again = LinksStore(p)
        val song = again.song("ytm", "v1")
        assertTrue("song = $song", song != null && song.mbid == "rec-1" && song.updatedAt.epochSecond > 0)
        assertEquals("art-1", again.artist("daft punk")?.mbid) // by folded name
        assertEquals("soundcloud", again.alt("ytm", "v1")?.otherProvider)
    }

    // TestMergeKeepsTheLaterRecord
    @Test fun mergeKeepsTheLaterRecord() {
        val s = LinksStore()
        val old = Instant.ofEpochSecond(1_700_000_000); val later = old.plusSeconds(3600)
        s.setSong(Song("ytm", "a", "unknown", updatedAt = old))
        s.setSong(Song("ytm", "b", "matched", "mine", updatedAt = later))
        val took = s.merge(LinkData(
            songs = listOf(Song("ytm", "a", "matched", "theirs", updatedAt = later), Song("ytm", "b", "other", updatedAt = old), Song("ytm", "c", "matched", "new", updatedAt = old)),
            artists = listOf(ArtistInfo("  Daft PUNK ", "matched", "x", old), ArtistInfo("!!!", "matched")),
        ))
        assertEquals("a newer, c new, one artist", 3, took)
        assertEquals("theirs", s.song("ytm", "a")!!.mbid)
        val b = s.song("ytm", "b")!!
        assertTrue("an older record overwrote a later one: $b", b.mbid == "mine" && b.state == "matched")
        assertNotNull(s.song("ytm", "c"))
        assertEquals("merging what it holds", 0, s.merge(s.data()))
    }

    // TestFreshAndArtistKey
    @Test fun freshAndArtistKey() {
        val now = Instant.parse("2026-11-21T12:00:00Z")
        val s = LinksStore(clock = { now })
        assertFalse(s.fresh(com.muratkirazkaya.meiro.data.Rfc3339Nano.ZERO))
        assertTrue(s.fresh(now.minusSeconds(3600)))
        assertFalse(s.fresh(now.minus(LinksStore.RECHECK_AFTER).minusSeconds(3600)))
        assertEquals(LinksStore.artistKey("Daft  Punk"), LinksStore.artistKey("daft punk"))
    }

    @Test fun serverShapeRoundTrip() {
        val d = LinkData(
            songs = listOf(Song("", "v", "matched", "m", "A", "am", "matched", Instant.parse("2026-11-21T10:00:00Z"), Instant.parse("2026-11-21T10:00:00.5Z"))),
            artists = listOf(ArtistInfo("Hidra", "matched", "a", Instant.parse("2026-11-21T10:00:00Z"))),
            alts = listOf(Alt("ytm", "v", "soundcloud", "u", "", Instant.parse("2026-11-21T10:00:00Z"), Instant.parse("2026-11-21T10:00:01Z"))),
        )
        val out = LinksStore.linksOut(d)
        assertEquals("a blank provider reads as ytm on the wire", "ytm", out.songs[0].provider)
        assertEquals("hidra", out.artists[0].artistKey)
        val back = LinksStore.linksIn(out)
        assertEquals(d.songs[0].copy(provider = "ytm"), back.songs[0])
    }
}
