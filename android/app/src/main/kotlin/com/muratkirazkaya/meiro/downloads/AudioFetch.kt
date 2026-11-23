package com.muratkirazkaya.meiro.downloads

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** A download that could not finish; [message] is the reason for the log and the snack. */
class FetchException(message: String) : IOException(message)

/**
 * Saves a resolved audio stream to a file (`internal/audio` `Save`): the bytes as they are served, in their own
 * container, no re-encoding. A progressive stream comes in 10 MiB ranges with Chrome's User-Agent (YouTube serves
 * bounded ranges at full speed); an HLS stream (SoundCloud) is its init section and segments joined in order, which the
 * desktop cannot do (it refuses playlists). The file appears only when every byte has arrived; [progress] gets bytes
 * written and the total (-1 unknown).
 */
class AudioFetch(private val http: OkHttpClient = defaultHttp(), private val range: Long = RANGE) {

    /** Writes [url] to [base] plus the extension its media type calls for; returns that file. */
    suspend fun save(url: String, hls: Boolean, base: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        if (url.isBlank()) throw FetchException("no resolved audio URL to download")
        base.parentFile?.mkdirs()
        val temp = File(base.parentFile, ".part-" + base.name)
        temp.delete()
        try {
            val ext = temp.outputStream().buffered().use { out ->
                if (hls || isPlaylist(url, "")) saveHls(url, out, progress) else saveRanges(url, out, progress)
            }
            val dest = File(base.path + ext)
            dest.delete()
            if (!temp.renameTo(dest)) throw FetchException("could not keep the file")
            return dest
        } finally {
            temp.delete()
        }
    }

    private suspend fun saveRanges(url: String, out: OutputStream, progress: (Long, Long) -> Unit): String {
        var written = 0L
        var total = -1L
        var contentType = ""
        while (total < 0 || written < total) {
            currentCoroutineContext().ensureActive()
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT)
                .header("Range", "bytes=$written-${written + range - 1}").build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body ?: throw FetchException("download audio: the response was empty")
                when {
                    resp.code == 206 -> {
                        val m = Regex("""bytes (\d+)-(\d+)/(\d+)""").find(resp.header("Content-Range").orEmpty())
                        if (m == null || m.groupValues[1].toLong() != written) throw FetchException("download audio: unexpected range ${resp.header("Content-Range")}")
                        total = m.groupValues[3].toLong()
                    }
                    resp.code == 200 && written == 0L -> {}
                    else -> throw FetchException("download audio: unexpected status ${resp.code}")
                }
                if (written == 0L) contentType = resp.header("Content-Type").orEmpty()
                if (isPlaylist(url, contentType)) throw FetchException(PLAYLIST_REFUSED)
                val before = written
                val n = copy(body.byteStream(), out) { progress(before + it, total) }
                // A stream cut short can end without an error, so the declared length is the only proof.
                val declared = body.contentLength()
                if (declared >= 0 && n != declared) throw FetchException("download audio: got $n of $declared bytes")
                if (n == 0L) throw FetchException("download audio: the response was empty")
                if (resp.code == 200) total = n
                written += n
                progress(written, total)
            }
        }
        return extensionFor(contentType)
    }

    /** Joins an HLS media playlist's init section (`EXT-X-MAP`) and segments; a master playlist's first variant is used. */
    private suspend fun saveHls(url: String, out: OutputStream, progress: (Long, Long) -> Unit): String {
        var playlistUrl = url
        var text = get(playlistUrl)
        Hls.firstVariant(text, playlistUrl)?.let { playlistUrl = it; text = get(it) }
        val list = Hls.parse(text, playlistUrl)
        if (list.encrypted) throw FetchException("download audio: the stream is encrypted")
        if (list.segments.isEmpty()) throw FetchException("download audio: the playlist has no segments")
        var written = 0L
        val parts = listOfNotNull(list.init) + list.segments
        parts.forEachIndexed { i, part ->
            currentCoroutineContext().ensureActive()
            http.newCall(Request.Builder().url(part).header("User-Agent", USER_AGENT).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw FetchException("download audio: segment answered ${resp.code}")
                val body = resp.body ?: throw FetchException("download audio: empty segment")
                written += copy(body.byteStream(), out) {}
            }
            // The total is unknown for a playlist: progress counts parts, scaled to bytes so far.
            progress(written, if (i == parts.lastIndex) written else (written * parts.size / (i + 1)))
        }
        return list.extension
    }

    private fun get(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw FetchException("download audio: playlist answered ${resp.code}")
            resp.body?.string().orEmpty()
        }

    private suspend fun copy(input: java.io.InputStream, out: OutputStream, at: (Long) -> Unit): Long {
        val buf = ByteArray(64 * 1024)
        var n = 0L
        input.use {
            while (true) {
                currentCoroutineContext().ensureActive()
                val r = it.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                n += r
                at(n)
            }
        }
        return n
    }

    companion object {
        /** `downloadRange`. */
        const val RANGE = 10L shl 20
        /** `userAgent` of the desktop's cache: YouTube answers some clients' range requests with an error otherwise. */
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        const val PLAYLIST_REFUSED = "this stream is delivered in segments and cannot be saved as a download"

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

        fun isPlaylist(url: String, contentType: String): Boolean {
            val path = url.substringBefore('?').lowercase()
            if (path.endsWith(".m3u8")) return true
            return contentType.substringBefore(';').trim().lowercase() in
                setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
        }

        /** `audioURLExtension`. */
        fun extensionFor(contentType: String): String = when (contentType.substringBefore(';').trim().lowercase()) {
            "audio/webm", "video/webm" -> ".webm"
            "audio/mp4", "video/mp4" -> ".m4a"
            "audio/ogg", "application/ogg" -> ".ogg"
            "audio/opus" -> ".opus"
            "audio/aac", "audio/aacp" -> ".aac"
            "audio/flac", "audio/x-flac" -> ".flac"
            "audio/wav", "audio/x-wav", "audio/wave" -> ".wav"
            "audio/mpeg" -> ".mp3"
            else -> ".webm"
        }
    }
}

/** The parts of an HLS playlist a download needs. */
object Hls {
    data class Media(val init: String?, val segments: List<String>, val encrypted: Boolean, val extension: String)

    /** A master playlist's first variant URL, null for a media playlist. */
    fun firstVariant(text: String, base: String): String? {
        val lines = text.lines().map { it.trim() }
        val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (i < 0) return null
        return lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(base, it) }
    }

    fun parse(text: String, base: String): Media {
        var init: String? = null
        var encrypted = false
        val segs = mutableListOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MAP:") -> Regex("URI=\"([^\"]+)\"").find(line)?.let { init = resolve(base, it.groupValues[1]) }
                line.startsWith("#EXT-X-KEY:") -> if (!line.contains("METHOD=NONE")) encrypted = true
                line.isEmpty() || line.startsWith("#") -> {}
                else -> segs += resolve(base, line)
            }
        }
        val first = (segs.firstOrNull() ?: "").substringBefore('?').lowercase()
        val ext = when {
            init != null || first.endsWith(".m4s") || first.endsWith(".mp4") -> ".m4a"
            first.endsWith(".mp3") -> ".mp3"
            first.endsWith(".aac") -> ".aac"
            else -> ".ts"
        }
        return Media(init, segs, encrypted, ext)
    }

    fun resolve(base: String, ref: String): String = java.net.URI(base).resolve(ref.replace(" ", "%20")).toString()
}
