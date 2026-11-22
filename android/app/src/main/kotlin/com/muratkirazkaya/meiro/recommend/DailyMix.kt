package com.muratkirazkaya.meiro.recommend

import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.Item
import com.muratkirazkaya.meiro.data.Library
import com.muratkirazkaya.meiro.data.LinkState
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.sync.Ids
import com.muratkirazkaya.meiro.sync.libraryServerId
import com.muratkirazkaya.meiro.youtube.ItemKind
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.Ref
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Where related songs come from: YouTube Music's "Related" tab of a video, SoundCloud's recommendations beside a track page. */
interface RelatedSources {
    suspend fun youtube(videoId: String): List<MusicItem>
    suspend fun soundCloud(pageUrl: String): List<MusicItem>
}

/**
 * What a provider listed as related to a seed, kept for a day so asking again costs no request (`relatedCache`,
 * recommendations.go:85). Entries older than a week are dropped when one is written. Kept in [file] when given.
 */
class RelatedCache(private val file: File? = null, private val log: (String) -> Unit = {}) {
    private class Entry(val fetchedAt: Instant, val items: List<MusicItem>)
    private val data = HashMap<String, Entry>()

    init {
        if (file != null && file.exists()) {
            try {
                val o = JSONObject(file.readText())
                for (k in o.keys()) {
                    val e = o.getJSONObject(k)
                    val a = e.getJSONArray("items")
                    data[k] = Entry(Instant.parse(e.getString("fetchedAt")), (0 until a.length()).map { fromJson(a.getJSONObject(it)) })
                }
            } catch (e: Exception) {
                log("recommendations: the cache is not readable, starting empty: $e")
                data.clear()
            }
        }
    }

    @Synchronized fun get(key: String, now: Instant): List<MusicItem>? {
        val e = data[key] ?: return null
        if (Duration.between(e.fetchedAt, now) >= TTL) return null
        return e.items
    }

    @Synchronized fun put(key: String, items: List<MusicItem>, now: Instant) {
        data[key] = Entry(now, items)
        data.entries.removeAll { Duration.between(it.value.fetchedAt, now) > MAX_AGE }
        if (file == null) return
        try {
            val o = JSONObject()
            for ((k, e) in data) o.put(k, JSONObject().put("fetchedAt", e.fetchedAt.toString()).put("items", JSONArray(e.items.map { toJson(it) })))
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            log("recommendations: saving the cache: $e")
        }
    }

    companion object {
        val TTL: Duration = Duration.ofHours(24)
        val MAX_AGE: Duration = Duration.ofDays(7)

        private fun toJson(m: MusicItem) = JSONObject().put("source", m.source).put("videoId", m.videoId).put("url", m.url)
            .put("title", m.title).put("subtitle", m.subtitle).put("artist", m.artist).put("artists", m.artists)
            .put("duration", m.duration).put("thumbnail", m.thumbnail)
            .put("sources", JSONArray(m.sources.map { JSONObject().put("source", it.source).put("id", it.id).put("url", it.url) }))

        private fun fromJson(o: JSONObject): MusicItem {
            val src = o.optJSONArray("sources")
            return MusicItem(
                kind = ItemKind.Track, source = o.optString("source", Source.YTM), videoId = o.optString("videoId"), id = o.optString("videoId"),
                url = o.optString("url"), title = o.optString("title"), subtitle = o.optString("subtitle"), artist = o.optString("artist"),
                artists = o.optString("artists"), duration = o.optString("duration"), thumbnail = o.optString("thumbnail"),
                sources = if (src == null) emptyList() else (0 until src.length()).map { i -> src.getJSONObject(i).let { Ref(it.optString("source"), it.optString("id"), it.optString("url")) } },
            )
        }
    }
}

/** A song to look for related songs around: the latest song of a liked artist (`seedTrack`). */
data class SeedTrack(val artist: Entry, val provider: String, val id: String, val mbid: String = "")

/**
 * The Daily mix as the desktop makes it (`recommendations.go`, PORTING.md 5.4): the taste profile from the history
 * and the lists, the top 8 artists' latest songs as seeds, what YouTube Music and SoundCloud list as related to each
 * (within the request budgets, paused between requests, kept for a day), ranked into 30 picks. The phone has no
 * MusicBrainz/ListenBrainz client yet, so seeds carry no MusicBrainz id and the similar-artist step never runs (the
 * desktop runs it only for artists linked on MusicBrainz).
 */
