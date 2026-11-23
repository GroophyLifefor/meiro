package com.muratkirazkaya.meiro.youtube

/**
 * A page of the queue YouTube Music generates after a track (`youtube/upnext.go` `upNextResult`): its items in order,
 * the token of the next page ("" at the end) and the id of the queue's playlist (the track's own mix, `RDAMVM<id>`).
 */
data class UpNextPage(val items: List<MusicItem>, val continuation: String, val playlistId: String)

/** Reading `next` responses for the generated queue (`upnext.go`); pure, so the recorded fixtures test it. */
object YtmUpNext {
    fun parse(text: String): UpNextPage = result(OrderedJson.parse(text))

    internal fun result(root: Any?): UpNextPage {
        val parts = Renderers.browseParts(root, false)
        return UpNextPage(parts.items, continuationToken(root), playlistId(root))
    }

    private val PANELS = listOf("playlistPanelRenderer", "playlistPanelContinuation")

    /** `upNextPlaylistID`: the first queue panel's `playlistId`. */
    fun playlistId(root: Any?): String {
        fun walk(v: Any?): String {
            when (v) {
                is List<*> -> for (c in v) walk(c).let { if (it.isNotEmpty()) return it }
                is JObj -> {
                    for (k in PANELS) (v[k] as? JObj)?.str("playlistId")?.takeIf { it.isNotEmpty() }?.let { return it }
                    for ((_, m) in v.members) walk(m).let { if (it.isNotEmpty()) return it }
                }
            }
            return ""
        }
        return walk(root)
    }

    /** `upNextContinuationToken`: the panel's own `continuation`, else its next(-radio) continuation data. */
    fun continuationToken(root: Any?): String {
        fun fromPanel(p: JObj): String {
            p.str("continuation")?.takeIf { it.isNotEmpty() }?.let { return it }
            for (c in p.arr("continuations").orEmpty()) {
                val o = c as? JObj ?: continue
                for (k in listOf("nextRadioContinuationData", "nextContinuationData")) {
                    o.obj(k)?.str("continuation")?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
            return ""
        }
        fun walk(v: Any?): String {
            when (v) {
                is List<*> -> for (c in v) walk(c).let { if (it.isNotEmpty()) return it }
                is JObj -> {
                    for (k in PANELS) (v[k] as? JObj)?.let { fromPanel(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
                    for ((_, m) in v.members) walk(m).let { if (it.isNotEmpty()) return it }
                }
            }
            return ""
        }
        return walk(root)
    }

    /**
     * `automixPlaylistPayload`: the members of the automix link's `watchPlaylistEndpoint` (the playlist and params a bare
     * `next` for a track points its queue at), or null when the response has none.
     */
    fun automixPayload(root: Any?): List<Pair<String, Any?>>? {
        fun walk(v: Any?): List<Pair<String, Any?>>? {
            when (v) {
                is List<*> -> for (c in v) walk(c)?.let { return it }
                is JObj -> {
                    val endpoint = v.obj("automixPreviewVideoRenderer")?.obj("content")?.obj("automixPlaylistVideoRenderer")
                        ?.obj("navigationEndpoint")?.obj("watchPlaylistEndpoint")
                    if (endpoint != null) return endpoint.members
                    for ((_, m) in v.members) walk(m)?.let { return it }
                }
            }
            return null
        }
        return walk(root)
    }
}
