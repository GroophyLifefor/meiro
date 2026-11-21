package com.muratkirazkaya.meiro.playback

/**
 * Port of `playerror.go`: what yt-dlp, the player and the network say, turned into a reason the listener can act
 * on. The first rule whose phrase the raw error holds wins, so the narrower reasons come before the general ones.
 * The raw error goes to the log. Messages are the desktop's, in English.
 */
object PlayError {
    /** What yt-dlp says when YouTube gave it no audio to take, which an old yt-dlp is the usual reason for. */
    val FORMAT_UNAVAILABLE = listOf("requested format is not available", "no video formats found")

    private class Rule(val phrases: List<String>, val message: String)

    private val rules = listOf(
        Rule(
            listOf("not available in your country", "blocked it in your country", "blocked in your country", "geo restrict", "geo-restrict"),
            "This track is not available in your region.",
        ),
        Rule(
            listOf("confirm your age", "age-restricted", "age restricted", "inappropriate for some users"),
            "This track is age-restricted. Sign in to play it.",
        ),
        Rule(
            listOf("not a bot", "sign in to confirm", "login required", "use --cookies"),
            "YouTube wants you to sign in before it plays this track.",
        ),
        Rule(listOf("private video", "this video is private"), "This track is private."),
        Rule(
            listOf("premium", "members-only", "members only", "join this channel"),
            "This track is only for Premium or channel members.",
        ),
        Rule(
            listOf(
                "has been removed", "video unavailable", "this video is unavailable", "no longer available", "does not exist",
                "account associated with this video has been terminated", "copyright",
            ),
            "This track is no longer available.",
        ),
        Rule(FORMAT_UNAVAILABLE, "YouTube did not offer audio for this track. Try again later, or update Meiro."),
        Rule(listOf("cannot be saved as a download"), "This track streams in segments, which Meiro cannot download yet."),
        Rule(listOf("offline: this track is not downloaded"), "You are offline and this track is not downloaded, so it cannot play."),
        Rule(listOf("playing needs ffmpeg"), "Playing needs ffmpeg and yt-dlp, and one of them was not found."),
        Rule(
            listOf(
                "name resolution", "no such host", "network is unreachable", "no route to host", "timed out", "timeout",
                "deadline exceeded", "connection reset", "connection refused", "connection closed", "unable to download",
                "failed to resolve", "temporary failure", "end of file", "i/o error", "server returned 5", "transporterror",
            ),
            "Could not reach YouTube. Check your connection.",
        ),
        Rule(
            listOf(
                "server returned 403", "server returned 404", "server returned 410", "403 forbidden", "404 not found",
                "410 gone", "http error 403", "http error 404", "http error 410",
            ),
            "YouTube stopped serving this track's audio.",
        ),
        Rule(
            listOf("invalid data found", "unsupported", "no such file", "audio device"),
            "This track's audio could not be played.",
        ),
    )

    fun isFormatUnavailable(raw: String): Boolean {
        val lower = raw.lowercase()
        return FORMAT_UNAVAILABLE.any { lower.contains(it) }
    }

    /** The short reason a track did not play, for [raw]. An error it does not know is shown as its first line. */
    fun message(raw: String): String {
        val lower = raw.lowercase()
        for (rule in rules) for (phrase in rule.phrases) if (lower.contains(phrase)) return rule.message
        val line = raw.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (line.isNotEmpty()) "This track could not be played: $line" else "This track could not be played."
    }
}
