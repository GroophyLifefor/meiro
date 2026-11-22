package com.muratkirazkaya.meiro.search

import com.muratkirazkaya.meiro.data.Match
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.parseDurationMs
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.Ref
import org.json.JSONObject

/** Port of the provider merge in the desktop's `search.go` and of `soundcloud/parse.go`. */
object SearchMerge {
    /** How many YouTube Music rows come before each SoundCloud track YouTube Music does not have (`soundCloudEvery`). */
    const val SOUNDCLOUD_EVERY = 3

    /**
     * `mergeProviders`: a YouTube Music track keeps its place and gains a SoundCloud reference when a SoundCloud track is
     * the same recording (`match.Match`); a SoundCloud track that matches nothing is a row of its own, put among the
     * YouTube Music rows ([interleave]). Titles, artists and artwork stay YouTube Music's.
     */
    fun mergeProviders(ytm: List<MusicItem>, sc: List<MusicItem>): List<MusicItem> {
        if (sc.isEmpty()) return ytm
        if (ytm.isEmpty()) return sc
        val merged = ytm.toMutableList()
        val matched = BooleanArray(merged.size)
        val own = ArrayList<MusicItem>()
        for (track in sc) {
            if (!track.isTrack()) continue
            val i = matchingTrack(merged, matched, track)
            if (i < 0) { own += track; continue }
            matched[i] = true
            merged[i] = addSourceRef(merged[i], track)
        }
        return interleave(merged, own, SOUNDCLOUD_EVERY)
    }

    /** One of [extra] after every [every] rows of [items], in order, and the ones left over after the last row. */
    fun <T> interleave(items: List<T>, extra: List<T>, every: Int): List<T> {
        if (extra.isEmpty()) return items
        val rest = ArrayDeque(extra)
        val out = ArrayList<T>(items.size + extra.size)
        items.forEachIndexed { i, item ->
            out += item
            if ((i + 1) % every == 0 && rest.isNotEmpty()) out += rest.removeFirst()
        }
        out.addAll(rest)
        return out
    }

    private fun matchingTrack(items: List<MusicItem>, matched: BooleanArray, track: MusicItem): Int {
        val other = matchTrack(track)
        for (i in items.indices) {
            if (matched[i] || !items[i].isTrack()) continue
            if (Match.match(matchTrack(items[i]), other) == Match.Status.MATCHED) return i
        }
        return -1
    }

    /** The title, the artist the byline names (or the first part of the subtitle) and the length. */
    fun matchTrack(item: MusicItem): Match.Track {
        val artist = item.artist.ifEmpty { item.subtitle.substringBefore(" • ") }
        return Match.Track(item.title, artist, parseDurationMs(item.duration))
    }

    /** `addSourceRef`: [item] keeps its fields and gains the source [other] came from; its own source is left to the default. */
    fun addSourceRef(item: MusicItem, other: MusicItem): MusicItem {
        val ref = other.firstRef() ?: return item
        if (item.sources.any { it.source == ref.source } || item.firstRef()?.source == ref.source && item.sources.isEmpty()) return item
        val sources = item.sources.ifEmpty { listOfNotNull(item.firstRef()) } + ref
        return item.copy(sources = sources, source = "")
    }

    /** `filterLocal`: the tracks whose title, artist or subtitle holds the query, compared in lower case. */
    fun filterLocal(items: List<MusicItem>, query: String): List<MusicItem> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return items
        return items.filter { it.title.lowercase().contains(needle) || it.artist.lowercase().contains(needle) || it.subtitle.lowercase().contains(needle) }
    }
}

/** SoundCloud search through the bundled yt-dlp (`soundcloud/client.go`, `parse.go`). */
object SoundCloudSearch {
    /** `searchLimit`. */
    const val LIMIT = 50

    fun args(query: String): List<String> = listOf("--flat-playlist", "--dump-single-json", "--no-warnings", "scsearch$LIMIT:$query")

    /** `GetRelated` (soundcloud/client.go:90): the page yt-dlp reads a track's recommendations from (`relatedURL`). */
    fun relatedUrl(pageUrl: String): String {
        val t = pageUrl.trimEnd('/')
        return if (t.endsWith("/recommended")) t else "$t/recommended"
    }

    fun relatedArgs(pageUrl: String): List<String> = listOf("--flat-playlist", "--dump-single-json", "--no-warnings", relatedUrl(pageUrl))

    /** yt-dlp's flat JSON to tracks: entries with neither an id nor a page URL are skipped. */
    fun parse(json: String): List<MusicItem> {
        val o = JSONObject(json)
        val entries = o.optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<MusicItem>(entries.length())
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.optString("id").takeUnless { e.isNull("id") }.orEmpty()
            val page = e.optString("webpage_url").takeUnless { e.isNull("webpage_url") }.orEmpty()
                .ifEmpty { e.optString("url").takeUnless { e.isNull("url") }.orEmpty() }
            if (id.isEmpty() && page.isEmpty()) continue
            val uploader = e.optString("uploader").takeUnless { e.isNull("uploader") }.orEmpty()
            out += MusicItem(
                source = Source.SOUNDCLOUD, id = id, url = page,
                title = e.optString("title").takeUnless { e.isNull("title") }.orEmpty(),
                subtitle = uploader, artist = uploader, artists = uploader,
                duration = durationText(e.optDouble("duration", 0.0)),
                thumbnail = thumbnail(e),
            )
        }
        return out
    }

    /**
     * The track's artwork, when the flat listing carries one (the desktop leaves SoundCloud search rows without; the
     * phone's rows show a cover, so the largest listed thumbnail is taken).
     */
    private fun thumbnail(e: JSONObject): String {
        val list = e.optJSONArray("thumbnails") ?: return ""
        var best = ""; var bestW = -1
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val url = t.optString("url")
            val w = t.optInt("width", 0)
            if (url.startsWith("http") && w >= bestW) { best = url; bestW = w }
        }
        return best
    }

    /** "3:42" or "1:02:03"; "" for an unknown length. */
    fun durationText(seconds: Double): String {
        if (seconds.isNaN() || seconds <= 0) return ""
        val total = seconds.toInt()
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
