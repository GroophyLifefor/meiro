package com.muratkirazkaya.meiro.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant

class ManualClock(var t: Instant = Instant.parse("2026-11-21T12:00:00Z")) : Clock {
    override fun now(): Instant = t
    fun advance(d: Duration) { t = t.plus(d) }
}

/** Ports of internal/library/library_test.go. */
class LibraryStoreTest {
    private fun ytm(key: String, title: String = key, artist: String = "Artist", dur: Long = 0) = Item(source = Source.YTM, key = key, title = title, artist = artist, durationMs = dur)
    private fun keys(items: List<Item>) = items.map { it.key }
    private fun ago(clock: Clock, days: Long): Instant = clock.now().minus(Duration.ofHours(days * 24))
    private fun addTracks(s: LibraryStore, id: String, vararg ks: String) { for (k in ks) s.addItem(id, ytm(k)) }

    // TestPersistsAcrossReads
    @Test fun persistsAcrossReads() {
        val store = MemoryPersistence()
        val s = LibraryStore(store)
        val lib = s.create("Road trip")
        assertTrue(s.addItem(lib.id, Item(source = Source.YTM, key = "vid-1", title = "Song", artist = "Someone", durationMs = 180_000)))
        val again = LibraryStore(store).list()
        assertTrue("after reading back: $again", again.size == 1 && again[0].name == "Road trip" && again[0].items.size == 1)
        assertTrue(again[0].items[0].key == "vid-1" && again[0].items[0].title == "Song")
    }

    // TestRefusesTheSameTrackTwice
    @Test fun refusesTheSameTrackTwice() {
        val s = LibraryStore(); val lib = s.create("List")
        val item = Item(source = Source.SOUNDCLOUD, key = "https://soundcloud.com/a/b", url = "https://soundcloud.com/a/b", title = "Cloud Song")
        assertTrue(s.addItem(lib.id, item)); assertFalse(s.addItem(lib.id, item))
        assertEquals(1, s.get(lib.id)!!.items.size)
    }

    // TestRefusesTheSameRecordingFromAnotherProvider
    @Test fun refusesTheSameRecordingFromAnotherProvider() {
        val s = LibraryStore(); val lib = s.create("List")
        val y = Item(source = Source.YTM, key = "vid-1", title = "Just a Moment", artist = "Yorushika", durationMs = 242_000)
        val c = Item(source = Source.SOUNDCLOUD, key = "https://soundcloud.com/y/just-a-moment", url = "https://soundcloud.com/y/just-a-moment", title = "Just a Moment (Official Video)", artist = "yorushika", durationMs = 243_000)
        assertTrue(s.addItem(lib.id, y)); assertFalse(s.addItem(lib.id, c))
        assertEquals(1, s.get(lib.id)!!.items.size)
    }

    // TestOrderMoves
    @Test fun orderMoves() {
        val s = LibraryStore(); val lib = s.create("List")
        addTracks(s, lib.id, "a", "b", "c")
        s.moveItem(lib.id, 0, 2)
        assertEquals(listOf("b", "c", "a"), keys(s.get(lib.id)!!.items))
        try { s.moveItem(lib.id, 0, 9); fail() } catch (_: LibraryException) {}
    }

    // TestRemoveItem
    @Test fun removeItem() {
        val s = LibraryStore(); val lib = s.create("List")
        s.addItem(lib.id, ytm("a", "A")); s.addItem(lib.id, ytm("b", "B"))
        s.removeItem(lib.id, "a")
        assertEquals(listOf("b"), keys(s.get(lib.id)!!.items))
        try { s.removeItem(lib.id, "a"); fail("removing a track that is not there succeeded") } catch (_: LibraryException) {}
    }

    // TestLinksAreKeptWithTheTrack
    @Test fun linksAreKeptWithTheTrack() {
        val s = LibraryStore()
        val one = s.create("One"); val two = s.create("Two")
        s.addItem(one.id, ytm("a", "Song A", "Daft Punk"))
        s.addItem(one.id, ytm("b", "Song B", "Someone Else"))
        s.addItem(two.id, Item(source = Source.SOUNDCLOUD, key = "c", title = "Song C", artist = "daft punk"))
        assertTrue(s.setLink(one.id, "a", LinkState.MATCHED, "rec-1"))
        var got = s.get(one.id)!!
        assertTrue(got.items[0].state == LinkState.MATCHED && got.items[0].mbid == "rec-1")
        s.setLink(one.id, "a", LinkState.OTHER, "ignored")
        got = s.get(one.id)!!
        assertTrue("an other mark kept an MBID: ${got.items[0]}", got.items[0].state == LinkState.OTHER && got.items[0].mbid == "")
        assertFalse(s.setLink(one.id, "missing", LinkState.MATCHED, "x"))
        assertEquals(2, s.setArtistLink("Daft Punk", LinkState.MATCHED, "artist-1"))
        val first = s.get(one.id)!!; val second = s.get(two.id)!!
        assertTrue(first.items[0].artistMbid == "artist-1" && second.items[0].artistMbid == "artist-1" && first.items[1].artistMbid == "")
    }

