package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.data.SourceRef
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.sync.Ids
import com.muratkirazkaya.meiro.ui.TrackMenuMapping.toListItem
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.youtube.TargetKind
import com.muratkirazkaya.meiro.youtube.YtmSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(name: String) = TrackMenuMappingTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

/** What the track menu, the add-to sheet and the list menu show, and what they store (`TrackMenuMapping`). */
class TrackMenuMappingTest {
    private val ytm = QItem(Source.YTM, "vid1", "Get Lucky", "Daft Punk, Pharrell Williams", "4:08", "https://i.ytimg.com/a.jpg", album = "Random Access Memories")
    private val sc = QItem(Source.SOUNDCLOUD, "https://soundcloud.com/a/b", "Gece Yarısı (canlı)", "Anıl Kaya", "3:41")
    private val both = ytm.copy(sources = listOf(Source.YTM, Source.SOUNDCLOUD), refs = mapOf(Source.YTM to "vid1", Source.SOUNDCLOUD to "https://soundcloud.com/dp/gl"))

    @Test fun linkWordsAreTheMocks() {
        assertEquals("eşleşti", TrackMenuMapping.linkText(LinkState.MATCHED))
        assertEquals("diğer", TrackMenuMapping.linkText(LinkState.OTHER))
        assertEquals("unknown", TrackMenuMapping.linkText(LinkState.UNKNOWN))
        assertEquals("unknown", TrackMenuMapping.linkText(null))
    }

    @Test fun menuShowsTheProvidersTheAlbumAndTheListItem() {
        val m = TrackMenuMapping.menu(MenuTarget(sc), null)
        assertEquals(listOf(Provider.Sc), m.sources)
        assertFalse(m.album); assertFalse(m.inList)
        assertEquals("Anıl Kaya", m.artist)
        val b = TrackMenuMapping.menu(MenuTarget(both, listId = "l1"), LinkState.MATCHED)
        assertEquals(listOf(Provider.Ytm, Provider.Sc), b.sources)
        assertTrue(b.album); assertTrue(b.inList)
        assertEquals("eşleşti", b.link)
        assertEquals("https://i.ytimg.com/a.jpg", b.cover.url)
        // An album page names its album by id even when the row carries no album name.
        assertTrue(TrackMenuMapping.menu(MenuTarget(sc, albumId = "MPREb_x"), null).album)
    }

    /** `libraryItem`: the key and URL a list keeps, unknown, the length in ms, the album page's cover, refs only when merged. */
    @Test fun queueTracksBecomeListItems() {
        val y = ytm.toListItem()
        assertEquals(Item(source = Source.YTM, key = "vid1", title = "Get Lucky", artist = "Daft Punk, Pharrell Williams", durationMs = 248_000, state = LinkState.UNKNOWN, thumbnail = "https://i.ytimg.com/a.jpg"), y)
        assertEquals(Source.YTM to "vid1", y.activeRef())
        val s = sc.toListItem("https://art/cover.jpg")
        assertEquals(Source.SOUNDCLOUD to "https://soundcloud.com/a/b", s.activeRef())
        assertEquals("https://soundcloud.com/a/b", s.url)
        assertEquals("https://art/cover.jpg", s.albumThumbnail)
        assertTrue(s.sources.isEmpty())
        val m = both.toListItem()
        assertEquals(listOf(SourceRef(Source.YTM, id = "vid1"), SourceRef(Source.SOUNDCLOUD, url = "https://soundcloud.com/dp/gl")), m.sources)
        assertEquals(Source.YTM to "vid1", m.activeRef())
        // Played from SoundCloud, the list keeps it at SoundCloud and still knows the YouTube Music copy.
        val fromSc = both.copy(source = Source.SOUNDCLOUD, key = "https://soundcloud.com/dp/gl").toListItem()
        assertEquals(Source.SOUNDCLOUD to "https://soundcloud.com/dp/gl", fromSc.activeRef())
        assertEquals(2, fromSc.sources.size)
    }

