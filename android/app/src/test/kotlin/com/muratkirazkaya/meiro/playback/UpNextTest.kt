package com.muratkirazkaya.meiro.playback

import com.muratkirazkaya.meiro.data.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `ensureUpNext` and `withSuggestions` (upnext.go, recommendations.go) against a fake YouTube Music. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpNextTest {
    private fun t(k: String, why: String = "") = QItem(Source.YTM, k, "T$k", "A$k", "3:00", why = why)

    /** Answers every request at once (unconfined), with pages from [pages] by continuation. */
    private class Fake(val pages: Map<String, GeneratedPage>, val fail: Boolean = false) : UpNextSource {
        val asked = mutableListOf<Triple<String, String, String>>()
        override suspend fun page(videoId: String, playlistId: String, continuation: String): GeneratedPage {
            asked += Triple(videoId, playlistId, continuation)
            if (fail) error("offline")
            return pages[continuation] ?: GeneratedPage(emptyList(), "", "")
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun queue(vararg keys: String, at: Int = 0) = Queue().apply { replace(t(keys[at]), keys.map { t(it) }, at, "L") }

    @Test fun firstPageIsAskedAtOnceAndAppendedAfterTheChosenTracks() {
        val f = Fake(mapOf("" to GeneratedPage(listOf(t("a"), t("x"), t("y")), "tok1", "RDAMVMa")))
        val u = UpNext(scope, f)
        val q = queue("a", "b")
        u.reset("a")
        var done = 0
        u.ensure(true, q, q.current()) { _, failed -> assertFalse(failed); done++ }
        assertEquals(listOf(Triple("a", "", "")), f.asked)
        assertEquals(1, done)
        // "a" is already queued: only x and y are added, as generated tracks after the chosen two.
        assertEquals(listOf("a", "b", "x", "y"), q.items.map { it.key })
        assertEquals(2, q.recStart)
        assertEquals("RDAMVMa", u.playlistId)
        assertEquals("tok1", u.token)
    }

    @Test fun nextPageOnlyWhenTheGeneratedPartIsAlmostPlayedOut() {
        val f = Fake(mapOf("" to GeneratedPage(listOf(t("x"), t("y"), t("z")), "tok1", "PL"), "tok1" to GeneratedPage(listOf(t("w")), "tok2", "PL")))
        val u = UpNext(scope, f)
        val q = queue("a")
        u.reset("a")
        u.ensure(true, q, q.current()) { _, _ -> }
        u.ensure(true, q, q.current()) { _, _ -> } // 3 remain: nothing asked
        assertEquals(1, f.asked.size)
        q.moveTo(2) // y playing, z remains
        u.ensure(true, q, q.current()) { _, _ -> }
        assertEquals(Triple("a", "PL", "tok1"), f.asked[1])
        assertEquals(listOf("a", "x", "y", "z", "w"), q.items.map { it.key })
    }

    @Test fun aRepeatedTokenEndsTheListAndTheEndAsksAgainFromTheTrackPlaying() {
        val f = Fake(mapOf("" to GeneratedPage(listOf(t("x")), "tok1", "PL"), "tok1" to GeneratedPage(emptyList(), "tok1", "PL")))
        val u = UpNext(scope, f)
        val q = queue("a")
        u.reset("a")
        u.ensure(true, q, q.current()) { _, _ -> }
        u.ensure(true, q, q.current()) { _, _ -> } // remaining 1: continues with tok1, which answers tok1 again
        assertEquals("", u.token)
        q.moveTo(1) // x playing, nothing after it
        u.ensure(true, q, q.current()) { _, _ -> }
        assertEquals(Triple("x", "PL", ""), f.asked.last())
    }

    @Test fun nothingForSoundCloudOrWithAutoPlayOff() {
        val f = Fake(emptyMap())
        val u = UpNext(scope, f)
        val q = queue("a")
        u.reset("a")
        u.ensure(false, q, q.current()) { _, _ -> }
        val sc = QItem(Source.SOUNDCLOUD, "https://soundcloud.com/x/y", "S")
        u.ensure(true, Queue().apply { replace(sc, listOf(sc), 0, "") }, sc) { _, _ -> }
        assertTrue(f.asked.isEmpty())
    }

    @Test fun aFailureIsReportedAndClearsTheToken() {
        val u = UpNext(scope, Fake(emptyMap(), fail = true))
        val q = queue("a")
        u.reset("a")
        var failed = false
        u.ensure(true, q, q.current()) { _, f -> failed = f }
        assertTrue(failed)
        assertEquals("offline", u.error)
        assertEquals(listOf("a"), q.items.map { it.key })
    }

    @Test fun suggestionsGoFirstUpToFiveAndNeverTwice() {
        val picks = (1..7).map { t("m$it", why = "W$it") } + t("a")
        val f = Fake(mapOf("" to GeneratedPage(listOf(t("x"), t("m2")), "", "PL")))
        val u = UpNext(scope, f, { picks })
        val q = queue("a")
        u.reset("a")
        u.ensure(true, q, q.current()) { _, _ -> }
        assertEquals(listOf("a", "m1", "m2", "m3", "m4", "m5", "x"), q.items.map { it.key })
        assertEquals(setOf("m1", "m2", "m3", "m4", "m5"), u.suggested)
        assertEquals("W1", q.items[1].why)
        // The next fill takes the picks not used yet.
        assertEquals(listOf("m6", "m7"), u.withSuggestions(emptyList(), q, q.current()).map { it.key })
    }
}
