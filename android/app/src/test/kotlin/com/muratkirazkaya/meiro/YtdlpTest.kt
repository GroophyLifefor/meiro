package com.muratkirazkaya.meiro

import org.junit.Assert.*
import org.junit.Test

class YtdlpTest {
    @Test fun visionosArgs() {
        val a = ytmArgs("abc", true)
        assertEquals(
            listOf("-f", "bestaudio", "-g", "--no-playlist", "--extractor-args",
                "youtube:skip=hls,dash;player_client=visionos", "https://www.youtube.com/watch?v=abc"), a)
    }

    @Test fun defaultArgsDropVisionos() {
        assertEquals("youtube:skip=hls,dash", ytmArgs("abc", false)[5])
    }

    @Test fun firstLine() {
        assertEquals("https://x/y", firstUrlLine("\n https://x/y \nsecond\n"))
        assertNull(firstUrlLine("  \n"))
    }

    @Test fun lastError() {
        val s = "WARNING: x\nERROR: first\nsome noise\nERROR: [youtube] boom\n"
        assertEquals("ERROR: [youtube] boom", lastErrorLine(s))
        assertNull(lastErrorLine("WARNING: only"))
    }

    @Test fun soundCloudArgs() {
        assertEquals(listOf("-f", "bestaudio", "--dump-single-json", "--no-warnings", "u"), soundCloudArgs("u"))
    }

    @Test fun parseSc() {
        val i = parseSoundCloudJson("""{"url":"https://a/b.m3u8","duration":123.4,"protocol":"m3u8_native"}""")
        assertEquals("https://a/b.m3u8", i.url); assertEquals(123.4, i.durationSec!!, 0.001); assertTrue(i.hls)
        val j = parseSoundCloudJson("""{"url":"https://a/b.mp3"}""")
        assertNull(j.durationSec); assertFalse(j.hls)
    }

    @Test(expected = IllegalArgumentException::class) fun parseScNoUrl() { parseSoundCloudJson("{}") }
}
