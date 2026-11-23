package com.muratkirazkaya.meiro.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lyrics tab (`tracktabs.go` `GetLyrics`) against real signed-out responses recorded by
 * `tools/record-ytm-fixtures.sh lyrics`: the `next` of "Instant Crush" names its lyrics page; that page read as the
 * web client has the plain text and its footer, read as ANDROID_MUSIC the timed lines; the related seed's page says
 * "Lyrics not available".
 */
class YtmLyricsTest {
    private fun fixture(name: String) = YtmLyricsTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().decodeToString()

    @Test fun nextNamesTheLyricsPage() {
        assertEquals("MPLYt_K8qWMWVqXGi-5", YtmRelated.tabBrowseId(fixture("ytm_next_lyrics_seed.json"), YtmLyricsParse.LYRICS_PAGE))
        assertEquals("MPLYt_7ltM34kr0mH-10", YtmRelated.tabBrowseId(fixture("ytm_next_related_seed.json"), YtmLyricsParse.LYRICS_PAGE))
    }

    @Test fun webPageHasThePlainTextAndItsSource() {
        val (text, footer) = YtmLyricsParse.plain(fixture("ytm_browse_lyrics.json"))
        assertTrue(text.lines().size > 40)
        assertTrue(footer, footer.startsWith("Source: "))
        assertTrue(YtmLyricsParse.timed(fixture("ytm_browse_lyrics.json")).isEmpty())
    }

    @Test fun androidPageHasTheTimedLines() {
        val lines = YtmLyricsParse.timed(fixture("ytm_browse_lyrics_android.json"))
        assertEquals(82, lines.size)
        assertEquals(0L to "♪", lines[0].atMs to lines[0].text)
        assertEquals(26_520L, lines[1].atMs)
        assertEquals(295_640L, lines.last().atMs)
        assertEquals(lines.sortedBy { it.atMs }, lines)
        assertTrue(lines.drop(1).all { it.text == it.text.trim() })
    }

    @Test fun pageWithoutLyricsGivesNothing() {
        assertEquals("" to "", YtmLyricsParse.plain(fixture("ytm_browse_lyrics_none.json")))
        assertTrue(YtmLyricsParse.timed(fixture("ytm_browse_lyrics_none.json")).isEmpty())
    }

    @Test fun startTimesAsNumbersToo() {
        val lines = YtmLyricsParse.timed("""{"a":[{"x":{"timedLyricsData":[{"lyricLine":" one ","cueRange":{"startTimeMilliseconds":1500}},{"lyricLine":"bad","cueRange":{}}]}}]}""")
        assertEquals(listOf(1_500L to "one"), lines.map { it.atMs to it.text })
    }
}