    // TestAddItemsSkipsWhatTheListHolds
    @Test fun addItemsSkipsWhatTheListHolds() {
        val s = LibraryStore(); val lib = s.create("Copy")
        s.addItem(lib.id, Item(source = Source.YTM, key = "a", title = "Song A", artist = "X", durationMs = 180_000))
        val added = s.addItems(lib.id, listOf(
            Item(source = Source.YTM, key = "a", title = "Song A", artist = "X", durationMs = 180_000),
            Item(source = Source.YTM, key = "b", title = "Song B", artist = "X", durationMs = 200_000),
            Item(source = Source.SOUNDCLOUD, key = "b2", title = "Song B", artist = "X", durationMs = 200_000),
            Item(source = Source.YTM, key = "c", title = "Song C", artist = "Y", durationMs = 150_000),
        ))
        assertEquals("the held and the repeated one are left out", 2, added)
        assertEquals(3, s.get(lib.id)!!.items.size)
        try { s.addItems("missing", emptyList()); fail() } catch (_: LibraryException) {}
    }

    // TestChangesStampTheListAndTheTracksTheyTouch
    @Test fun changesStampTheListAndTheTracksTheyTouch() {
        val clock = ManualClock()
        val s = LibraryStore(clock = clock); val lib = s.create("List")
        addTracks(s, lib.id, "a", "b", "c", "d")
        val old = clock.now()
        clock.advance(Duration.ofSeconds(10))
        s.rename(lib.id, "Renamed")
        assertTrue("a rename did not advance the list", s.get(lib.id)!!.updatedAt.isAfter(old))
        assertTrue("a rename touched the tracks", s.get(lib.id)!!.items.none { it.updatedAt.isAfter(old) })
        clock.advance(Duration.ofSeconds(10))
        val beforeCover = s.get(lib.id)!!.updatedAt
        s.setCover(lib.id, "https://cover")
        assertTrue("a new cover did not advance the list", s.get(lib.id)!!.updatedAt.isAfter(beforeCover))
        // Adding or removing tracks leaves the list's own time alone (UpdatedAt moves only on list-level changes).
        val listTime = s.get(lib.id)!!.updatedAt
        clock.advance(Duration.ofSeconds(10))
        s.addItem(lib.id, ytm("e")); s.removeItem(lib.id, "e")
        assertEquals(listTime, s.get(lib.id)!!.updatedAt)
        // Moving b to the end changes the places of b, c and d, not a's.
        clock.advance(Duration.ofSeconds(10))
        s.moveItem(lib.id, 1, 3)
        for (item in s.get(lib.id)!!.items) assertEquals("after the move, ${item.key}", item.key != "a", item.updatedAt.isAfter(old))
    }

    // TestRemovalsLeaveTombstonesAndReAddingTakesThemBack
    @Test fun removalsLeaveTombstonesAndReAddingTakesThemBack() {
        val s = LibraryStore(); val lib = s.create("List")
        addTracks(s, lib.id, "a", "b")
        s.removeItem(lib.id, "a")
        var got = s.get(lib.id)!!
        assertTrue("tombstones = ${got.removed}", got.removed.size == 1 && got.removed[0].key == "a" && got.removed[0].source == Source.YTM && got.removed[0].title == "a" && !got.removed[0].removedAt.isZero)
        addTracks(s, lib.id, "a")
        assertTrue("a track put back still has its tombstone", s.get(lib.id)!!.removed.isEmpty())
        s.delete(lib.id)
        val deleted = s.deleted()
        assertTrue("deleted = $deleted", deleted.size == 1 && deleted[0].id == lib.id && deleted[0].name == "List" && !deleted[0].deletedAt.isZero)
    }