class DailyMixMaker(
    private val sources: RelatedSources,
    private val cache: RelatedCache,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    /** `tastePlays`: the history as plays, provider defaulting to YouTube Music. */
    fun tastePlays(history: List<HistoryEntry>): List<Play> = history.map { e ->
        Play(e.source.ifEmpty { Source.YTM }, e.videoId, e.title, e.artist, e.playedAt, e.playedMs, e.durationMs, e.completed)
    }

    /**
     * `tasteSignals`: every track of every list but the Daily mix (the app made it; counting it would have the app vote
     * for its own picks), as saved at the time it was added. Downloads come with M5.
     */
    fun tasteSignals(libs: List<Library>, dailyMixId: String): List<Signal> = libs
        .filterNot { it.id == dailyMixId || libraryServerId(it.id, it.serverId) == Ids.dailyMixId() }
        .flatMap { lib -> lib.items.map { Signal(it.source, it.key, it.title, it.artist, SignalKind.Saved, it.addedAt) } }

    fun profile(history: List<HistoryEntry>, libs: List<Library>, dailyMixId: String, now: Instant): Profile =
        Taste.build(tastePlays(history), tasteSignals(libs, dailyMixId), now)

    /** `recSeedTracks`: for each of the top [SEEDS] artists, the latest played song by that artist that has an id. */
    fun seeds(profile: Profile, history: List<HistoryEntry>): List<SeedTrack> = profile.topArtists(SEEDS).mapNotNull { artist ->
        var latest: HistoryEntry? = null
        for (e in history) {
            if (Taste.artistKey(e.artist) == artist.key && e.videoId.isNotEmpty() && (latest == null || e.playedAt.isAfter(latest.playedAt))) latest = e
        }
        latest?.let { SeedTrack(artist, it.source.ifEmpty { Source.YTM }, it.videoId) }
    }

    /**
     * `gatherCandidates`: asks the providers what is related to each seed, within the budgets (10 YouTube Music, 3
     * SoundCloud requests that are not kept already), pausing before each request (1.5 s, 3 s). A failed request is
     * logged and skipped.
     */
    suspend fun gather(seeds: List<SeedTrack>, now: Instant): List<Candidate<MusicItem>> {
        val out = ArrayList<Candidate<MusicItem>>()
        var ytLeft = BUDGET_YOUTUBE; var scLeft = BUDGET_SOUNDCLOUD
        var asked = 0; var cached = 0
        for (seed in seeds) {
            val key = seed.provider + "|" + seed.id
            val rel = if (seed.provider == Source.SOUNDCLOUD) Relation.SoundCloudRelated else Relation.YouTubeRelated
            var items = cache.get(key, now)
            if (items != null) cached++ else {
                try {
                    items = when {
                        rel == Relation.SoundCloudRelated && scLeft > 0 -> { scLeft--; asked++; pause(PAUSE_SOUNDCLOUD_MS); sources.soundCloud(seed.id) }
                        rel == Relation.YouTubeRelated && ytLeft > 0 -> { ytLeft--; asked++; pause(PAUSE_YOUTUBE_MS); sources.youtube(seed.id) }
                        else -> null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("recommendations: related to \"${seed.artist.name}\": $e")
                    continue
                }
                if (items == null) continue
                cache.put(key, items, now)
            }
            for (it in items) {
                val artist = itemArtist(it)
                if (it.title.isEmpty() || artist.isEmpty()) continue
                out.add(Candidate(it.title, artist, it, rel, 1.0, Seed(seed.artist.name, seed.artist.score)))
            }
        }
        log("recommendations: ${seeds.size} seeds, $asked requests, $cached from the cache, ${out.size} candidates")
        return out
    }

    /** `itemArtist`: the artist a song names, from its credit or its byline. */
    fun itemArtist(it: MusicItem): String = it.artist.ifEmpty { it.subtitle.substringBefore(" • ").trim() }

    /** The picks as list tracks (`libraryItem` plus the reason), in rank order. */
    fun items(picks: List<Pick<MusicItem>>): List<Item> = picks.map { p ->
        val m = p.candidate.ref
        Item(
            source = m.source, key = m.key(), url = m.url, title = m.title, artist = p.artist,
            durationMs = durationMs(m.duration), state = LinkState.UNKNOWN, thumbnail = m.thumbnail, why = p.why,
            sources = m.sources.map { com.muratkirazkaya.meiro.data.SourceRef(it.source, it.id, it.url) },
        )
    }

    /**
     * One run (`refreshRecommendations`): nothing when the profile is empty or there is no seed; otherwise gather, then
     * rank against the profile as it is when the answers are in ([profileNow]), with [explore] of the picks new.
     */
    suspend fun make(
        profileNow: () -> Profile, history: () -> List<HistoryEntry>, now: Instant, zone: ZoneId, explore: Double = EXPLORE,
    ): List<Item> {
        val start = profileNow()
        if (start.empty()) { log("recommendations: nothing played or saved yet"); return emptyList() }
        val seeds = seeds(start, history())
        if (seeds.isEmpty()) { log("recommendations: no seed track"); return emptyList() }
        val candidates = gather(seeds, now)
        val options = RankOptions.defaults(now, zone).copy(n = DAILY_MIX_SIZE, explore = explore)
        val picks = Rank.rank(profileNow(), candidates, options)
        log("recommendations: ${picks.size} picks (${picks.count { it.fresh }} new)")
        return items(picks)
    }

    companion object {
        const val SEEDS = 8
        const val BUDGET_YOUTUBE = 10
        const val BUDGET_SOUNDCLOUD = 3
        const val PAUSE_YOUTUBE_MS = 1500L
        const val PAUSE_SOUNDCLOUD_MS = 3000L
        const val DAILY_MIX_SIZE = 30
        /** The desktop's default "New artists" setting, 30 of every 100 (the phone has no settings page yet). */
        const val EXPLORE = 0.3
        val RUN_TIMEOUT: Duration = Duration.ofMinutes(10)
        /** The desktop checks every hour, the first time 45 s after launch (main.go:207). */
        val FIRST_CHECK: Duration = Duration.ofSeconds(45)
        val CHECK_EVERY: Duration = Duration.ofHours(1)

        /** "4:08", "1:02:03" to milliseconds; 0 when it is not a length. */
        fun durationMs(text: String): Long {
            val parts = text.trim().split(":")
            if (parts.size !in 2..3 || parts.any { p -> p.isEmpty() || p.any { it !in '0'..'9' } }) return 0
            return parts.fold(0L) { acc, p -> acc * 60 + p.toLong() } * 1000
        }
    }
}
