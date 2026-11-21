package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.MemoryPersistence
import com.muratkirazkaya.meiro.data.Rfc3339Nano
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.data.SourceRef
import com.muratkirazkaya.meiro.data.SyncSettings
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Ports of the desktop's syncpull_test.go, syncclear_test.go and sync_test.go (everything that does not need the UI). */
class SyncEngineTest {
    private val servers = mutableListOf<FakeMeiroServer>()
    private val webs = mutableListOf<MockWebServer>()
    @After fun tearDown() { servers.forEach { it.stop() }; webs.forEach { it.shutdown() } }

    private fun server(page: Int = 2): FakeMeiroServer = FakeMeiroServer().also { it.page = page }.start().also { servers += it }
    private fun device(s: FakeMeiroServer, name: String = "laptop", withLinks: Boolean = false) = Device(s, name = name, withLinks = withLinks)
    private fun now() = Instant.now()

    // ---------------------------------------------------------------- push (syncpull_test.go, sync_test.go)

    // TestActiveRefIsWhatSyncTrackSends
    @Test fun activeRefIsWhatSyncTrackSends() {
        val s = server(); val d = device(s)
        fun ytm(i: Int) = SourceRef(Source.YTM, id = "v$i")
        fun cloud(i: Int) = SourceRef(Source.SOUNDCLOUD, url = "https://soundcloud.com/x/$i")
        val shapes = listOf(
            Item(source = Source.YTM, key = "v0"), Item(key = "v1"),
            Item(source = Source.SOUNDCLOUD, key = "https://soundcloud.com/a/b", url = "https://soundcloud.com/a/b"),
            Item(source = Source.YTM, key = "v3", sources = listOf(ytm(3), cloud(3))),
            Item(source = Source.SOUNDCLOUD, key = "v4", sources = listOf(ytm(4), cloud(4))),
            Item(source = Source.YTM, key = "v5", sources = listOf(cloud(5))),
        )
        val lib = d.libraries.create("Shapes")
        shapes.forEachIndexed { i, it -> d.libraries.addItem(lib.id, it.copy(title = "T$i", artist = "A")) }
        val snap = d.engine.syncSnapshot()
        val sent = snap.items.single().items
        d.libraries.get(lib.id)!!.items.forEachIndexed { i, item ->
            val (src, id) = item.activeRef()
            assertEquals("$item", src, sent[i].track.provider); assertEquals("$item", id, sent[i].track.providerTrackId)
        }
        // A song held by two providers goes out on the source it plays from now, with that provider's own key.
        assertEquals("soundcloud", sent[4].track.provider); assertEquals("https://soundcloud.com/x/4", sent[4].track.providerTrackId)
    }

    @Test fun snapshotMapsTheLocalStores() { // TestSyncSnapshotMapsTheLocalStores
        val s = server(); val d = device(s)
        val lib = d.libraries.create("Road trip")
        d.libraries.addItem(lib.id, Item(source = Source.SOUNDCLOUD, key = "https://soundcloud.com/x/y", url = "https://soundcloud.com/x/y", title = "Cloud Song", artist = "C", durationMs = 200000))
        val snap = d.engine.syncSnapshot()
        assertTrue(snap.libraries.size == 1 && snap.libraries[0].kind == "custom" && snap.libraries[0].name == "Road trip")
        assertEquals(Ids.libraryId(lib.id), snap.libraries[0].id)
        val track = snap.items[0].items[0].track
        assertTrue(track.provider == "soundcloud" && track.providerTrackId == "https://soundcloud.com/x/y")
    }

    // TestPushSendsRemovalsDeletionsCoversAndThumbnails
    @Test fun pushSendsRemovalsDeletionsCoversAndThumbnails() {
        val s = server(); val a = device(s)
        val kept = a.libraries.create("Kept")
        a.addSong(kept.id, "one"); a.addSong(kept.id, "two")
        a.libraries.setCover(kept.id, "https://cover/kept")
        val gone = a.libraries.create("Gone")
        // A list that came from the server keeps the server's id.
        val remote = a.libraries.addRemote("11111111-1111-4111-8111-111111111111", "Remote", "", now())
        a.syncBoth()
        a.libraries.removeItem(kept.id, "two")
        assertEquals(0, a.libraries.deleted().size)
        a.libraries.delete(gone.id)
        val snap = a.engine.syncSnapshot()
        val sawGone = snap.libraries.any { it.id == Ids.libraryId(gone.id) && it.deleted && !it.updatedAt.equals(Rfc3339Nano.ZERO) && it.kind == "custom" }
        assertTrue("the deleted list is not in the push as deleted: ${snap.libraries}", sawGone)
        a.syncBoth()

        val id = Ids.libraryId(kept.id)
        val lib = s.library(id)!!
        assertTrue("kept list on the server = $lib", lib.cover == "https://cover/kept" && !lib.deleted)
        val one = s.item(id, "ytm", "one")!!
        assertTrue("track on the server = $one", one.track.thumbnail == "https://pic/one" && one.track.albumThumbnail == "https://album/one" && !one.deleted)
        val two = s.item(id, "ytm", "two")!!
        val keptNow = a.libraries.get(kept.id)!!
        assertTrue("removed track on the server = $two, tombstone ${keptNow.removed}", two.deleted && two.updatedAt == keptNow.removed[0].removedAt)
        assertTrue(s.library(Ids.libraryId(gone.id))?.deleted == true)
        assertEquals("Remote", s.library(remote.serverId)?.name) // pushed under its own id
    }

    // ---------------------------------------------------------------- pull (TestPullMergesWhatTheServerSends)

