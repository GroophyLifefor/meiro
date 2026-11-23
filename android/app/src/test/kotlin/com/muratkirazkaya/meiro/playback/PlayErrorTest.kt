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

    /** M6c: the phone shows each reason in Turkish (the mock's `playerr`); the same rules and order decide. */
    @Test fun turkishReasons() {
        fun t(raw: String) = PlayError.turkish(raw)
        assertEquals("Bu şarkı bulunduğun bölgede çalınamıyor.", t("ERROR: blocked it in your country"))
        assertEquals("Bu şarkı yaş sınırlı. Çalmak için giriş gerekiyor.", t("ERROR: Sign in to confirm your age"))
        assertEquals("YouTube bu şarkıyı çalmadan önce giriş istiyor.", t("ERROR: Sign in to confirm you're not a bot"))
        assertEquals("Bu şarkı gizli.", t("ERROR: Private video"))
        assertEquals("Bu şarkı yalnız Premium ya da kanal üyeleri için.", t("ERROR: Join this channel to get access"))
        assertEquals("Bu şarkı artık yok.", t("ERROR: Video unavailable"))
        assertEquals("YouTube bu şarkının sesini vermedi. Biraz sonra tekrar dene ya da Ayarlar'dan yt-dlp'yi güncelle.", t("ERROR: Requested format is not available"))
        assertEquals("Bu şarkı parça parça geliyor, Meiro onu henüz indiremiyor.", t("this track cannot be saved as a download"))
        assertEquals("Çevrimdışısın ve bu şarkı indirilmemiş, çalınamıyor.", t("offline: this track is not downloaded"))
        assertEquals("Çalmak için ffmpeg ve yt-dlp gerekiyor, biri bulunamadı.", t("playing needs ffmpeg"))
        assertEquals("YouTube'a ulaşılamadı. Bağlantını kontrol et.", t("Unable to download webpage: timed out"))
        assertEquals("YouTube bu şarkının sesini vermeyi kesti.", t("HTTP error 403 (ERROR_CODE_IO_BAD_HTTP_STATUS)"))
        assertEquals("Bu şarkının sesi çalınamadı.", t("Invalid data found when processing input"))
        assertEquals("Bu şarkı yalnız Premium ya da kanal üyeleri için.", t("premium content, request timed out"))
        assertEquals("Bu şarkı çalınamadı: something odd", t("something odd\nsecond line"))
        assertEquals("Bu şarkı çalınamadı.", t(""))
    }

    @Test fun theSkipSnack() {
        assertEquals("\u201CYolla\u201D geçildi: Bu şarkı artık yok", PlaybackEngine.skipNotice("Yolla", "Bu şarkı artık yok."))
    }

    @Test fun formatUnavailable() {
        assertTrue(PlayError.isFormatUnavailable("ERROR: Requested format is not available. Use --list-formats"))
        assertTrue(PlayError.isFormatUnavailable("No video formats found!"))
        assertFalse(PlayError.isFormatUnavailable("video unavailable"))
    }
}
