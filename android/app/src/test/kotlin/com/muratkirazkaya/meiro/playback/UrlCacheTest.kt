package com.muratkirazkaya.meiro.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** The URL cache's rules (`internal/audio/urls.go`; the desktop has no test file for it, so these state its comments). */
class UrlCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var clock = 0L
    private val calls = AtomicInteger()

    @After fun end() { scope.coroutineContext[kotlinx.coroutines.Job]!!.let { (it as kotlinx.coroutines.CompletableJob).complete() } }

    private fun cache(limit: Int = 8, timeoutMs: Long = 90_000, resolve: suspend (String) -> String = { calls.incrementAndGet(); "url-$it-${calls.get()}" }) =
        UrlCache(scope, limit = limit, timeoutMs = timeoutMs, now = { clock }, resolve = resolve)

    @Test fun resolvesOnceAndReusesUntilTheTtl() = runBlocking {
        val c = cache()
        val first = c.get("a")
        assertEquals(first, c.get("a")); assertEquals(1, calls.get())
        clock = UrlCache.TTL_MS // exactly the TTL: still fresh
        assertEquals(first, c.get("a")); assertEquals(1, calls.get())
        clock = UrlCache.TTL_MS + 1 // past it: resolved again
        assertTrue(c.get("a") != first); assertEquals(2, calls.get())
    }

    @Test fun ttlIsAtMostThirtyMinutes() { assertEquals(30 * 60 * 1000L, UrlCache.TTL_MS); assertEquals(8, UrlCache.LIMIT); assertEquals(90_000L, UrlCache.RESOLVE_TIMEOUT_MS) }

    @Test fun keepsTheNewestEightAndEvictsTheOldest() = runBlocking {
        val c = cache()
        for (i in 0 until 9) { clock = i * 10L; c.get("t$i") }
        assertEquals(8, c.size())
        assertNull("the oldest entry should be gone", c.cached("t0"))
        for (i in 1..8) assertTrue("t$i should be kept", c.cached("t$i") != null)
        val before = calls.get()
        c.get("t0"); assertEquals("an evicted track is resolved again", before + 1, calls.get())
    }

    @Test fun concurrentRequestsShareOneResolve() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val c = cache { gate.await(); calls.incrementAndGet(); "u" }
        val all = List(5) { scope.async { c.get("a") } }
        delay(100)
        assertEquals("nothing finished before the resolve did", 0, calls.get())
        gate.complete(Unit)
        assertEquals(List(5) { "u" }, all.map { it.await() })
        assertEquals(1, calls.get())
    }

    @Test fun aCancelledAskerLeavesTheResolveForTheNext() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val c = cache { gate.await(); calls.incrementAndGet(); "u" }
        val first = scope.async { c.get("a") }
        delay(50); first.cancel()
        gate.complete(Unit)
        assertEquals("u", withTimeout(5000) { c.get("a") })
        assertEquals("the second asker used the resolve the first started", 1, calls.get())
    }

    @Test fun aFailureIsNotRemembered() = runBlocking {
        val n = AtomicInteger()
        val c = cache { if (n.incrementAndGet() == 1) throw ResolveException("ERROR: boom") else "ok" }
        try { c.get("a"); fail("expected a failure") } catch (e: ResolveException) { assertEquals("ERROR: boom", e.message) }
        assertEquals(0, c.size())
        assertEquals("a retry right after the failure resolves anew", "ok", c.get("a"))
        assertEquals(2, n.get())
    }

    @Test fun waitersOfAFailedResolveAllSeeTheFailure() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val c = cache { gate.await(); throw ResolveException("nope") }
        val all = List(3) { scope.async { runCatching { c.get("a") } } }
        delay(50); gate.complete(Unit)
        assertTrue(all.all { it.await().exceptionOrNull() is ResolveException })
    }

    @Test fun forgetMakesTheNextGetResolveAgain() = runBlocking {
        val c = cache()
        val first = c.get("a")
        c.forget("a")
        assertNull(c.cached("a"))
        assertTrue(c.get("a") != first); assertEquals(2, calls.get())
    }

    @Test fun aResolveThatRunsPastTheTimeoutFailsAndIsNotRemembered() = runBlocking {
        val c = cache(timeoutMs = 100) { delay(10_000); "late" }
        try { c.get("a"); fail("expected a timeout") } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("timed out")) }
        assertEquals(0, c.size())
    }
}
