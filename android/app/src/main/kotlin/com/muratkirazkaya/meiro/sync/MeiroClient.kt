package com.muratkirazkaya.meiro.sync

import com.muratkirazkaya.meiro.data.Rfc3339Nano
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A refusal the server sent back, with its status (`APIError`). Never branch on [message]: it is Turkish. */
class ApiException(val status: Int, val serverMessage: String) :
    Exception(if (serverMessage.isEmpty()) "sunucu $status döndü" else "sunucu $status: $serverMessage")

/**
 * Whether an error may pass on a later try (`Retryable`): a network failure (anything that is not an
 * [ApiException]), or a server that is temporarily unwell (5xx). Cancellation is not retryable.
 */
fun isRetryable(e: Throwable?): Boolean {
    if (e == null) return false
    if (e is ApiException) return e.status >= 500
    return true
}

/** MeiroClient talks to a Meiro server over HTTP (internal/sync/sync.go `Client`). */
class MeiroClient(
    baseUrl: String,
    /** The bearer token of the signed-in account, empty before one. */
    var token: String = "",
    private val http: OkHttpClient = defaultHttp(),
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    private fun url(path: String) = baseUrl + path

    private fun builder(path: String): Request.Builder {
        val b = Request.Builder().url(url(path))
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        return b
    }

    private suspend fun <T> execute(req: Request, ser: KSerializer<T>?): T? {
        val resp = http.newCall(req).await()
        resp.use {
            val body = it.body.string()
            if (it.code != 200) throw apiError(it.code, body)
            if (ser == null) return null
            return SyncJson.decodeFromString(ser, body)
        }
    }

    private suspend fun <B, T> post(path: String, bser: KSerializer<B>, body: B, out: KSerializer<T>?): T? {
        val data = SyncJson.encodeToString(bser, body)
        return execute(builder(path).post(data.toRequestBody(JSON)).build(), out)
    }

    private suspend fun <T> get(path: String, out: KSerializer<T>): T = execute(builder(path).get().build(), out)!!

    private suspend fun <T> delete(path: String, out: KSerializer<T>): T = execute(builder(path).delete().build(), out)!!

    @Serializable private data class LoginReq(val email: String, val password: String)
    @Serializable private data class LoginResp(val token: String = "")
    @Serializable private data class EventsReq(val events: List<Event>)
    @Serializable private data class EventsResp(val accepted: List<String> = emptyList(), val duplicates: List<String> = emptyList())
    @Serializable private data class LibrariesReq(val libraries: List<Library>)
    @Serializable private data class AcceptedResp(val accepted: List<String> = emptyList())
    @Serializable private data class DownloadsReq(val downloads: List<Download>)
    @Serializable private data class ClearResp(
        @kotlinx.serialization.SerialName("cleared_at")
        @Serializable(with = InstantSerializer::class) val clearedAt: Instant = Rfc3339Nano.ZERO,
    )

    /** Exchanges an email and password for a session token. There is no registration route on the server. */
    suspend fun login(email: String, password: String): String =
        post("/api/v1/auth/login", LoginReq.serializer(), LoginReq(email, password), LoginResp.serializer())!!.token

    /** Sends plays; returns which the server took and which it had already. */
    suspend fun pushEvents(events: List<Event>): Pair<List<String>, List<String>> {
        val r = post("/api/v1/sync/events", EventsReq.serializer(), EventsReq(events), EventsResp.serializer())!!
        return r.accepted to r.duplicates
    }

    suspend fun pushLibraries(libraries: List<Library>): List<String> =
        post("/api/v1/sync/libraries", LibrariesReq.serializer(), LibrariesReq(libraries), AcceptedResp.serializer())!!.accepted

    suspend fun pushLibraryItems(payload: LibraryItems): List<String> =
        post("/api/v1/sync/library_items", LibraryItems.serializer(), payload, AcceptedResp.serializer())!!.accepted

    suspend fun pushDownloads(downloads: List<Download>): List<String> =
        post("/api/v1/sync/downloads", DownloadsReq.serializer(), DownloadsReq(downloads), AcceptedResp.serializer())!!.accepted

    suspend fun pushLinks(links: Links) {
        post<Links, Unit>("/api/v1/sync/links", Links.serializer(), links, null)
    }

    suspend fun fetchLinks(): Links = get("/api/v1/links", Links.serializer())

    /** One answer of what changed after [since], tombstones included. A limit outside 1..1000 asks for 1000. */
    suspend fun fetchChanges(since: Long, limit: Int): Changes {
        val l = if (limit < 1 || limit > MAX_CHANGES) MAX_CHANGES else limit
        return get("/api/v1/sync/changes?since=$since&limit=$l", Changes.serializer())
    }

    /**
     * Everything that changed after [since], asking again from where each answer left off while the server says
     * there is more. A server that says there is more but does not move on is an error, never a loop.
     */
    suspend fun fetchAllChanges(since: Long): Changes {
        var next = since
        val libs = mutableListOf<ChangedLibrary>()
        val items = mutableListOf<ChangedItem>()
        while (true) {
            val page = fetchChanges(next, MAX_CHANGES)
            libs += page.libraries
            items += page.items
            if (!page.more) return Changes(libs, items, maxOf(next, page.next), false)
            if (page.next <= next) throw IOException("sync: the server has more changes but did not move on")
            next = page.next
        }
    }

    suspend fun fetchEvents(since: Long, limit: Int): Plays {
        val l = if (limit < 1 || limit > MAX_PLAYS) MAX_PLAYS else limit
        return get("/api/v1/events?since=$since&limit=$l", Plays.serializer())
    }

    suspend fun fetchAllEvents(since: Long): Plays {
        var next = since
        val events = mutableListOf<PlayedEvent>()
        var cleared: Instant? = null
        while (true) {
            val page = fetchEvents(next, MAX_PLAYS)
            events += page.events
            cleared = page.clearedAt // the last answer is the newest word on when the history was cleared
            if (!page.more) return Plays(events, maxOf(next, page.next), false, cleared)
            if (page.next <= next) throw IOException("sync: the server has more plays but did not move on")
            next = page.next
        }
    }

    /** Asks the server to forget every play of the account; returns the time it forgot them at. */
    suspend fun clearEvents(): Instant = delete("/api/v1/events", ClearResp.serializer()).clearedAt

    companion object {
        const val MAX_CHANGES = 1000
        const val MAX_PLAYS = 1000
        private val JSON = "application/json".toMediaType()

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS) // 30 s per request, as the desktop
            .build()

        private fun apiError(status: Int, body: String): ApiException {
            val msg = try {
                SyncJson.parseToJsonElement(body.take(1 shl 16)).let {
                    (it as? kotlinx.serialization.json.JsonObject)?.get("error")?.let { e -> (e as? kotlinx.serialization.json.JsonPrimitive)?.content }
                } ?: ""
            } catch (_: Exception) { "" }
            return ApiException(status, msg)
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}
