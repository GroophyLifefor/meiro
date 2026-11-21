package com.muratkirazkaya.meiro

import org.json.JSONObject

/** Pure helpers, kept free of Android types so they run in JVM unit tests. */

const val CHROME_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

fun youtubeWatchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

/** Mirrors internal/ytdlp/ytdlp.go resolve(): visionos client alone, or the default set. */
fun ytmArgs(videoId: String, jsless: Boolean): List<String> {
    var extractor = "youtube:skip=hls,dash"
    if (jsless) extractor += ";player_client=visionos"
    return listOf(
        "-f", "bestaudio", "-g", "--no-playlist",
        "--extractor-args", extractor,
        youtubeWatchUrl(videoId),
    )
}

fun soundCloudArgs(url: String): List<String> =
    listOf("-f", "bestaudio", "--dump-single-json", "--no-warnings", url)

/** First stdout line is the URL. Returns null when empty. */
fun firstUrlLine(stdout: String): String? =
    stdout.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

/** Last line starting with "ERROR:" in stderr, or null. */
fun lastErrorLine(stderr: String): String? =
    stderr.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("ERROR:") }

data class ScInfo(val url: String, val durationSec: Double?, val hls: Boolean)

fun parseSoundCloudJson(json: String): ScInfo {
    val o = JSONObject(json)
    val url = o.optString("url", "")
    require(url.isNotEmpty()) { "no url in JSON" }
    val duration = if (o.has("duration") && !o.isNull("duration")) o.getDouble("duration") else null
    val protocol = o.optString("protocol", "")
    val hls = protocol.contains("m3u8") || url.contains(".m3u8")
    return ScInfo(url, duration, hls)
}
