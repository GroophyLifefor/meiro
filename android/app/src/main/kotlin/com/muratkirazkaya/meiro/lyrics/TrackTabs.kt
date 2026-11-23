package com.muratkirazkaya.meiro.lyrics

import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.youtube.MusicItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The lyrics tab of one track, as far as it is loaded. */
sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val lyrics: FoundLyrics) : LyricsState
    /** Nothing anywhere; [instrumental] when LRCLIB says the track has no words. */
    data class None(val instrumental: Boolean) : LyricsState
    /** The lookup failed (not that there are none); opening the tab again tries again. */
    data object Failed : LyricsState
}

/** The related tab of one track. */
sealed interface RelatedState {
    data object Loading : RelatedState
    data class Found(val items: List<MusicItem>) : RelatedState
    /** YouTube Music lists nothing related, or the track has no YouTube Music copy to ask about. */
    data object None : RelatedState
    data object Failed : RelatedState
}

/**
 * The loads behind the player's "Sözler" and "Benzerler" tabs (`loadLyrics`, `loadRelated` in lyrics.go and
 * nowplaying.go): once per track, started when the tab is shown and again when the track changes while it is shown;
 * a new track cancels the load of the one before. A failure is not remembered, so the next showing tries again.
 * Each state carries the key of the track it belongs to.
 */
class TrackTabs(
    private val scope: CoroutineScope,
    private val finder: LyricsFinder,
    private val fetchRelated: suspend (videoId: String) -> List<MusicItem>,
    private val timeoutMs: Long = 60_000,
) {
    private val _lyrics = MutableStateFlow<Pair<String, LyricsState>?>(null)
    val lyrics: StateFlow<Pair<String, LyricsState>?> = _lyrics
    private val _related = MutableStateFlow<Pair<String, RelatedState>?>(null)
    val related: StateFlow<Pair<String, RelatedState>?> = _related
    private var lyricsJob: Job? = null
    private var relatedJob: Job? = null

    fun loadLyrics(item: QItem, totalMs: Long) {
        val key = item.key
        val have = _lyrics.value
        if (key.isEmpty() || (have != null && have.first == key && have.second != LyricsState.Failed)) return
        lyricsJob?.cancel()
        _lyrics.value = key to LyricsState.Loading
        lyricsJob = scope.launch {
            val state = try {
                val found = withTimeout(timeoutMs) { finder.find(item, totalMs) }
                if (found.empty()) LyricsState.None(found.instrumental) else LyricsState.Found(found)
            } catch (e: CancellationException) {
                if (_lyrics.value?.first != key) throw e
                LyricsState.Failed // the timeout
            } catch (e: Exception) {
                LyricsState.Failed
            }
            if (_lyrics.value?.first == key) _lyrics.value = key to state
        }
    }

    fun loadRelated(item: QItem) {
        val key = item.key
        val have = _related.value
        if (key.isEmpty() || (have != null && have.first == key && have.second != RelatedState.Failed)) return
        relatedJob?.cancel()
        val videoId = item.refFor(Source.YTM)
        if (videoId == null) { _related.value = key to RelatedState.None; return }
        _related.value = key to RelatedState.Loading
        relatedJob = scope.launch {
            val state = try {
                val items = withTimeout(timeoutMs) { fetchRelated(videoId) }
                if (items.isEmpty()) RelatedState.None else RelatedState.Found(items)
            } catch (e: CancellationException) {
                if (_related.value?.first != key) throw e
                RelatedState.Failed
            } catch (e: Exception) {
                RelatedState.Failed
            }
            if (_related.value?.first == key) _related.value = key to state
        }
    }
}