    @Test fun pullMergesWhatTheServerSends() {
        val now = Instant.now(); val hour = Duration.ofHours(1)
        var pushes = 0
        val asked = mutableListOf<Long>()
        val d = Device(null)
        lateinit var listA: com.muratkirazkaya.meiro.data.Library
        lateinit var listB: com.muratkirazkaya.meiro.data.Library
        fun pages(): Map<Long, Changes> {
            val idA = Ids.libraryId(listA.id); val idB = Ids.libraryId(listB.id)
            fun track(id: String) = Track("ytm", id, id, "R", durationMs = 1000, thumbnail = "https://t/$id")
            fun item(lib: String, id: String, pos: Int, at: Instant, deleted: Boolean) = ChangedItem(lib, track(id), pos, "", at, deleted)
            return mapOf(
                0L to Changes(
                    libraries = listOf(
                        ChangedLibrary(id = "remote-new", kind = "custom", name = "From phone", cover = "https://cover/phone", updatedAt = now),
                        ChangedLibrary(id = idA, kind = "custom", name = "Renamed remotely", updatedAt = now.plus(hour)),
                        ChangedLibrary(id = idB, kind = "custom", name = "Stale name", updatedAt = now.minus(hour)),
                        ChangedLibrary(id = "someone-elses", kind = "ytm_playlist", name = "Not ours", updatedAt = now),
                    ),
                    items = listOf(item("remote-new", "p1", 1, now, false)), next = 3, more = true,
                ),
                3L to Changes(
                    items = listOf(
                        item("remote-new", "p0", 0, now, false), item(idA, "gone", 0, now.plus(hour), true), item(idA, "late", 0, now.plus(hour), false),
                        item(idA, "removed-here", 5, now.minus(hour), false), item(idB, "b0", 1, now.plus(hour), false),
                    ),
                    next = 8,
                ),
            )
        }
        lateinit var served: Map<Long, Changes>
        val web = MockWebServer()
        web.dispatcher = object : Dispatcher() {
            @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                fun json(s: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(s)
                if (!path.endsWith("/sync/changes")) {
                    if (path.endsWith("/api/v1/events")) return json("""{"events":[],"next":0,"more":false}""")
                    pushes++
                    return json("""{"accepted":[],"duplicates":[]}""")
                }
                val since = request.requestUrl!!.queryParameter("since")!!.toLong()
                asked += since
                return json(SyncJson.encodeToString(Changes.serializer(), served[since] ?: Changes(next = since)))
            }
        }
        web.start(); webs += web
        val url = web.url("/").toString().trimEnd('/')
        d.engine.testSignIn(url)

        listA = d.libraries.create("Local A"); listB = d.libraries.create("Local B")
        // Both were made long ago, so a remote record's time decides.
        d.libraries.applyRemoteList(listA.id, "Local A", "", now); d.libraries.applyRemoteList(listB.id, "Local B", "", now)
        d.addSong(listA.id, "gone"); d.addSong(listA.id, "removed-here"); d.addSong(listB.id, "b1"); d.addSong(listB.id, "b3")
        d.libraries.removeItem(listA.id, "removed-here")
        served = pages()

        runBlocking { d.engine.pullChanges(); d.engine.settled() }
        val st = d.engine.settings
        assertTrue("cursor = ${st.syncCursor} for ${st.syncCursorUrl} ${st.syncCursorEmail}", st.syncCursor == 8L && st.syncCursorUrl == url && st.syncCursorEmail == "me@example.com")
        assertEquals(listOf(0L, 3L), asked)
        val names = d.libraries.list().associateBy { it.name }
        val phone = names["From phone"]!!
        assertTrue("the list from the server: $phone", phone.serverId == "remote-new" && phone.cover == "https://cover/phone")
        assertEquals(listOf("p0", "p1"), listKeys(phone))
        assertTrue(phone.items[0].thumbnail == "https://t/p0" && phone.items[0].source == Source.YTM)
        assertTrue("lists = ${names.keys}", "Not ours" !in names && names.size == 3)
        val renamed = names["Renamed remotely"]!!
        assertEquals("the deleted one gone, the old removal not undone", listOf("late"), listKeys(renamed))
        assertTrue("tombstones = ${renamed.removed}: the server's deletion must not leave one", renamed.removed.size == 1 && renamed.removed[0].key == "removed-here")
        assertEquals("an older rename won, or the order is wrong", listOf("b1", "b0", "b3"), listKeys(names["Local B"]!!))
        assertTrue("reading queued or sent something: $pushes pushes, ${d.outbox.pending()} waiting", pushes == 0 && d.outbox.pending() == 0)

        // The next read starts where the last one ended.
        runBlocking { d.engine.pullChanges() }
        assertEquals(listOf(0L, 3L, 8L), asked)
        // Another server, or another account, starts from the beginning.
        d.engine.testUpdateSettings { it.copy(syncUrl = it.syncUrl + "/") }
        runBlocking { d.engine.pullChanges() }
        assertEquals("asked since $asked after the address changed", 0L, asked[3])
        d.engine.signOut()
        assertTrue("signing out kept the cursor", d.engine.settings.syncCursor == 0L && d.engine.settings.syncCursorUrl == "")
    }

