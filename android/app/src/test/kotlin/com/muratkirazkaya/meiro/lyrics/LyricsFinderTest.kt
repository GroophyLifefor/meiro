package com.muratkirazkaya.meiro.lyrics

import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.YtmLyrics
import com.muratkirazkaya.meiro.youtube.YtmLyricsParse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** `findLyrics` (lyrics.go) and the per-track loads behind the two tabs, with fake sources (no network). */
class LyricsFinderTest {
    private fun fixture(name: String) = LyricsFinderTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

    private val track = QItem(Source.YTM, "khnokW3Mw24", "Instant Crush", "Daft Punk", "5:38", album = "Random Access Memories")
    private val recorded: YtmLyrics by lazy {
        val (text, footer) = YtmLyricsParse.plain(fixture("ytm_browse_lyrics.json"))
        YtmLyrics(text, footer, YtmLyricsParse.timed(fixture("ytm_browse_lyrics_android.json")))
    }
    private val lrcTimed = LrcLyrics("plain from lrclib", listOf(LyricLine(1_000, "a"), LyricLine(2_000, "b")))

    private fun finder(ytm: suspend (String) -> YtmLyrics, lrclib: suspend (LrcQuery) -> LrcLyrics?) = LyricsFinder(ytm, lrclib)

    @Test fun youtubeTimedLinesWinAndLrclibIsNotAsked() = runBlocking {
        var asked = 0
        val f = finder({ recorded }, { asked++; lrcTimed }).find(track, 338_000)
        assertEquals(82, f.synced.size)
        assertEquals(recorded.footer, f.footer)
        assertEquals(recorded.description.trim(), f.text)
        assertEquals(0, asked)
    }

    @Test fun noYoutubeLyricsAsksLrclibWithTheTrack() = runBlocking {
        var q: LrcQuery? = null
        val f = finder({ YtmLyrics("", "", emptyList()) }, { q = it; lrcTimed }).find(track, 0)
        assertEquals(LrcQuery("Instant Crush", "Daft Punk", "Random Access Memories", 338_000), q) // the row's length
        assertEquals(lrcTimed.synced, f.synced)
        assertEquals("Source: LRCLIB", f.footer)
        assertEquals("plain from lrclib".isNotEmpty(), f.text.isNotEmpty())
        assertEquals("a\nb", f.text) // timed lines stand in for LRCLIB's plain text
    }

    @Test fun youtubePlainTextIsKeptWhenLrclibHasOnlyPlain() = runBlocking {
        val f = finder({ YtmLyrics("yt plain", "Source: LyricFind", emptyList()) }, { LrcLyrics("lrc plain", emptyList()) }).find(track, 1)
        assertEquals("yt plain", f.text)
        assertEquals("Source: LyricFind", f.footer)
        assertTrue(f.synced.isEmpty())
    }

    @Test fun lrclibPlainWhenNothingElse() = runBlocking {
        val f = finder({ throw IOException("no tab") }, { LrcLyrics("lrc plain", emptyList()) }).find(track, 1)
        assertEquals("lrc plain" to "Source: LRCLIB", f.text to f.footer)
    }

    @Test fun soundCloudGoesStraightToLrclib() = runBlocking {
        var ytmAsked = false
        val sc = QItem(Source.SOUNDCLOUD, "https://soundcloud.com/a/b", "Song", "Artist", "3:00")
        val f = finder({ ytmAsked = true; recorded }, { lrcTimed }).find(sc, 180_000)
        assertEquals(false, ytmAsked)
        assertEquals(2, f.synced.size)
        // A SoundCloud track that also has a YouTube Music copy asks YouTube Music by that copy's id.
        var id = ""
        finder({ id = it; recorded }, { null }).find(sc.copy(refs = mapOf(Source.YTM to "vid")), 0)
        assertEquals("vid", id)
    }

    @Test fun noneAnywhereIsEmptyAndInstrumentalIsSaid() = runBlocking {
        assertTrue(finder({ YtmLyrics("", "", emptyList()) }, { null }).find(track, 1).empty())
        val f = finder({ throw IOException("x") }, { LrcLyrics("", emptyList(), instrumental = true) }).find(track, 1)
        assertTrue(f.empty() && f.instrumental)
    }

    @Test fun aFailedLookupWithNothingFoundThrows() = runBlocking {
        try {
            finder({ YtmLyrics("", "", emptyList()) }, { throw IOException("lrclib down") }).find(track, 1)
            fail("no error")
        } catch (e: IOException) { assertEquals("lrclib down", e.message) }
        // With YouTube Music's plain text the failure does not matter.
        assertEquals("yt", finder({ YtmLyrics("yt", "", emptyList()) }, { throw IOException("down") }).find(track, 1).text)
    }

    private suspend fun <T> CoroutineScope.until(get: () -> T?, ok: (T?) -> Boolean): T? {
        withTimeout(5_000) { while (!ok(get())) delay(5) }
        return get()
    }

    @Test fun lyricsLoadOncePerTrackAndAFailureIsTriedAgain() = runBlocking {
        var calls = 0
        var fail = true
        val tabs = TrackTabs(this, finder({ calls++; if (fail) throw IOException("x") else recorded }, { if (fail) throw IOException("y") else null }), { emptyList() })
        tabs.loadLyrics(track, 0)
        assertEquals(LyricsState.Failed, until({ tabs.lyrics.value }) { it?.second != LyricsState.Loading }?.second)
        fail = false
        tabs.loadLyrics(track, 0)
        val s = until({ tabs.lyrics.value }) { it?.second is LyricsState.Found }!!
        assertEquals(track.key, s.first)
        tabs.loadLyrics(track, 0) // loaded: not asked again
        delay(20)
        assertEquals(2, calls)
    }

    @Test fun aNewTrackCancelsTheLoadBefore() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val tabs = TrackTabs(this, finder({ id -> if (id == "first") gate.await(); YtmLyrics("text of $id", "", emptyList()) }, { null }), { emptyList() })
        tabs.loadLyrics(track.copy(key = "first"), 0)
        delay(10)
        tabs.loadLyrics(track.copy(key = "second"), 0)
        val s = until({ tabs.lyrics.value }) { it?.second is LyricsState.Found }!!
        assertEquals("second", s.first)
        gate.complete(Unit)
        delay(20)
        assertEquals("second", tabs.lyrics.value!!.first)
        assertEquals("text of second", ((tabs.lyrics.value!!.second) as LyricsState.Found).lyrics.text)
    }

    @Test fun relatedNeedsAYoutubeCopyAndSaysWhenEmpty() = runBlocking {
        var asked = ""
        val items = listOf(MusicItem(videoId = "r1", title = "One"))
        val tabs = TrackTabs(this, finder({ recorded }, { null }), { asked = it; if (it == "none") emptyList() else items })
        tabs.loadRelated(QItem(Source.SOUNDCLOUD, "https://soundcloud.com/x"))
        assertEquals(RelatedState.None, tabs.related.value!!.second)
        assertEquals("", asked)
        tabs.loadRelated(track)
        assertEquals(RelatedState.Found(items), until({ tabs.related.value }) { it?.second is RelatedState.Found }!!.second)
        assertEquals(track.key, asked)
        tabs.loadRelated(QItem(Source.YTM, "none"))
        assertEquals(RelatedState.None, until({ tabs.related.value }) { it?.second != RelatedState.Loading }!!.second)
    }
}
