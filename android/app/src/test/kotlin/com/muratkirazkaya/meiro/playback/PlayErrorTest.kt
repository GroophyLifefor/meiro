package com.muratkirazkaya.meiro.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from playerror.go: every rule's message, the order (narrow before general), and the fallback. */
class PlayErrorTest {
    private fun m(raw: String) = PlayError.message(raw)

    @Test fun eachRuleHasItsMessage() {
        assertEquals("This track is not available in your region.", m("ERROR: The uploader has not made this video available in your country; blocked it in your country"))
        assertEquals("This track is age-restricted. Sign in to play it.", m("ERROR: Sign in to confirm your age"))
        assertEquals("YouTube wants you to sign in before it plays this track.", m("ERROR: Sign in to confirm you're not a bot"))
        assertEquals("This track is private.", m("ERROR: Private video. Sign in if you've been granted access"))
        assertEquals("This track is only for Premium or channel members.", m("ERROR: Join this channel to get access"))
        assertEquals("This track is no longer available.", m("ERROR: Video unavailable"))
        assertEquals("YouTube did not offer audio for this track. Try again later, or update Meiro.", m("ERROR: Requested format is not available"))
        assertEquals("This track streams in segments, which Meiro cannot download yet.", m("this track cannot be saved as a download"))
        assertEquals("You are offline and this track is not downloaded, so it cannot play.", m("offline: this track is not downloaded"))
        assertEquals("Playing needs ffmpeg and yt-dlp, and one of them was not found.", m("playing needs ffmpeg and yt-dlp on PATH"))
        assertEquals("Could not reach YouTube. Check your connection.", m("ERROR: Unable to download webpage: <urlopen error [Errno -2] Name or service not known> timed out"))
        assertEquals("YouTube stopped serving this track's audio.", m("HTTP error 403 (ERROR_CODE_IO_BAD_HTTP_STATUS)"))
        assertEquals("This track's audio could not be played.", m("Invalid data found when processing input"))
    }

    @Test fun theNarrowReasonWinsOverTheGeneralOne() {
        // "timed out" is in the network rule, "premium" in an earlier one: the earlier rule wins.
        assertEquals("This track is only for Premium or channel members.", m("premium content, request timed out"))
        // A 403 that mentions a region is a region problem, not a dead URL.
        assertEquals("This track is not available in your region.", m("HTTP error 403: blocked in your country"))
    }

    @Test fun anUnknownErrorShowsItsFirstLine() {
        assertEquals("This track could not be played: something odd", m("something odd\nsecond line"))
        assertEquals("This track could not be played.", m(""))
        assertEquals("This track could not be played.", m("   \nx"))
    }

    @Test fun formatUnavailable() {
        assertTrue(PlayError.isFormatUnavailable("ERROR: Requested format is not available. Use --list-formats"))
        assertTrue(PlayError.isFormatUnavailable("No video formats found!"))
        assertFalse(PlayError.isFormatUnavailable("video unavailable"))
    }
}