    // TestFirstReadOfAnOldInstallDeletesWhatItLacks
    @Test fun firstReadOfAnOldInstallDeletesWhatItLacks() {
        val now = Instant.now()
        fun build(syncedBefore: Boolean): Pair<Device, FakeMeiroServer> {
            val s = server(100)
            val a = device(s)
            val mine = a.libraries.create("Mine")
            a.addSong(mine.id, "keep")
            fun track(id: String) = Track("ytm", id, id)
            val old = now.minus(Duration.ofHours(1))
            s.libs["stale-list"] = FakeMeiroServer.Rec(Library("stale-list", "custom", name = "Stale", updatedAt = old), 1)
            s.items["stale-list|ytm|s0"] = FakeMeiroServer.Rec(ChangedItem("stale-list", track("s0"), 0, "", old, false, 2), 2)
            val id = Ids.libraryId(mine.id)
            s.libs[id] = FakeMeiroServer.Rec(Library(id, "custom", name = "Mine", updatedAt = old), 3)
            s.items["$id|ytm|stale"] = FakeMeiroServer.Rec(ChangedItem(id, track("stale"), 0, "", old, false, 4), 4)
            s.seq = 4
            if (syncedBefore) a.engine.testUpdateSettings { it.copy(syncLastAt = now.minus(Duration.ofHours(24))) }
            return a to s
        }

        val (a, s) = build(true)
        runBlocking { a.engine.pullChanges(); a.engine.settled() }
        val lists = a.libraries.list()
        assertTrue("an old install imported what the server had: $lists", lists.size == 1 && listKeys(lists[0]) == listOf("keep"))
        assertTrue("the migration was not recorded", a.engine.settings.syncTwoWay)
        assertTrue("the stale list was not deleted on the server", s.library("stale-list")!!.deleted)
        assertTrue("the stale track was not deleted on the server", s.item(Ids.libraryId(lists[0].id), "ytm", "stale")!!.deleted)
        val keep = s.item(Ids.libraryId(lists[0].id), "ytm", "keep")!!
        assertTrue("the track that is here was not kept on the server: $keep", !keep.deleted && keep.track.providerTrackId == "keep")

        // Once migrated, the server is read as usual: a new list is imported.
        s.locked {
            s.seq++
            s.libs["later"] = FakeMeiroServer.Rec(Library("later", "custom", name = "Later", updatedAt = Instant.now()), s.seq)
        }
        runBlocking { a.engine.pullChanges(); a.engine.settled() }
        assertEquals("after the migration a new list was not imported: ${a.libraries.list()}", 2, a.libraries.list().size)

        val (fresh, _) = build(false)
        runBlocking { fresh.engine.pullChanges(); fresh.engine.settled() }
        val names = fresh.libraries.list().associate { it.name to listKeys(it) }
        assertTrue("a new install did not import: $names", names["Stale"] == listOf("s0") && names["Mine"] == listOf("keep", "stale"))
        assertTrue("a new install did not record that it reads lists", fresh.engine.settings.syncTwoWay)
    }

    // TestTwoDevicesConverge
    @Test fun twoDevicesConverge() {
        val s = server(); val a = device(s); val b = device(s)
        val trip = a.libraries.create("Trip")
        a.addSong(trip.id, "s1"); a.addSong(trip.id, "s2")
        a.syncBoth()
        b.syncBoth()
        val lists = b.libraries.list()
        assertTrue("B after pulling: $lists", lists.size == 1 && lists[0].name == "Trip" && lists[0].serverId == Ids.libraryId(trip.id) && listKeys(lists[0]) == listOf("s1", "s2"))
        assertEquals("B did not get the cover", "https://pic/s1", lists[0].items[0].thumbnail)
        val onB = lists[0].id

        b.libraries.removeItem(onB, "s2"); b.libraries.rename(onB, "Trip 2")
        b.syncBoth(); a.syncBoth()
        var got = a.libraries.get(trip.id)!!
        assertTrue("A after B's changes: ${got.name} ${listKeys(got)}", got.name == "Trip 2" && listKeys(got) == listOf("s1"))

        // Both edit while apart.
        a.addSong(trip.id, "x"); b.addSong(onB, "y")
        a.syncBoth(); b.syncBoth(); a.syncBoth()
        val onA = a.libraries.get(trip.id)!!
        got = b.libraries.get(onB)!!
        assertTrue("A has ${listKeys(onA)}, B has ${listKeys(got)} (${got.name})",
            listKeys(onA) == listKeys(got) && got.items.size == 3 && "x" in listKeys(got) && "y" in listKeys(got) && got.name == "Trip 2")

        // A deletion on one reaches the other, and the other does not bring it back.
        b.libraries.delete(onB)
        b.syncBoth(); a.syncBoth()
        assertTrue("A after B deleted the list: ${a.libraries.list()}", a.libraries.list().isEmpty() && a.libraries.deleted().isEmpty())
        a.syncBoth(); b.syncBoth()
        assertTrue("the deleted list came back", s.library(Ids.libraryId(trip.id))!!.deleted && a.libraries.list().size + b.libraries.list().size == 0)
    }

    // TestASongAddedByADeviceThatMissedARenameDoesNotUndoIt
    @Test fun aSongAddedByADeviceThatMissedARenameDoesNotUndoIt() {
        val s = server(); val a = device(s); val b = device(s)
        val trip = a.libraries.create("Trip")
        a.addSong(trip.id, "s1")
        a.syncBoth(); b.syncBoth()
        val onB = b.libraries.list()[0].id
        b.libraries.rename(onB, "Renamed on B")
        b.syncBoth()
        // A has not pulled, and adds a song after B's rename.
        a.addSong(trip.id, "s2")
        a.syncBoth(); b.syncBoth()
        for ((name, d) in listOf("A" to a, "B" to b)) {
            val lib = d.libraries.list()[0]
            assertTrue("$name has ${lib.name} ${listKeys(lib)}", lib.name == "Renamed on B" && listKeys(lib) == listOf("s1", "s2"))
        }
    }

