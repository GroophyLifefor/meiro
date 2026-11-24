package com.muratkirazkaya.meiro.youtube

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Whether the signed-in account has liked a track (`media.LikeStatus`). */
enum class LikeStatus { Unknown, Indifferent, Liked, Disliked }

/** M6h: the like button's statuses and writes as YouTube Music names them (`youtube/rating.go`). */
object YtmRating {
    /** `parseLikeStatus`: the name YouTube's like button gives a status. */
    fun parse(name: String?): LikeStatus = when (name) {
        "LIKE" -> LikeStatus.Liked
        "INDIFFERENT" -> LikeStatus.Indifferent
        "DISLIKE" -> LikeStatus.Disliked
        else -> LikeStatus.Unknown
    }

    /**
     * `likeStatus`: the like a row or a panel item carries in its menu (the like button among the menu's top-level
     * buttons); Unknown for an item whose menu has none.
     */
    fun of(renderer: JObj?): LikeStatus {
        val buttons = renderer?.obj("menu")?.obj("menuRenderer")?.arr("topLevelButtons") ?: return LikeStatus.Unknown
        for (b in buttons) {
            val s = parse((b as? JObj)?.obj("likeButtonRenderer")?.str("likeStatus"))
            if (s != LikeStatus.Unknown) return s
        }
        return LikeStatus.Unknown
    }

    /**
     * The status `next` gives [videoId] (the desktop learns it from the queue `/next` sends to a signed-in client): the
     * like of the first `playlistPanelVideoRenderer` of that video. Unknown when none says.
     */
    fun statusIn(nextResponse: String, videoId: String): LikeStatus {
        var found = LikeStatus.Unknown
        fun walk(v: Any?) {
            if (found != LikeStatus.Unknown) return
            when (v) {
                is JObj -> {
                    val panel = v.obj("playlistPanelVideoRenderer")
                    if (panel != null && panel.str("videoId") == videoId) { found = of(panel); if (found != LikeStatus.Unknown) return }
                    for ((_, x) in v.members) walk(x)
                }
                is List<*> -> for (x in v) walk(x)
            }
        }
        walk(OrderedJson.parse(nextResponse))
        return found
    }

    /**
     * `writeFailure`: YouTube answers a write it refused, in some cases, with a 200 and an "error" object in place of
     * the result. Throws with its message (or status).
     */
    fun checkWrite(endpoint: String, body: String) {
        val root = try { OrderedJson.parse(body) as? JObj } catch (e: Exception) { throw YtmException("youtube: decode $endpoint response: ${e.message}") }
            ?: throw YtmException("youtube: decode $endpoint response: not an object")
        val error = root.obj("error") ?: return
        val reason = error.str("message").orEmpty().ifEmpty { error.str("status").orEmpty() }
        throw YtmException(if (reason.isEmpty()) "youtube: $endpoint was refused" else "youtube: $endpoint was refused: $reason")
    }
}

/**
 * M6h: the hearts of the signed-in YouTube Music account (`accountwrites.go` `toggleLike`, `setLike`, `learnLikes`).
 * [state] holds what YouTube said of each video (by video id) and the changes made here; signed out it is empty and
 * nothing is asked or written. A change shows at once and is written behind it; a write YouTube refuses is taken back
 * and [toggle]'s `onFailure` is told. A video is written one change at a time: a press while its last change is out is
 * ignored. A sign-out (or another account) forgets everything. Logs outcomes only: never a cookie, a name or a title.
 */
class YtmLikes(
    private val session: StateFlow<YtmSession?>,
    private val newClient: (YtmCookieAuth) -> YtmClient,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<Map<String, LikeStatus>>(emptyMap())
    val state: StateFlow<Map<String, LikeStatus>> = _state

    private val writing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // The session the statuses belong to, from the start: [start]'s first look at the session then changes nothing.
    @Volatile private var owner: YtmSession? = session.value

    /** Follows the session: called once by the app. */
    fun start(): Job = scope.launch {
        session.collect { s ->
            if (!same(owner, s)) {
                owner = s
                writing.clear(); asked.clear()
                _state.value = emptyMap()
            }
        }
    }

    /** `canLike`: an account, and a YouTube Music video. */
    fun canLike(videoId: String?): Boolean = session.value != null && !videoId.isNullOrBlank()

    fun liked(videoId: String?): Boolean = videoId != null && _state.value[videoId] == LikeStatus.Liked

    /**
     * Asks YouTube once per session whether [videoId] is liked (the desktop reads it from the signed-in `/next` of the
     * track playing). A status already known, or a write on its way, is kept.
     */
    fun learn(videoId: String?) {
        val s = session.value ?: return
        if (videoId.isNullOrBlank() || !asked.add(videoId)) return
        scope.launch {
            val client = clientFor(s) ?: return@launch
            val status = try { client.likeStatus(videoId) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log("ytm like: read failed (${e.javaClass.simpleName})"); asked.remove(videoId); return@launch
            }
            if (!same(session.value, s)) return@launch
            setIfUnknown(videoId, status)
        }
    }

    /** `learnLikes`: the songs of the liked songs list are liked, for the copies that do not know yet. */
    fun learnLiked(videoIds: Collection<String>) {
        if (session.value == null) return
        for (id in videoIds) if (id.isNotBlank()) setIfUnknown(id, LikeStatus.Liked)
    }

    /**
     * `toggleLike`: likes [videoId], or takes its like back, as it shows now. Returns false (and does nothing) signed
     * out, for no video, or while the last change of this video is still on its way.
     */
    fun toggle(videoId: String?, onFailure: (liked: Boolean, message: String) -> Unit = { _, _ -> }): Boolean {
        val s = session.value ?: return false
        if (videoId.isNullOrBlank() || !writing.add(videoId)) return false
        val before = _state.value[videoId] ?: LikeStatus.Unknown
        val like = before != LikeStatus.Liked
        set(videoId, if (like) LikeStatus.Liked else LikeStatus.Indifferent)
        scope.launch {
            val error = try {
                val client = clientFor(s) ?: throw YtmSessionGone()
                client.rateTrack(videoId, like)
                null
            } catch (e: CancellationException) { throw e } catch (e: Exception) { e }
            // Another account's answers say nothing of this one.
            if (!same(session.value, s)) return@launch
            writing.remove(videoId)
            if (error == null) { log("ytm like: ${if (like) "liked" else "unliked"}"); return@launch }
            log("ytm like: ${if (like) "like" else "unlike"} refused (${error.javaClass.simpleName})")
            set(videoId, before)
            onFailure(like, error.message?.lineSequence()?.firstOrNull().orEmpty())
        }
        return true
    }

    private fun set(id: String, status: LikeStatus) = _state.update { it + (id to status) }

    private fun setIfUnknown(id: String, status: LikeStatus) {
        if (status == LikeStatus.Unknown || id in writing) return
        _state.update { m -> if ((m[id] ?: LikeStatus.Unknown) == LikeStatus.Unknown) m + (id to status) else m }
    }

    private fun same(a: YtmSession?, b: YtmSession?) = (a == null && b == null) || (a != null && b != null && a.cookie == b.cookie && a.channel == b.channel)

    private fun clientFor(s: YtmSession): YtmClient? = try {
        newClient(YtmCookieAuth.create(s.cookie, onBehalfOfUser = s.channel))
    } catch (e: IllegalArgumentException) { log("ytm like: the session has no usable cookie"); null }
}