    // TestSetSourceTombstonesTheOldReference
    @Test fun setSourceTombstonesTheOldReference() {
        val clock = ManualClock()
        val s = LibraryStore(clock = clock); val lib = s.create("List")
        val ytm = SourceRef(Source.YTM, id = "vid")
        val cloud = SourceRef(Source.SOUNDCLOUD, url = "https://soundcloud.com/x/y")
        s.addItem(lib.id, Item(source = Source.YTM, key = "vid", title = "Song", sources = listOf(ytm, cloud)))
        val before = s.get(lib.id)!!
        clock.advance(Duration.ofSeconds(1))
        assertTrue(s.setSource(lib.id, "vid", Source.SOUNDCLOUD))
        var got = s.get(lib.id)!!
        assertTrue("tombstones = ${got.removed}", got.removed.size == 1 && got.removed[0].source == Source.YTM && got.removed[0].key == "vid")
        assertEquals(Source.SOUNDCLOUD to cloud.url, got.items[0].activeRef())
        assertTrue("the switched track was not stamped", got.items[0].updatedAt.isAfter(before.items[0].updatedAt))
        s.setSource(lib.id, "vid", Source.YTM)
        got = s.get(lib.id)!!
        assertTrue("after switching back, tombstones = ${got.removed}", got.removed.size == 1 && got.removed[0].source == Source.SOUNDCLOUD)
    }

    // TestReplaceItemsTombstonesWhatWentAndStampsWhatStays
    @Test fun replaceItemsTombstonesWhatWentAndStampsWhatStays() {
        val clock = ManualClock()
        val s = LibraryStore(clock = clock); val lib = s.create("Daily mix")
        addTracks(s, lib.id, "a", "b")
        clock.advance(Duration.ofSeconds(5))
        s.replaceItems(lib.id, listOf(ytm("b", "b2", "Other"), ytm("c", "c", "Other")))
        val got = s.get(lib.id)!!
        assertTrue("tombstones = ${got.removed}", got.removed.size == 1 && got.removed[0].key == "a")
        for (item in got.items) assertTrue("${item.key} was not stamped with the list's time", !item.updatedAt.isZero && !item.updatedAt.isBefore(got.updatedAt))
    }

    // TestOldFilesLoadAndOldTombstonesArePruned
    @Test fun oldFilesLoadAndOldTombstonesArePruned() {
        val clock = ManualClock()
        val added = clock.now().minus(Duration.ofHours(5 * 24)); val created = clock.now().minus(Duration.ofHours(9 * 24))
        val store = MemoryPersistence()
        store.libraries = LibraryFile(listOf(Library(id = "old", name = "Old", createdAt = created, items = listOf(Item(source = Source.YTM, key = "k", addedAt = added)))))
        val lib = LibraryStore(store, clock).list()[0]
        assertEquals("an old track reads as its AddedAt", added, lib.items[0].updatedAt)
        assertTrue("an old list reads as its CreatedAt", lib.updatedAt == created && lib.serverId == "")

        val s = LibraryStore(store, clock)
        addTracks(s, "old", "fresh", "stale")
        s.removeItem("old", "fresh"); s.removeItem("old", "stale")
        s.delete("old")
        store.libraries = store.libraries.copy(deleted = store.libraries.deleted + DeletedList("ancient", deletedAt = ago(clock, 91)))
        try { s.delete("none"); fail("deleting a list that is not there succeeded") } catch (_: LibraryException) {}
        val keep = s.create("Keep")
        addTracks(s, keep.id, "fresh", "stale")
        s.removeItem(keep.id, "fresh"); s.removeItem(keep.id, "stale")
        // Age one removal by hand, on a list that is kept.
        val lf = store.libraries
        store.libraries = lf.copy(libraries = lf.libraries.map { l -> if (l.id == keep.id) l.copy(removed = listOf(l.removed[0], l.removed[1].copy(removedAt = ago(clock, 91)))) else l })
        val again = LibraryStore(store, clock)
        val got = again.get(keep.id)!!
        assertTrue("after loading, tombstones = ${got.removed}", got.removed.size == 1 && got.removed[0].key == "fresh")
        val deleted = again.deleted()
        assertTrue("after loading, deleted = $deleted", deleted.size == 1 && deleted[0].id == "old")
    }

    private fun remote(key: String, at: Instant) = Item(source = Source.YTM, key = key, title = key, addedAt = at, updatedAt = at)