    @Test fun sameSecondEditsAreOrderedBySubSecondTime() {
        // Two renames inside one second: the later fraction wins on both devices, whichever arrives first.
        val s = server(); val a = Device(s, clock = com.muratkirazkaya.meiro.data.Clock { Instant.parse("2026-11-21T10:00:00.200Z") })
        val b = Device(s, clock = com.muratkirazkaya.meiro.data.Clock { Instant.parse("2026-11-21T10:00:00.700Z") })
        val la = a.libraries.addRemote(Ids.libraryId("shared"), "Start", "", Instant.parse("2026-11-21T09:00:00Z"))
        val lb = b.libraries.addRemote(Ids.libraryId("shared"), "Start", "", Instant.parse("2026-11-21T09:00:00Z"))
        a.libraries.rename(la.id, "A at .200"); b.libraries.rename(lb.id, "B at .700")
        // B pushes first, then A: A's older edit must not overwrite it, and A must adopt B's name.
        b.syncBoth(); a.syncBoth(); b.syncBoth()
        assertEquals("B at .700", s.library(Ids.libraryId("shared"))!!.name)
        assertEquals("B at .700", a.libraries.list()[0].name)
        assertEquals("B at .700", b.libraries.list()[0].name)
    }

    // ---------------------------------------------------------------- plays

    private fun phonePlay(id: String, video: String, title: String, artist: String, at: Instant, seq: Long) =
        PlayedEvent(id, Track("ytm", video, title, artist, durationMs = 200_000), 200_000, 200_000, true, "phone", at, seq)

    // TestPlaysFromAnotherDeviceAreCounted
    @Test fun playsFromAnotherDeviceAreCounted() {
        val s = server(); val a = device(s, "laptop")
        val now = Instant.now()
        a.playAt("own", now.minus(Duration.ofHours(3)))
        s.plays += phonePlay("phone-1", "p1", "Phone song", "Phone Band", now.minus(Duration.ofHours(2)), 1)
        s.plays += phonePlay("phone-2", "p2", "Phone song two", "Phone Band", now.minus(Duration.ofHours(1)), 2)
        s.plays += phonePlay("phone-3", "p3", "Phone song three", "Phone Band", now.minus(Duration.ofMinutes(30)), 3)
        a.syncBoth()
        assertEquals("history holds this device's one and the phone's three: ${a.historyIds()}", 4, a.engine.history().size)
        val fromPhone = a.engine.history().first { it.eventId == "phone-2" }
        assertTrue("a play from the phone = $fromPhone", fromPhone.device == "phone" && fromPhone.source == "ytm" && fromPhone.videoId == "p2" && fromPhone.title == "Phone song two" &&
            fromPhone.artist == "Phone Band" && fromPhone.playedMs == 200_000L && fromPhone.durationMs == 200_000L && fromPhone.completed && fromPhone.playedAt == now.minus(Duration.ofHours(1)))
        val h = a.engine.history()
        for (i in 1 until h.size) assertFalse("history is not in the order of play", h[i].playedAt.isBefore(h[i - 1].playedAt))
        val st = a.engine.settings
        assertTrue("cursor = ${st.eventCursor}", st.eventCursor == 4L && st.eventCursorUrl == s.url && st.eventCursorEmail == "me@example.com")
        // Read from the beginning in pages of two, then again after the push, which brought this device's own play
        // back; that play went to the server once.
        assertEquals(listOf(0L, 2L, 4L), s.askedPlays)
        assertTrue("the server holds ${s.plays.size} plays, ${a.outbox.eventCount()} wait: the phone's plays must not be sent back", s.plays.size == 4 && a.outbox.eventCount() == 0)
        // Reading again, from the start as well, adds nothing.
        runBlocking { a.engine.pullEvents() }
        a.engine.testUpdateSettings { it.copy(eventCursor = 0) }
        runBlocking { a.engine.pullEvents() }
        assertEquals("reading twice", 4, a.engine.history().size)
        // Signing out forgets the cursor.
        a.engine.signOut()
        assertTrue(a.engine.settings.eventCursor == 0L && a.engine.settings.eventCursorUrl == "" && a.engine.settings.eventCursorEmail == "")
    }

    // TestPlaysOfAnotherDeviceArriveOnSignIn
    @Test fun playsOfAnotherDeviceArriveOnSignIn() {
        val s = server()
        val phone = device(s, "phone"); val laptop = device(s, "laptop")
        val now = Instant.now()
        phone.engine.recordPlay(HistoryEntry("x", "X", "Y", now.minus(Duration.ofHours(1)), "ytm", 100_000, 100_000, true, "phone", "", "ph"))
        phone.syncBoth()
        laptop.engine.recordPlay(HistoryEntry("z", "Z", "W", now.minus(Duration.ofHours(2)), "ytm", 100_000, 100_000, true, "laptop", "", "lp"))
        laptop.syncBoth()
        val h = laptop.engine.history()
        assertTrue("laptop history = $h", h.size == 2 && h[0].eventId == "lp" && h[1].eventId == "ph" && h[1].device == "phone")
    }

    // TestPlayCoversTravelToTheHistoryRows (the sync part)
    @Test fun playCoversTravel() {
        val s = server(); val laptop = device(s, "laptop"); val phone = device(s, "phone")
        laptop.engine.recordPlay(HistoryEntry("cover", "Covered", "A", Instant.now().minusSeconds(60), "ytm", 200_000, 200_000, true, "laptop", "https://pic/cover", "ev-cover"))
        laptop.engine.recordPlay(HistoryEntry("bare", "Bare", "A", Instant.now().minusSeconds(50), "ytm", 200_000, 200_000, true, "laptop", "", "ev-bare"))
        laptop.syncBoth()
        assertEquals("https://pic/cover", s.plays.first { it.track.providerTrackId == "cover" }.track.thumbnail)
        phone.syncBoth()
        val got = phone.engine.history().first { it.videoId == "cover" }
        assertTrue(got.thumbnail == "https://pic/cover" && got.device == "laptop")
        assertEquals("", phone.engine.history().first { it.videoId == "bare" }.thumbnail)
    }

