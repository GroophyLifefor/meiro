package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * `writeDailyMix` (recommendations.go:423) on the phone: the shared list, once a day, replaced not added to, adopted
 * from another device, sent through the outbox with each track's reason (the shape the desktop reads).
 */
class DailyMixWriteTest {
    private val servers = mutableListOf<FakeMeiroServer>()
    @After fun tearDown() { servers.forEach { it.stop() } }
    private fun server(): FakeMeiroServer = FakeMeiroServer().also { it.page = 100 }.start().also { servers += it }
    private val today get() = Instant.now().atZone(ZoneId.of("UTC")).toLocalDate().toString()

    private fun picks(n: Int, tag: String = "p") = (1..n).map {
        Item(source = Source.YTM, key = "$tag$it", title = "Song $tag$it", artist = "Artist $it", durationMs = 200_000, why = "Because you listen to Seed $it")
    }

    private fun mixes(d: Device) = d.libraries.list().filter { libraryServerId(it.id, it.serverId) == Ids.dailyMixId() }

    @Test fun writesTheSharedListOnceADay() {
        val d = Device(signedIn = false)
        assertFalse("fewer than 5 picks make no mix", d.engine.writeDailyMix(picks(4)))
        assertTrue(d.libraries.list().isEmpty())
        assertTrue(d.engine.writeDailyMix(picks(6)))
        val mix = mixes(d).single()
        assertEquals("Daily mix", mix.name)
        assertEquals("", mix.cover)
        assertEquals(Ids.dailyMixId(), mix.serverId)
        assertEquals(mix.id, d.engine.settings.dailyMixId)
        assertEquals(today, d.engine.settings.dailyMixDate)
        assertEquals(picks(6).map { it.key }, mix.items.map { it.key })
        assertEquals(picks(6).map { it.why }, mix.items.map { it.why })
        assertTrue(d.engine.dailyMixMadeToday())
        // The same day nothing is made again.
        assertFalse(d.engine.writeDailyMix(picks(7, "q")))
        assertEquals(picks(6).map { it.key }, mixes(d).single().items.map { it.key })
        // Asked for a new one, the list is replaced, not added to: there is still one, holding only the new picks.
        assertTrue(d.engine.writeDailyMix(picks(5, "r"), remake = true))
        val again = mixes(d).single()
        assertEquals(mix.id, again.id)
        assertEquals(picks(5, "r").map { it.key }, again.items.map { it.key })
        assertEquals(6, again.removed.size)
    }

    @Test fun anOlderMixUnderAnotherIdIsDeleted() {
        val d = Device(signedIn = false)
        val old = d.libraries.create("Daily mix")
        d.engine.testUpdateSettings { it.copy(dailyMixId = old.id, dailyMixDate = "2020-01-01") }
        assertTrue(d.engine.writeDailyMix(picks(5)))
        assertEquals(null, d.libraries.get(old.id))
        assertEquals(listOf(old.id), d.libraries.deleted().map { it.id })
        assertEquals(1, mixes(d).size)
    }

    @Test fun theMixGoesToTheServerWithItsReasonsAndAnotherDeviceAdoptsIt() {
        val s = server(); val phone = Device(s, name = "phone"); val laptop = Device(s, name = "laptop")
        assertTrue(phone.engine.writeDailyMix(picks(5)))
        phone.syncBoth()
        for (p in picks(5)) assertEquals(p.why, s.item(Ids.dailyMixId(), "ytm", p.key)!!.note)
        laptop.syncBoth()
        val onLaptop = mixes(laptop).single()
        assertEquals(picks(5).map { it.key }, onLaptop.items.map { it.key })
        assertEquals(onLaptop.id, laptop.engine.settings.dailyMixId)
        assertEquals("tracks put there today make today's mix on the other device too", today, laptop.engine.settings.dailyMixDate)
        // The other device does not make a second mix today.
        assertFalse(laptop.engine.writeDailyMix(picks(8, "z")))
        assertEquals(picks(5).map { it.key }, mixes(laptop).single().items.map { it.key })
    }

    @Test fun aMixAnotherDeviceMadeTodayIsAdoptedInsteadOfWritten() {
        val d = Device(signedIn = false)
        // The list is here (a sync brought it) but the settings were not told yet.
        val shared = d.libraries.addRemote(Ids.dailyMixId(), "Daily mix", "", Instant.now())
        d.libraries.replaceItems(shared.id, picks(5, "other"))
        assertFalse(d.engine.writeDailyMix(picks(6)))
        assertEquals(shared.id, d.engine.settings.dailyMixId)
        assertEquals(today, d.engine.settings.dailyMixDate)
        assertEquals(picks(5, "other").map { it.key }, mixes(d).single().items.map { it.key })
    }
}