    // TestApplyRemoteItems
    @Test fun applyRemoteItems() {
        val clock = ManualClock()
        val s = LibraryStore(clock = clock); val lib = s.create("List")
        addTracks(s, lib.id, "a", "b", "c")
        s.removeItem(lib.id, "c")
        val now = clock.now(); val future = now.plus(Duration.ofHours(1)); val past = now.minus(Duration.ofHours(1))
        val n = s.applyRemoteItems(lib.id, listOf(
            RemoteItem(remote("a", future), deleted = true), // newer deletion: removed
            RemoteItem(remote("b", future), position = 5),   // newer: takes its place
            RemoteItem(remote("d", future), position = 0),   // unknown: added in front
            RemoteItem(remote("c", past)),                    // older than this device's removal: stays out
            RemoteItem(remote("e", past), position = 1, deleted = true), // deletion of a track not here: nothing
        ))
        var got = s.get(lib.id)!!
        assertTrue("applied $n, items = ${keys(got.items)}", n == 3 && got.items.size == 2 && got.items[0].key == "d" && got.items[1].key == "b")
        assertTrue("tombstones = ${got.removed}: only this device's own removal", got.removed.size == 1 && got.removed[0].key == "c")

        // A deletion older than the track's own stamp does not remove it, and a newer live record of a track
        // removed here brings it back.
        val n2 = s.applyRemoteItems(lib.id, listOf(RemoteItem(remote("b", past), deleted = true), RemoteItem(remote("c", future), position = 9)))
        got = s.get(lib.id)!!
        assertTrue("applied $n2, items = ${keys(got.items)}, tombstones = ${got.removed}", n2 == 1 && got.items.size == 3 && got.items[2].key == "c" && got.removed.isEmpty())
    }

    // TestARemoteReasonFollowsTheLaterRecord
    @Test fun aRemoteReasonFollowsTheLaterRecord() {
        val s = LibraryStore(); val lib = s.create("Mix")
        s.addItem(lib.id, Item(source = Source.YTM, key = "one", title = "Song one", artist = "Artist"))
        val base = s.get(lib.id)!!.items[0]
        fun apply(why: String, at: Instant): String {
            s.applyRemoteItems(lib.id, listOf(RemoteItem(base.copy(why = why, updatedAt = at))))
            return s.get(lib.id)!!.items[0].why
        }
        assertEquals("new reason", apply("new reason", base.updatedAt.plusSeconds(3600)))
        assertEquals("an older record changed the reason", "new reason", apply("stale", base.updatedAt.plusSeconds(60)))
        assertEquals("a newer record with no reason cleared it", "new reason", apply("", base.updatedAt.plusSeconds(7200)))
    }

    @Test fun equalPlacesAreOrderedBySourceThenIdOnEveryDevice() {
        val a = LibraryStore(); val b = LibraryStore()
        val la = a.create("L"); val lb = b.create("L")
        val at = Instant.parse("2026-11-21T10:00:00Z")
        a.applyRemoteItems(la.id, listOf(RemoteItem(remote("y", at), 3), RemoteItem(remote("x", at), 3), RemoteItem(remote("w", at), 0)))
        b.applyRemoteItems(lb.id, listOf(RemoteItem(remote("x", at), 3), RemoteItem(remote("w", at), 0), RemoteItem(remote("y", at), 3)))
        assertEquals(listOf("w", "x", "y"), keys(a.get(la.id)!!.items))
        assertEquals(keys(a.get(la.id)!!.items), keys(b.get(lb.id)!!.items))
    }

    @Test fun listLevelRemoteRules() {
        val s = LibraryStore()
        val deletedFirst = s.create("Gone")
        s.delete(deletedFirst.id)
        val at = Instant.parse("2026-11-21T10:00:00.123456789Z")
        val remote = s.addRemote("srv-1", "Remote", "https://c", at)
        assertTrue(remote.serverId == "srv-1" && remote.updatedAt == at && remote.createdAt == at)
        // AddRemote lets go of a deletion remembered for the same server id.
        val d = LibraryStore()
        val l = d.addRemote("srv-2", "x", "", at); d.delete(l.id)
        assertEquals(1, d.deleted().size)
        d.addRemote("srv-2", "x again", "", at.plusSeconds(1))
        assertEquals(0, d.deleted().size)
        // ApplyRemoteList stamps the server's time, not the clock's; DropRemote leaves nothing to tell.
        s.applyRemoteList(remote.id, "Renamed", "", at.plusNanos(5))
        assertEquals(at.plusNanos(5), s.get(remote.id)!!.updatedAt)
        s.dropRemote(remote.id)
        assertEquals(1, s.deleted().size) // only the list deleted here
    }
}