    // TestDailyMixReasonsTravel (a list whose tracks carry a reason, as the Daily mix does)
    @Test fun reasonsTravel() {
        val s = server(100); val a = device(s); val b = device(s)
        val mix = a.libraries.addRemote(Ids.dailyMixId(), "Daily mix", "", Instant.now())
        a.libraries.addItems(mix.id, listOf(
            Item(source = Source.YTM, key = "m1", title = "M1", artist = "X", why = "Because you like X"),
            Item(source = Source.YTM, key = "m2", title = "M2", artist = "Y", why = "Similar to Y"),
        ))
        a.syncBoth()
        for (item in a.libraries.get(mix.id)!!.items) assertEquals(item.why, s.item(Ids.dailyMixId(), "ytm", item.key)!!.note)
        b.syncBoth()
        val onB = b.libraries.list().single { it.serverId == Ids.dailyMixId() }
        assertEquals(listOf("Because you like X", "Similar to Y"), onB.items.map { it.why })
    }

    // TestTwoDevicesShareOneDailyMix (the adoption half; building the mix is the recommender's, A3d)
    @Test fun aDeviceAdoptsTheSharedDailyMix() {
        val s = server(100); val a = device(s); val b = device(s)
        val today = Instant.now().atZone(ZoneId.of("UTC")).toLocalDate().toString()
        val mix = a.libraries.addRemote(Ids.dailyMixId(), "Daily mix", "", Instant.now())
        a.libraries.addItems(mix.id, listOf(Item(source = Source.YTM, key = "m1", title = "M1", artist = "X")))
        a.syncBoth()
        b.syncBoth()
        val got = b.libraries.list().filter { it.name == "Daily mix" }
        assertTrue("B's Daily mix after pulling = $got", got.size == 1 && got[0].serverId == Ids.dailyMixId() && listKeys(got[0]) == listOf("m1"))
        assertEquals(got[0].id, b.engine.settings.dailyMixId)
        assertEquals("tracks put there today make today's mix", today, b.engine.settings.dailyMixDate)
        // An adopted mix whose tracks are old does not claim today.
        val s2 = server(100); val c = device(s2); val e = device(s2)
        val old = c.libraries.addRemote(Ids.dailyMixId(), "Daily mix", "", Instant.parse("2020-01-01T00:00:00Z"))
        c.libraries.applyRemoteItems(old.id, listOf(com.muratkirazkaya.meiro.data.RemoteItem(Item(source = Source.YTM, key = "o", title = "O", updatedAt = Instant.parse("2020-01-01T00:00:00Z")))))
        c.syncBoth(); e.syncBoth()
        assertEquals(e.libraries.list()[0].id, e.engine.settings.dailyMixId)
        assertEquals("", e.engine.settings.dailyMixDate)
    }

    // ---------------------------------------------------------------- clearing the history (syncclear_test.go)

    // TestClearingWhileSignedInClearsTheServer
    @Test fun clearingWhileSignedInClearsTheServer() {
        val s = server(); val a = device(s)
        val now = Instant.now()
        a.playAt("p1", now.minus(Duration.ofHours(2))); a.playAt("p2", now.minus(Duration.ofHours(1)))
        a.syncBoth()
        assertEquals(listOf("p1", "p2"), s.playIds())
        a.playAt("p3", now.minus(Duration.ofMinutes(1))) // waits to be sent
        a.engine.clearHistory(); a.settled()
        assertTrue("history left: ${a.historyIds()}", a.engine.history().isEmpty())
        assertEquals(0, a.outbox.eventCount())
        assertTrue("the server was cleared ${s.clears} times and holds ${s.plays.size} plays", s.clears == 1 && s.plays.isEmpty() && s.clearedAt != null)
        val st = a.engine.settings
        assertTrue("pending ${st.historyClearPending}, applied ${st.historyClearedAt}", st.historyClearPending == null && st.historyClearedAt != null)
        // Reading the plays again brings nothing back.
        a.engine.testUpdateSettings { it.copy(eventCursor = 0) }
        a.syncBoth()
        assertTrue("a read brought plays back: ${a.historyIds()}", a.engine.history().isEmpty())
    }

    // TestClearingSignedOutStaysOnThisComputer
    @Test fun clearingSignedOutStaysOnThisComputer() {
        val d = Device(null, signedIn = false)
        d.playAt("p1", now().minusSeconds(3600))
        d.engine.clearHistory(); d.settled()
        assertTrue(d.engine.history().isEmpty() && d.engine.settings.historyClearPending == null)
    }

    // TestAnOfflineClearingIsRetriedAndDropsQueuedPlays
    @Test fun anOfflineClearingIsRetriedAndDropsQueuedPlays() {
        val s = server(); val a = device(s)
        val now = Instant.now()
        a.playAt("old", now.minus(Duration.ofHours(1)))
        s.plays += phonePlay("other", "o", "Other", "O", now.minus(Duration.ofHours(3)), 1)
        s.offline = true
        a.engine.clearHistory(); a.settled()
        assertEquals("the server was reached while down", 0, s.clears)
        assertNotNull("the clearing was not remembered", a.engine.settings.historyClearPending)
        assertTrue("history ${a.historyIds()}, ${a.outbox.eventCount()} plays wait", a.engine.history().isEmpty() && a.outbox.eventCount() == 0)
        // While it waits, the plays the server still holds do not come back.
        s.offline = false
        runBlocking { a.engine.pullEvents(); a.engine.settled() }
        assertTrue("a play came back before the server was told: ${a.historyIds()}", a.engine.history().isEmpty())

        a.engine.testUpdateSettings { it.copy(eventCursor = 0) }
        a.playAt("after", Instant.now().plusSeconds(60))
        a.syncBoth()
        assertTrue("clears ${s.clears}, pending ${a.engine.settings.historyClearPending}", s.clears == 1 && a.engine.settings.historyClearPending == null)
        assertEquals("the server holds only the play made after the clearing", listOf("after"), s.playIds())
        assertEquals(listOf("after"), a.historyIds())
    }

