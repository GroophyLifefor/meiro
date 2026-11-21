package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.Rfc3339Nano
import com.muratkirazkaya.meiro.data.isZero
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Golden values computed by the Go desktop code (PORTING 1.4.9) and the RFC 3339 rules of the wire. */
class GoldenTest {
    @Test fun dailyMixIdIsTheDesktopsIdentity() {
        assertEquals("bab208cb-a929-55cd-a091-c414d0e03864", Ids.dailyMixId())
    }

    @Test fun libraryIdsMatchGo() {
        assertEquals("6e55280b-fa0f-50d8-952c-6b0c7384b653", Ids.libraryId("abc123"))
        assertEquals("12f21daa-677d-585d-9347-eb0518710a7d", Ids.libraryId("0123456789abcdef"))
    }

    @Test fun deriveIdIsNotStandardUuidV5() {
        // java.util.UUID.nameUUIDFromBytes is MD5 (v3) and RFC UUIDv5 hashes raw namespace bytes: both differ.
        assertNotEquals(java.util.UUID.nameUUIDFromBytes("daily-mix".toByteArray()).toString(), Ids.dailyMixId())
        assertEquals(Ids.dailyMixId(), Ids.deriveId(Ids.DAILY_MIX_NS, "daily-mix"))
    }

    // TestDailyMixIDIsFixed, TestIDsAreStableAndDistinct
    @Test fun dailyMixHasOneIdentityWhichIsNotALibrarys() {
        assertEquals(Ids.dailyMixId(), Ids.dailyMixId())
        assertEquals(36, Ids.dailyMixId().length)
        assertNotEquals(Ids.libraryId("daily-mix"), Ids.dailyMixId())
    }

    @Test fun idsAreStableAndDistinct() {
        val first = Ids.newId(); val second = Ids.newId()
        assertNotEquals(first, second)
        assertEquals(36, first.length)
        assertEquals('4', first[14]) // version 4
        assertTrue(first[19] in "89ab") // RFC 4122 variant
        val local = Ids.libraryId("local-1")
        assertEquals(local, Ids.libraryId("local-1"))
        assertNotEquals(local, Ids.libraryId("local-2"))
    }

    @Test fun rfc3339NanoMatchesGoFormatting() {
        // Go's time.RFC3339Nano on a UTC time: fractional digits trimmed of trailing zeros, no fraction at all if zero.
        assertEquals("2026-10-30T10:00:00.123456789Z", Rfc3339Nano.format(Instant.parse("2026-10-30T10:00:00.123456789Z")))
        assertEquals("2026-10-30T10:00:00.5Z", Rfc3339Nano.format(Instant.parse("2026-10-30T10:00:00.500Z")))
        assertEquals("2026-10-30T10:00:00.000001Z", Rfc3339Nano.format(Instant.parse("2026-10-30T10:00:00.000001Z")))
        assertEquals("2026-10-30T10:00:00Z", Rfc3339Nano.format(Instant.parse("2026-10-30T10:00:00Z")))
        assertEquals("0001-01-01T00:00:00Z", Rfc3339Nano.format(Rfc3339Nano.ZERO))
    }

    @Test fun rfc3339NanoRoundTripKeepsEveryNanosecond() {
        for (s in listOf("2026-10-30T10:00:00.123456789Z", "2026-10-30T10:00:00.000000001Z", "2026-10-30T10:00:00.1Z", "2026-02-28T23:59:59.999999999Z")) {
            val t = Rfc3339Nano.parse(s)
            assertEquals(s, Rfc3339Nano.format(t))
            assertEquals(t, Rfc3339Nano.parse(Rfc3339Nano.format(t)))
        }
        // An offset (the Go desktop writes local time with an offset) means the same instant.
        val withOffset = Rfc3339Nano.parse("2026-10-30T13:00:00.123456789+03:00")
        assertEquals(Instant.parse("2026-10-30T10:00:00.123456789Z"), withOffset)
        assertEquals("2026-10-30T10:00:00.123456789Z", Rfc3339Nano.format(withOffset))
        assertTrue(Rfc3339Nano.parse("0001-01-01T00:00:00Z").isZero)
    }

    @Test fun subSecondOrderingIsKept() {
        // Two edits in the same second are ordered by the fraction.
        val a = Rfc3339Nano.parse("2026-10-30T10:00:00.100Z"); val b = Rfc3339Nano.parse("2026-10-30T10:00:00.900Z")
        assertTrue(b.isAfter(a))
        val c = Rfc3339Nano.parse("2026-10-30T10:00:00.000000001Z")
        assertTrue(c.isAfter(Rfc3339Nano.parse("2026-10-30T10:00:00Z")))
    }

    @Test fun wireJsonCarriesNanosecondsAndOmitsEmpties() {
        val lib = Library(id = "x", kind = "custom", name = "Gym", updatedAt = Instant.parse("2026-10-30T10:00:00.123456789Z"))
        assertEquals(
            """{"id":"x","kind":"custom","name":"Gym","updated_at":"2026-10-30T10:00:00.123456789Z"}""",
            SyncJson.encodeToString(Library.serializer(), lib),
        )
        val e = Event("e1", trackFor("ytm", "v", "", "T", "A", 0), 5, 0, false, "", Instant.parse("2026-10-30T10:00:00Z"))
        // completed and played_ms are always written (no omitempty in Go); duration_ms and device are not.
        assertEquals(
            """{"client_event_id":"e1","track":{"provider":"ytm","provider_track_id":"v","title":"T","artist":"A"},"played_ms":5,"completed":false,"played_at":"2026-10-30T10:00:00Z"}""",
            SyncJson.encodeToString(Event.serializer(), e),
        )
    }
}
