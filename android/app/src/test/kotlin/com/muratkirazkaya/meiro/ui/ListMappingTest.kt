package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.data.SourceRef
import com.muratkirazkaya.meiro.sync.LinksStore
import com.muratkirazkaya.meiro.sync.Song
import com.muratkirazkaya.meiro.ui.components.LinkBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListMappingTest {
    private val yt = Item(source = Source.YTM, key = "dQw4w9WgXcQ", title = "Never Gonna Give You Up", artist = "Rick Astley", durationMs = 213_000, thumbnail = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg")
    private val sc = Item(source = Source.SOUNDCLOUD, key = "x", url = "https://soundcloud.com/forss/flickermood", title = "Flickermood", artist = "Forss", durationMs = 170_000,
        sources = listOf(SourceRef(Source.SOUNDCLOUD, "https://soundcloud.com/forss/flickermood", "https://soundcloud.com/forss/flickermood")))

    @Test fun listMetaShowsTheProviderSplitOnlyForMixedLists() {
        assertEquals("Meiro · 0 parça", Library("a", "A").metaText())
        assertEquals("Meiro · 1 parça", Library("a", "A", items = listOf(yt)).metaText())
        assertEquals("Meiro · 3 parça · YT 2, SC 1", Library("a", "A", items = listOf(yt, yt.copy(key = "b"), sc)).metaText())
    }

    @Test fun durationsReadAsTheMockWritesThem() {
        assertEquals("1 sa 52 dk", durationText(112 * 60_000L))
        assertEquals("47 dk", durationText(47 * 60_000L + 5_000))
        assertEquals("30 sn", durationText(30_000))
        assertNull(durationText(0))
    }

    @Test fun coverIsTheListsPictureThenTheFirstTracksThenTheGradient() {
        assertEquals("https://x/c.jpg", Library("a", "A", cover = "https://x/c.jpg", items = listOf(yt)).coverSpec().url)
        assertEquals(yt.thumbnail, Library("a", "A", items = listOf(sc, yt)).coverSpec().url)
        assertNull(Library("a", "A", items = listOf(sc)).coverSpec().url)
        assertNull(Library("a", "A", cover = "not a url").coverSpec().url)
    }

    @Test fun soundcloudTrackPlaysFromItsPageUrl() {
        val q = sc.toQItem()
        assertEquals(Source.SOUNDCLOUD, q.source)
        assertEquals("https://soundcloud.com/forss/flickermood", q.key)
        assertEquals("2:50", q.duration)
    }

    @Test fun rowShowsTheSourcesMarkAndTheLinkState() {
        val links = LinksStore().also { it.setSong(Song(Source.YTM, yt.key, LinkState.MATCHED, "mbid")) }
        val r = yt.toSongRow(links, playingKey = null)
        assertEquals("Rick Astley · 3:33", r.subtitle)
        assertTrue(r.ytm && !r.sc && r.activeYtm)
        assertEquals(LinkBadge.None, r.link)
        assertEquals(LinkBadge.Unknown, yt.toSongRow(null, null).link)
        assertEquals(LinkBadge.Other, yt.copy(state = LinkState.OTHER).toSongRow(null, null).link)
        val s = sc.toSongRow(null, playingKey = sc.activeRef().second)
        assertTrue(s.sc && !s.ytm && !s.activeYtm && s.now)
    }

    @Test fun summaryCountsProvidersAndMatches() {
        val links = LinksStore().also { it.setSong(Song(Source.YTM, yt.key, LinkState.MATCHED, "m")) }
        val ui = Library("a", "A", items = listOf(yt, sc)).toPlaylistUi(links, null)
        assertEquals("YT 1 · SC 1 · eşleşen 1", ui.summary)
        assertEquals("Meiro listesi · 2 parça · 6 dk", ui.kind)
    }

    @Test fun storeAnnouncesEveryChange() {
        val s = LibraryStore()
        val v0 = s.version.value
        s.create("Yeni")
        assertTrue(s.version.value > v0)
    }
}