    // TestAClearingOnAnotherDeviceDropsOlderPlays
    @Test fun aClearingOnAnotherDeviceDropsOlderPlays() {
        val s = server(); val b = device(s)
        val now = Instant.now()
        b.playAt("old1", now.minus(Duration.ofHours(2))); b.playAt("old2", now.minus(Duration.ofHours(1))); b.playAt("new", now.plus(Duration.ofHours(1)))
        b.outbox.dropEventsThrough(now.plus(Duration.ofHours(2))) // already sent
        s.clearedAt = now
        runBlocking { b.engine.pullEvents(); b.engine.settled() }
        assertEquals("history = only the play after the clearing", listOf("new"), b.historyIds())
        assertEquals(now, b.engine.settings.historyClearedAt)
        // A play made before the same clearing that is somehow here again stays: the clearing was applied once.
        b.playAt("late", now.minus(Duration.ofMinutes(30)))
        runBlocking { b.engine.pullEvents(); b.engine.settled() }
        assertTrue("an applied clearing was applied again: ${b.historyIds()}", "late" in b.historyIds())
    }

    // TestClearingOnOneDeviceClearsTheOthers
    @Test fun clearingOnOneDeviceClearsTheOthers() {
        val s = server(); val laptop = device(s, "laptop"); val phone = device(s, "phone"); val tablet = device(s, "tablet")
        val now = Instant.now()
        laptop.playAt("l1", now.minus(Duration.ofHours(3))); phone.playAt("p1", now.minus(Duration.ofHours(2)))
        laptop.syncBoth(); phone.syncBoth(); laptop.syncBoth()
        assertTrue("laptop ${laptop.historyIds()}, phone ${phone.historyIds()}", laptop.engine.history().size == 2 && phone.engine.history().size == 2)
        // The tablet made a play while offline, before the clearing.
        tablet.playAt("t1", now.minus(Duration.ofHours(1)))

        laptop.engine.clearHistory(); laptop.settled()
        assertTrue("clears ${s.clears}, plays ${s.playIds()}", s.clears == 1 && s.plays.isEmpty())
        phone.syncBoth()
        assertTrue("the phone kept ${phone.historyIds()}", phone.engine.history().isEmpty())
        tablet.syncBoth()
        assertTrue("the tablet kept ${tablet.historyIds()}, ${tablet.outbox.eventCount()} wait", tablet.engine.history().isEmpty() && tablet.outbox.eventCount() == 0)
        assertTrue("the offline play came back to the server: ${s.playIds()}", s.plays.isEmpty())
        // A play made after the clearing reaches everyone.
        phone.playAt("p2", Instant.now().plusSeconds(60))
        phone.syncBoth(); laptop.syncBoth()
        assertEquals(listOf("p2"), laptop.historyIds())
    }

    // TestSigningOutForgetsAPendingClearing
    @Test fun signingOutForgetsAPendingClearing() {
        val d = Device(null)
        d.engine.testUpdateSettings { it.copy(historyClearPending = Instant.now()) }
        d.engine.signOut()
        assertNull(d.engine.settings.historyClearPending)
    }

    // ---------------------------------------------------------------- the session (sync_test.go)

    // TestA401EndsTheSessionAndKeepsEverything
    @Test fun a401EndsTheSessionAndKeepsEverything() {
        for (name in listOf("push", "changes", "events", "links", "clearing")) {
            val s = server()
            s.ended = true
            val tokens = InMemoryTokenStore("TOKEN")
            val d = Device(s, tokens = tokens, withLinks = true)
            d.engine.testUpdateSettings { it.copy(syncCursor = 7, syncCursorUrl = it.syncUrl, syncCursorEmail = it.syncEmail, eventCursor = 9, eventCursorUrl = it.syncUrl, eventCursorEmail = it.syncEmail) }
            d.playAt("p1", Instant.now().minusSeconds(3600))
            runBlocking {
                when (name) {
                    "push" -> d.engine.syncNow()
                    "changes" -> d.engine.pullChanges()
                    "events" -> d.engine.pullEvents()
                    "links" -> d.engine.pullLinks()
                    "clearing" -> {
                        // Nothing waits to be pushed, so only the clearing can find out.
                        d.outbox.dropEventsThrough(Instant.now().plusSeconds(3600))
                        d.engine.testUpdateSettings { it.copy(historyClearPending = Instant.now()) }
                        d.engine.syncNow()
                    }
                }
                d.engine.settled()
            }
            val e = d.engine
            assertTrue("$name: token '${e.token}', expired ${e.expired}: the session did not end", e.token == "" && e.expired)
            assertNull("$name: the token must be removed from the store", tokens.load())
            assertTrue("$name: the account was not kept", e.settings.syncEmail == "me@example.com" && e.settings.syncUrl.isNotEmpty())
            assertTrue("$name: the cursors were lost: ${e.settings.syncCursor} ${e.settings.eventCursor}", e.settings.syncCursor == 7L && e.settings.eventCursor == 9L && e.settings.syncCursorEmail.isNotEmpty())
            if (name != "clearing") assertEquals("$name: the queued play was lost", 1, d.outbox.eventCount())
            assertTrue("$name: syncing was switched off", e.settings.syncEnabled)
            if (name == "clearing") assertNotNull("the clearing that waits was lost", e.settings.historyClearPending)
            // Nothing is tried again until the account signs in.
            val asked = s.requests
            runBlocking { e.syncNow(); e.pullChanges(); e.pullEvents(); e.pullLinks(); e.settled() }
            assertEquals("$name: requests went out after the session ended", asked, s.requests)
        }
    }

