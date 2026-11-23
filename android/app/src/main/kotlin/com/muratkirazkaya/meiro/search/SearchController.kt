package com.muratkirazkaya.meiro.search

import android.content.Context
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.screens.SearchChip
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.SearchFilter
import com.muratkirazkaya.meiro.youtube.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray

/** Where a search goes: YouTube Music (InnerTube) and SoundCloud (yt-dlp). */
interface SearchProviders {
    suspend fun ytm(query: String, params: String): SearchResult
    suspend fun ytmMore(token: String): SearchResult
    suspend fun soundCloud(query: String): List<MusicItem>
}

/** The recent searches, kept on the device. */
interface RecentStore {
    fun load(): List<String>
    fun save(queries: List<String>)
}

class PrefsRecentStore(context: Context) : RecentStore {
    private val prefs = context.getSharedPreferences("meiro-search", Context.MODE_PRIVATE)
    override fun load(): List<String> = try {
        val a = JSONArray(prefs.getString("recent", "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    override fun save(queries: List<String>) { prefs.edit().putString("recent", JSONArray(queries).toString()).apply() }
}

/** One provider's answer to the search out, and whether it has arrived (`providerSearch`). */
data class ProviderAnswer(val items: List<MusicItem> = emptyList(), val done: Boolean = false, val failed: Boolean = false, val error: String = "")

/**
 * The search page's state (`searchState`). Typing only edits [field]; a search runs when it is submitted, and
 * [submitted] is what the results are for.
 */
data class SearchState(
    val field: String = "",
    val submitted: String = "",
    val chip: SearchChip = SearchChip.All,
    val ytmOn: Boolean = true,
    val scOn: Boolean = true,
    val ytm: ProviderAnswer = ProviderAnswer(),
    val sc: ProviderAnswer = ProviderAnswer(),
    val top: MusicItem? = null,
    val filters: List<SearchFilter> = emptyList(),
    val more: String = "",
    /** Set when both providers failed: the tracks this device holds that match (`localSearch`). */
    val local: List<MusicItem>? = null,
    val recent: List<String> = emptyList(),
)

/**
 * The search (desktop `search.go`): both providers are asked together, YouTube Music bounded by [timeoutMs] and
 * SoundCloud by [scTimeoutMs] (see [SOUNDCLOUD_TIMEOUT_MS]); each answer is
 * shown as it arrives; one that fails is left out, and when both fail the results come from this device. SoundCloud is
 * asked only without a filter or for songs (`soundCloudWanted`). Recent searches: newest first, 8 at most, a repeat
 * (any case) moves to the top (`settings.Remember`).
 */
class SearchController(
    private val scope: CoroutineScope,
    private val providers: SearchProviders,
    private val recentStore: RecentStore,
    private val localItems: () -> List<MusicItem> = { emptyList() },
    /** Whether the phone has a network; without one the search goes straight to this device's tracks. */
    private val online: () -> Boolean = { true },
    private val timeoutMs: Long = SEARCH_TIMEOUT_MS,
    private val scTimeoutMs: Long = SOUNDCLOUD_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(SearchState(recent = recentStore.load()))
    val state: StateFlow<SearchState> = _state

    private var ytmJob: Job? = null
    private var scJob: Job? = null
    private var moreJob: Job? = null
    private var generation = 0
    /** SoundCloud's answer for a query, kept so switching between "Hepsi" and "Şarkılar" does not search it again. */
    private var scCache: Pair<String, List<MusicItem>>? = null

    fun setField(text: String) = _state.update { it.copy(field = text) }

    /** Runs a search for [query] (the field's text by default); an empty query clears the page. */
    fun submit(query: String = _state.value.field) {
        val q = query.trim()
        if (q.isEmpty()) { clear(); return }
        val recent = remember(_state.value.recent, q)
        recentStore.save(recent)
        _state.update {
            it.copy(field = q, submitted = q, chip = SearchChip.All, ytm = ProviderAnswer(), sc = ProviderAnswer(), top = null, more = "", local = null, recent = recent)
        }
        start(q, "", soundCloud = true)
    }

    fun clear() {
        stop()
        _state.update { SearchState(recent = it.recent, ytmOn = it.ytmOn, scOn = it.scOn) }
    }

    /** A filter chip: YouTube Music is asked again with the filter's own params when the last answer offered them. */
    fun selectChip(chip: SearchChip) {
        val s = _state.value
        if (s.submitted.isEmpty() || chip == s.chip) return
        val params = if (chip == SearchChip.All) "" else paramsFor(chip, s.filters)
        _state.update { it.copy(chip = chip) }
        if (chip != SearchChip.All && params.isEmpty()) return // no params known: the chip only narrows what is shown
        _state.update { it.copy(ytm = ProviderAnswer(), more = "", local = null) }
        start(s.submitted, params, soundCloud = chip == SearchChip.All || chip == SearchChip.Songs)
    }

    /** Shows or hides one provider's results; at least one stays on. */
    fun toggleProvider(p: Provider) = _state.update {
        when (p) {
            Provider.Ytm -> if (it.ytmOn && !it.scOn) it else it.copy(ytmOn = !it.ytmOn)
            Provider.Sc -> if (it.scOn && !it.ytmOn) it else it.copy(scOn = !it.scOn)
            Provider.Meiro -> it
        }
    }

    /** The next page of a filtered YouTube Music search (`ContinueSearch`); "Hepsi" shows a fixed number of rows. */
    fun loadMore() {
        val s = _state.value
        if (s.chip == SearchChip.All || s.more.isEmpty() || moreJob?.isActive == true || !s.ytm.done) return
        val gen = generation
        val token = s.more
        moreJob = scope.launch {
            val r = runCatching { withTimeout(timeoutMs) { providers.ytmMore(token) } }
            if (gen != generation) return@launch
            r.onSuccess { page ->
                log("search: next page +${page.items.size}")
                _state.update { it.copy(ytm = it.ytm.copy(items = it.ytm.items + page.items), more = page.continuationToken.takeIf { t -> t != token }.orEmpty()) }
            }.onFailure { e -> log("search: next page failed: ${e.message}") }
        }
    }

    fun forget(query: String) {
        val recent = _state.value.recent.filter { it != query }
        recentStore.save(recent)
        _state.update { it.copy(recent = recent) }
    }

    fun clearRecent() {
        recentStore.save(emptyList())
        _state.update { it.copy(recent = emptyList()) }
    }

    private fun stop() {
        generation++
        ytmJob?.cancel(); scJob?.cancel(); moreJob?.cancel()
    }

    private fun start(q: String, params: String, soundCloud: Boolean) {
        stop()
        val gen = generation
        if (!online()) {
            // No network: neither provider can answer, so the results come from this device at once.
            val off = ProviderAnswer(done = true, failed = true, error = "offline")
            _state.update { it.copy(ytm = off, sc = off) }
            fallBack(gen, q)
            return
        }
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        ytmJob = scope.launch {
            val r = runCatching { withTimeout(timeoutMs) { providers.ytm(q, params) } }
            if (gen != generation) return@launch
            r.onSuccess { res ->
                log("search: ytm \"$q\" params=${params.isNotEmpty()} ${res.items.size} items in ${ms()} ms")
                _state.update {
                    it.copy(
                        ytm = ProviderAnswer(res.items, done = true), top = res.top,
                        filters = res.filters.ifEmpty { it.filters }, more = res.continuationToken,
                    )
                }
            }.onFailure { e ->
                log("search: ytm \"$q\" failed in ${ms()} ms: ${e.javaClass.simpleName} ${e.message}")
                _state.update { it.copy(ytm = ProviderAnswer(done = true, failed = true, error = e.message.orEmpty())) }
            }
            fallBack(gen, q)
        }
        val cached = scCache?.takeIf { it.first == q }
        when {
            !soundCloud -> _state.update { it.copy(sc = ProviderAnswer(done = true)) }
            cached != null -> _state.update { it.copy(sc = ProviderAnswer(cached.second, done = true)) }
            else -> {
                _state.update { it.copy(sc = ProviderAnswer()) }
                scJob = scope.launch {
                    val r = runCatching { withTimeout(scTimeoutMs) { providers.soundCloud(q) } }
                    if (gen != generation) return@launch
                    r.onSuccess { items ->
                        log("search: sc \"$q\" ${items.size} items in ${ms()} ms")
                        scCache = q to items
                        _state.update { it.copy(sc = ProviderAnswer(items, done = true)) }
                    }.onFailure { e ->
                        log("search: sc \"$q\" failed in ${ms()} ms: ${e.javaClass.simpleName} ${e.message}")
                        _state.update { it.copy(sc = ProviderAnswer(done = true, failed = true, error = e.message.orEmpty())) }
                    }
                    fallBack(gen, q)
                }
            }
        }
    }

    /** Both providers out of reach: the results come from what this device holds. */
    private fun fallBack(gen: Int, q: String) {
        val s = _state.value
        if (gen != generation || !s.ytm.done || !s.sc.done) return
        if (s.ytm.failed && s.sc.failed) {
            val local = SearchMerge.filterLocal(localItems(), q)
            log("search: both providers failed, ${local.size} tracks from this device")
            _state.update { it.copy(local = local) }
        }
    }

    companion object {
        /** `searchTimeout`, for YouTube Music. */
        const val SEARCH_TIMEOUT_MS = 7_000L

        /**
         * SoundCloud's bound on the phone. The desktop's 7 s is too short for the bundled yt-dlp here: on the emulator a
         * `scsearch50:` took 18.7 s and 29 s (Python start, and a HEAD request per result for its artwork inside
         * yt-dlp), so with 7 s SoundCloud never answered. YouTube Music still shows at once; SoundCloud's rows join
         * when they arrive. A deviation for the owner to decide (PROGRESS.md).
         */
        const val SOUNDCLOUD_TIMEOUT_MS = 45_000L
        const val MAX_RECENT = 8

        /** `settings.Remember`. */
        fun remember(recent: List<String>, query: String): List<String> {
            val q = query.trim()
            if (q.isEmpty()) return recent
            return (listOf(q) + recent.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT)
        }

        /** The YouTube Music filter a chip stands for, by the English label `hl=en` gives it. */
        fun paramsFor(chip: SearchChip, filters: List<SearchFilter>): String {
            val label = when (chip) {
                SearchChip.All -> return ""
                SearchChip.Songs -> "Songs"
                SearchChip.Artists -> "Artists"
                SearchChip.Albums -> "Albums"
                SearchChip.Lists -> "Featured playlists"
            }
            return filters.firstOrNull { it.label.equals(label, ignoreCase = true) }?.params.orEmpty()
        }
    }
}