    /** Added through the store, a track is in the list once; the same recording from the other provider is refused. */
    @Test fun addingTwiceKeepsOneAndTheRowPlaysItBack() {
        val store = LibraryStore()
        val lib = store.create("Gece Sürüşü")
        assertTrue(store.addItem(lib.id, both.toListItem()))
        assertFalse(store.addItem(lib.id, both.copy(source = Source.SOUNDCLOUD, key = "https://soundcloud.com/dp/gl").toListItem()))
        val held = store.get(lib.id)!!.items.single()
        assertEquals(both.key, held.toQItem().key)
        assertEquals(setOf(Source.YTM, Source.SOUNDCLOUD), held.toQItem().providers())
        store.removeItem(lib.id, held.key)
        assertTrue(store.get(lib.id)!!.items.isEmpty())
        assertEquals(1, store.get(lib.id)!!.removed.size)
    }

    @Test fun addToRowsLeaveOutTheDailyMix() {
        val mix = Library("m", "Daily mix", serverId = Ids.dailyMixId(), items = listOf(Item(key = "a")))
        val a = Library("a", "Gece Sürüşü", items = List(28) { Item(key = "k$it") })
        val b = Library("b", "Keşifler")
        val rows = TrackMenuMapping.addToRows(listOf(mix, a, b), "")
        assertEquals(listOf("Gece Sürüşü" to "28 parça", "Keşifler" to "0 parça"), rows.map { it.name to it.meta })
        assertEquals(listOf("Gece Sürüşü", "Keşifler"), TrackMenuMapping.addToRows(listOf(a, mix, b), "m").map { it.name })
    }

    @Test fun listMenuCountsTheUnlinkedTracks() {
        val lib = Library("a", "A", items = listOf(Item(key = "1", state = LinkState.MATCHED), Item(key = "2", state = LinkState.UNKNOWN), Item(key = "3"), Item(key = "4", state = LinkState.OTHER)))
        assertEquals(2, TrackMenuMapping.listMenu(lib).unknown)
        assertTrue(TrackMenuMapping.listMenu(lib).own)
    }

    @Test fun firstArtistOfACredit() {
        assertEquals("Daft Punk", TrackMenuMapping.firstArtist("Daft Punk, Pharrell Williams"))
        assertEquals("Simon", TrackMenuMapping.firstArtist("Simon & Garfunkel"))
        assertEquals("Anıl Kaya", TrackMenuMapping.firstArtist("Anıl Kaya"))
        assertEquals("", TrackMenuMapping.firstArtist(""))
    }

    /** Against a recorded search: the lead card is the artist; song rows that link an artist are not artists. */
    @Test fun artistAndAlbumFromASearch() {
        val r = YtmSearch.parseSearch(fixture("ytm_search_daft_punk.json"))
        assertEquals(r.top!!.browseId, TrackMenuMapping.pickArtist(r, "daft punk"))
        val albums = r.items.filter { it.target().first == TargetKind.Album }
        assertTrue("the recorded search lists albums", albums.isNotEmpty())
        val last = albums.last()
        assertEquals(last.browseId, TrackMenuMapping.pickAlbum(r, last.title.uppercase()))
        assertEquals(albums.first().browseId, TrackMenuMapping.pickAlbum(r, "no such album"))
        assertNull(TrackMenuMapping.pickArtist(com.muratkirazkaya.meiro.youtube.SearchResult(emptyList()), "x"))
    }

    @Test fun shareLinkAndOtherSource() {
        assertEquals("https://music.youtube.com/watch?v=vid1", TrackMenuMapping.shareLink(ytm))
        assertEquals("https://soundcloud.com/a/b", TrackMenuMapping.shareLink(sc))
        assertNull(TrackMenuMapping.otherSource(ytm))
        assertNull(TrackMenuMapping.otherSource(sc))
        assertEquals(Source.SOUNDCLOUD, TrackMenuMapping.otherSource(both))
        assertEquals(Source.YTM, TrackMenuMapping.otherSource(both.copy(source = Source.SOUNDCLOUD, key = "https://soundcloud.com/dp/gl")))
    }
}