    // TestSigningInAgainResumesWhereTheSessionEnded
    @Test fun signingInAgainResumesWhereTheSessionEnded() {
        val s = server(100); s.ended = true; s.loginToken = "NEW"
        val tokens = InMemoryTokenStore("TOKEN")
        val d = Device(s, tokens = tokens)
        d.engine.testUpdateSettings { it.copy(syncCursor = 4, syncCursorUrl = it.syncUrl, syncCursorEmail = it.syncEmail, syncTwoWay = true) }
        d.playAt("p1", Instant.now().minusSeconds(3600))
        runBlocking { d.engine.syncNow(); d.engine.settled() }
        assertTrue(d.engine.expired && d.engine.token == "")
        // (the card then shows SESSION_ENDED_MESSAGE with the email filled in)
        assertEquals("Your session ended. Sign in again to keep syncing.", SESSION_ENDED_MESSAGE)
        s.ended = false
        runBlocking { d.engine.signIn("me@example.com", "secret"); d.engine.settled() }
        assertTrue("token '${d.engine.token}', expired ${d.engine.expired} after signing in again", d.engine.token == "NEW" && !d.engine.expired)
        assertEquals("NEW", tokens.load())
        assertEquals("the play that waited was not sent", listOf("p1"), s.playIds())
        assertTrue("the changes were read from ${s.asked}, want from the cursor 4", s.asked.isNotEmpty() && s.asked[0] == 4L)
    }

    // TestAWrongPasswordIsNotAnEndedSession
    @Test fun aWrongPasswordIsNotAnEndedSession() {
        val web = MockWebServer(); web.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401) }
        web.start(); webs += web
        val d = Device(null, signedIn = false)
        d.engine.setSyncUrl(web.url("/").toString().trimEnd('/'))
        runBlocking { d.engine.signIn("me@example.com", "wrong"); d.engine.settled() }
        assertTrue("expired ${d.engine.expired}, err '${d.engine.lastError}'", !d.engine.expired && d.engine.lastError == "The server did not accept the email and password.")
    }

    @Test fun signInNeedsAnEmailAndPasswordAndSavesTheToken() {
        val s = server(); s.loginToken = "FRESH"
        val tokens = InMemoryTokenStore()
        val d = Device(s, tokens = tokens, signedIn = false)
        d.engine.testUpdateSettings { it.copy(syncUrl = s.url) }
        runBlocking { d.engine.signIn("  ", "x") }
        assertEquals("Enter your email and password.", d.engine.lastError)
        runBlocking { d.engine.signIn(" me@example.com ", "pw"); d.engine.settled() }
        assertTrue(d.engine.token == "FRESH" && d.engine.expired.not() && tokens.load() == "FRESH")
        assertTrue(d.engine.settings.syncEnabled && d.engine.settings.syncEmail == "me@example.com") // trimmed
        // A different account clears a clearing that never reached the old server.
        d.engine.testUpdateSettings { it.copy(historyClearPending = Instant.now()) }
        runBlocking { d.engine.signIn("other@example.com", "pw"); d.engine.settled() }
        assertNull(d.engine.settings.historyClearPending)
    }

    @Test fun loadSyncTokenResumesOrMarksTheSessionEnded() {
        val s = server()
        val withToken = Device(s, tokens = InMemoryTokenStore("SAVED"), signedIn = false)
        withToken.engine.testUpdateSettings { it.copy(syncUrl = s.url, syncEmail = "me@example.com", syncEnabled = true) }
        runBlocking { withToken.engine.loadSyncToken(); withToken.engine.settled() }
        assertEquals("SAVED", withToken.engine.token)
        assertTrue("it reads and sends once the token is there", s.requests >= 3)
        val without = Device(s, tokens = InMemoryTokenStore(), signedIn = false)
        without.engine.testUpdateSettings { it.copy(syncUrl = s.url, syncEmail = "me@example.com", syncEnabled = true) }
        runBlocking { without.engine.loadSyncToken() }
        assertTrue(without.engine.expired && without.engine.token == "")
    }

    // ---------------------------------------------------------------- links (sync_test.go)

    // TestLinksAreSentAndReadBackFromTheServer
    @Test fun linksAreSentAndReadBackFromTheServer() {
        val s = server()
        val later = Instant.now().plus(Duration.ofHours(1))
        s.serverLinks = Links(
            songs = listOf(
                SongLink(provider = "ytm", providerTrackId = "from-other-device", state = "matched", mbid = "rec-remote", updatedAt = later),
                SongLink(provider = "ytm", providerTrackId = "mine", state = "other", updatedAt = Instant.ofEpochSecond(1)), // older than mine: must not win
            ),
            artists = listOf(ArtistLink("remote artist", "Remote Artist", "matched", "art-remote", later)),
        )
        val d = device(s, withLinks = true)
        // A link made here goes to the server.
        d.links!!.setSong(Song("ytm", "mine", "matched", "rec-mine", checkedAt = Instant.now()))
        d.engine.queueAllLinks(); d.settled()
        val first = s.pushedLinks.first { it.songs.isNotEmpty() }
        assertTrue("what was sent = $first", first.songs.size == 1 && first.songs[0].providerTrackId == "mine" && first.songs[0].mbid == "rec-mine" && first.songs[0].checkedAt != null)
        // What the server holds is read, the later record of each winning.
        runBlocking { d.engine.pullLinks(); d.engine.settled() }
        assertEquals("rec-remote", d.links.song("ytm", "from-other-device")?.mbid)
        val mine = d.links.song("ytm", "mine")!!
        assertTrue("an older record from the server replaced a newer one: $mine", mine.state == "matched" && mine.mbid == "rec-mine")
        assertEquals("art-remote", d.links.artist("remote artist")?.mbid)
    }

    // TestLinksAreNotSentWhenSignedOut
    @Test fun linksAreNotSentWhenSignedOut() {
        val d = Device(null, signedIn = false, withLinks = true)
        d.links!!.setSong(Song("ytm", "v", "matched", "rec"))
        d.engine.queueAllLinks(); d.settled()
        assertEquals("a signed-out app queued records", 0, d.outbox.pending())
        assertEquals("rec", d.links.song("ytm", "v")?.mbid)
    }

    @Test fun pulledLinksAreMirroredOntoTheListTracks() {
        val s = server()
        s.serverLinks = Links(songs = listOf(SongLink(provider = "ytm", providerTrackId = "t1", state = "matched", mbid = "rec-1", updatedAt = Instant.now().plusSeconds(60))))
        val d = device(s, withLinks = true)
        val lib = d.libraries.create("L"); d.addSong(lib.id, "t1")
        runBlocking { d.engine.pullLinks(); d.engine.settled() }
        val item = d.libraries.get(lib.id)!!.items[0]
        assertTrue(item.state == "matched" && item.mbid == "rec-1")
    }

    // ---------------------------------------------------------------- engine-level push rules

    @Test fun aRetryableFailureIsRetriedFourTimesWithBackoffThenKeptForNextTime() {
        val s = server(); s.offline = true
        val sleeps = mutableListOf<Duration>()
        val d = Device(s)
        // A device whose sleeps are recorded
        val rec = SyncEngine(d.libraries, d.outbox, InMemoryTokenStore(), MemoryPersistence(), null, d.clock, ZoneId.of("UTC"), "laptop", scope = d.scope, sleep = { sleeps += it })
        rec.testSignIn(s.url)
        d.playAt("p1", Instant.now().minusSeconds(60))
        runBlocking { rec.syncNow() }
        assertEquals("four tries (the library list is empty, so only the event goes)", 4, s.requests)
        assertEquals(listOf(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)), sleeps)
        assertEquals("everything stays queued", 1, d.outbox.eventCount())
        assertTrue(rec.lastError.startsWith("Could not reach the server"))
        // and sends it when the server is back
        s.offline = false
        runBlocking { rec.syncNow(); rec.settled() }
        assertEquals(0, d.outbox.eventCount())
        assertEquals("", rec.lastError)
    }

    @Test fun aRefusedBatchDoesNotBlockTheSync() {
        val s = server(); val d = device(s)
        s.refuse = { path, body -> if (path.endsWith("/sync/events") && body.contains("\"bad\"")) 400 else 0 }
        d.playAt("bad", Instant.now().minusSeconds(60)); d.playAt("fine", Instant.now().minusSeconds(30))
        // both in one batch: the whole batch is refused and set aside, so a later push is not blocked
        runBlocking { d.engine.syncNow(); d.engine.settled() }
        assertEquals(1, d.outbox.refused().size)
        assertEquals(0, d.outbox.eventCount())
        assertEquals("", d.engine.lastError)
        d.playAt("next", Instant.now().plusSeconds(1))
        runBlocking { d.engine.syncNow(); d.engine.settled() }
        assertEquals(listOf("next"), s.playIds())
    }

    @Test fun syncOffMeansNothingIsSent() {
        val s = server(); val d = device(s)
        d.engine.testUpdateSettings { it.copy(syncEnabled = false) }
        d.playAt("p", Instant.now().minusSeconds(60))
        runBlocking { d.engine.syncNow() }
        assertEquals(0, s.requests); assertEquals(1, d.outbox.eventCount())
    }

    @Test fun receivingATombstoneDoesNotLeaveOneBehind() {
        val s = server(100); val a = device(s); val b = device(s)
        val l = a.libraries.create("L"); a.addSong(l.id, "x"); a.addSong(l.id, "y")
        a.syncBoth(); b.syncBoth()
        a.libraries.removeItem(l.id, "x")
        a.syncBoth(); b.syncBoth()
        val onB = b.libraries.list()[0]
        assertEquals(listOf("y"), listKeys(onB))
        assertTrue("B must not echo a tombstone for a deletion it received: ${onB.removed}", onB.removed.isEmpty())
    }

    @Test fun downloadsOfThisDeviceArePushedFromTheSource() {
        val s = server()
        val dl = Download(track = trackFor("ytm", "dl1", "", "T", "A", 0), device = "laptop", sizeBytes = 99, updatedAt = Instant.parse("2026-11-21T10:00:00Z"))
        val p = MemoryPersistence()
        val d = Device(s)
        val e = SyncEngine(d.libraries, d.outbox, InMemoryTokenStore(), p, null, d.clock, ZoneId.of("UTC"), "laptop", scope = d.scope, sleep = { }, downloads = { listOf(dl) })
        e.testSignIn(s.url)
        runBlocking { e.syncNow(); e.settled() }
        assertEquals(listOf(dl), s.downloads)
    }

    @Test fun theFirstPushOfANewInstallMakesItTwoWay() {
        val s = server(); val a = device(s)
        assertFalse(a.engine.settings.syncTwoWay)
        a.syncBoth()
        assertTrue(a.engine.settings.syncTwoWay)
        assertNotNull(a.engine.settings.syncLastAt)
    }

    @Test fun stateSurvivesARestartThroughPersistence() {
        val s = server(); val persistence = MemoryPersistence()
        val a = Device(s, persistence = persistence)
        val l = a.libraries.create("Persisted"); a.addSong(l.id, "k")
        a.playAt("e1", Instant.now().minusSeconds(60))
        // the engine goes away before anything was sent; a new one on the same storage finds it all
        val again = Device(s, persistence = persistence, signedIn = false)
        assertEquals(listOf("Persisted"), again.libraries.list().map { it.name })
        assertEquals(1, again.outbox.eventCount())
        assertEquals(listOf("e1"), again.engine.history().map { it.eventId })
        assertEquals("me@example.com", again.engine.settings.syncEmail)
        assertEquals(SyncSettings.DEFAULT_URL, SyncSettings().syncUrl)
    }
}
