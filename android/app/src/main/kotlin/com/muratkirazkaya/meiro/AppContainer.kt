package com.muratkirazkaya.meiro

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.muratkirazkaya.meiro.data.LibraryStore
import com.muratkirazkaya.meiro.data.db.MeiroDatabase
import com.muratkirazkaya.meiro.data.db.RoomPersistence
import com.muratkirazkaya.meiro.sync.EncryptedTokenStore
import com.muratkirazkaya.meiro.sync.LinksStore
import com.muratkirazkaya.meiro.sync.Outbox
import com.muratkirazkaya.meiro.sync.SyncEngine
import com.muratkirazkaya.meiro.sync.SyncLog
import com.muratkirazkaya.meiro.data.HistoryEntry
import com.muratkirazkaya.meiro.data.History
import com.muratkirazkaya.meiro.playback.PlayStore
import java.util.concurrent.Executors
import com.muratkirazkaya.meiro.data.Source
import com.muratkirazkaya.meiro.playback.YtdlpSetup
import com.muratkirazkaya.meiro.playback.clockText
import com.muratkirazkaya.meiro.search.PrefsRecentStore
import com.muratkirazkaya.meiro.search.SearchController
import com.muratkirazkaya.meiro.search.SearchProviders
import com.muratkirazkaya.meiro.search.SoundCloudSearch
import com.muratkirazkaya.meiro.youtube.MusicItem
import com.muratkirazkaya.meiro.youtube.SearchResult
import com.muratkirazkaya.meiro.youtube.YtmClient
import com.muratkirazkaya.meiro.ui.toQItem
import com.muratkirazkaya.meiro.youtube.BrowsePage
import com.muratkirazkaya.meiro.youtube.YtmRelated
import com.muratkirazkaya.meiro.recommend.DailyMixMaker
import com.muratkirazkaya.meiro.recommend.RelatedCache
import com.muratkirazkaya.meiro.recommend.RelatedSources
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit

/** The phone's own settings screen values that change behaviour (theme, colour from the cover, recommendations, Wi-Fi only, pausing the history). */
data class AppPrefs(
    val theme: com.muratkirazkaya.meiro.ui.screens.ThemeChoice = com.muratkirazkaya.meiro.ui.screens.ThemeChoice.System,
    val coverColor: Boolean = true,
    val suggestions: Boolean = true,
    /** Percent of the Daily mix by artists never played (desktop `Explore`, 30). */
    val explore: Int = 30,
    val wifiOnly: Boolean = true,
    val historyPaused: Boolean = false,
)

/** What the UI needs to know about the account, read from the engine. */
data class Session(val signedIn: Boolean, val expired: Boolean, val email: String, val server: String)

/** Manual DI: the Room database, the encrypted token store and the sync engine, created once per process. */
class AppContainer(private val context: Context) {
    private val db = Room.databaseBuilder(context, MeiroDatabase::class.java, "meiro.db").build()
    private val persistence = RoomPersistence(db)
    val libraries = LibraryStore(persistence)
    val links = LinksStore(persistence)
    val engine: SyncEngine

    /** The downloads (`internal/downloads`): kept in the app's own files, never evicted, removed only by the user. */
    val downloads = com.muratkirazkaya.meiro.downloads.DownloadStore(java.io.File(context.filesDir, "downloads"))
    private val dlScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetch = com.muratkirazkaya.meiro.downloads.AudioFetch()

    /** The playback caches, one per provider (`YouTubeCacheMB`, `SoundCloudCacheMB`: 512 MB each). */
    val ytCache = com.muratkirazkaya.meiro.downloads.AudioCache(java.io.File(context.cacheDir, "audio/ytm"), fetch = fetch, scope = dlScope, log = { Log.i(DL_TAG, it) })
    val scCache = com.muratkirazkaya.meiro.downloads.AudioCache(java.io.File(context.cacheDir, "audio/soundcloud"), fetch = fetch, scope = dlScope, log = { Log.i(DL_TAG, it) })
    fun cacheFor(source: String) = if (source == Source.SOUNDCLOUD) scCache else ytCache

    init {
        SyncLog.sink = { Log.i(TAG, it) }
        engine = SyncEngine(
            libraries, Outbox(persistence), EncryptedTokenStore(context), persistence, links,
            deviceName = Build.MODEL,
            downloads = { downloadSnapshot() },
        )
        // Reads the saved session token (this is the first use of the Keystore) and sends/reads what waits.
        val s = engine.settings
        Log.i(TAG, "start: account=${s.syncEmail.ifEmpty { "none" }} server=${s.syncUrl}")
        if (s.syncEmail.isNotEmpty()) {
            kotlinx.coroutines.runBlocking { engine.loadSyncToken() }
            Log.i(TAG, "start: saved session ${if (engine.expired) "missing" else "read"}")
            SyncScheduler.schedule(context)
        }
    }

    /**
     * MusicBrainz linking (`mblink.go`): the client with the desktop's User-Agent, its 1 request a second and its cache
     * (in the app's cache dir), the link store, the lists, and the sync's link queue. Links the lists held before the
     * store knew them are taken in first (`importListLinks`).
     */
    val linker: com.muratkirazkaya.meiro.musicbrainz.Linker by lazy {
        val client = com.muratkirazkaya.meiro.musicbrainz.MusicBrainzClient(
            cache = com.muratkirazkaya.meiro.musicbrainz.MbCache(java.io.File(context.cacheDir, "musicbrainz")),
            log = { Log.i(LINK_TAG, it) },
        )
        com.muratkirazkaya.meiro.musicbrainz.Linker(
            client, links, libraries, { engine.queueLinks(it) }, CoroutineScope(SupervisorJob() + Dispatchers.IO), log = { Log.i(LINK_TAG, it) },
        ).also { it.importListLinks() }
    }

    private val linkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * `afterEnter`'s `autoLink`: each new track that starts playing is linked to its MusicBrainz recording when the match
     * is certain (at most once in 30 days per song unless it is linked or marked "other").
     */
    fun startAutoLink() {
        linkScope.launch {
            var last: Pair<String, String>? = null
            com.muratkirazkaya.meiro.playback.PlaybackHost.ui.collect { p ->
                val item = p?.item ?: return@collect
                val k = item.provider() to item.key
                if (k == last) return@collect
                last = k
                linker.autoLinkAll(listOf(item))
            }
        }
    }

    private val _historyVersion = MutableStateFlow(0L)

    /** Grows when the listening history changes (a play starts or ends here, or a sync brings plays from elsewhere). */
    val historyVersion: StateFlow<Long> = _historyVersion

    private val playWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "meiro-plays") }
    private val playStore = object : PlayStore {
        // The history is written to Room, which must not happen on the main thread; one writer keeps the order.
        override fun begin(entry: HistoryEntry) {
            // "Kaydı duraklat": while paused a play is not filed at all (not in the history, not sent).
            if (prefs.value.historyPaused) { Log.i(TAG, "play: not recorded (history paused) ${entry.source}:${entry.videoId}"); return }
            playWriter.execute {
                engine.beginPlay(entry)
                _historyVersion.value += 1
                Log.i(TAG, "play: begin event=${entry.eventId} ${entry.source}:${entry.videoId} duration=${entry.durationMs} ms")
            }
        }

        override fun finish(eventId: String, playedMs: Long, completed: Boolean) {
            playWriter.execute {
                val e = engine.finishPlay(eventId, playedMs, completed)
                _historyVersion.value += 1
                Log.i(
                    TAG,
                    "play: finish event=$eventId played=$playedMs ms completed=$completed " +
                        (if (e == null) "(not in the history any more)" else "counted=${History.counted(e)} queued, outbox=${engine.outbox.eventCount()}"),
                )
            }
        }
    }

    /** The search page's state and its two providers; it lives as long as the process, so a tab switch keeps it. */
    val search: SearchController by lazy {
        SearchController(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            AppSearchProviders(context),
            PrefsRecentStore(context),
            localItems = { localTracks() },
            online = { com.muratkirazkaya.meiro.downloads.Online.state.value },
            log = { Log.i(SEARCH_TAG, it) },
        )
    }

    /**
     * The tracks this device holds for a search without a network (`localItems`): the downloads, then what the playback
     * caches hold (YouTube Music, then SoundCloud), each once.
     */
    fun localTracks(): List<MusicItem> =
        (downloads.list().map { it.toQItem() } + ytCache.metas().map { it.toQItem() } + scCache.metas().map { it.toQItem() })
            .map { q ->
                if (q.provider() == Source.SOUNDCLOUD) MusicItem(source = Source.SOUNDCLOUD, url = q.key, title = q.title, subtitle = q.artist, artist = q.artist, artists = q.artist, duration = q.duration, thumbnail = q.thumbnail)
                else MusicItem(source = Source.YTM, videoId = q.key, id = q.key, title = q.title, subtitle = q.artist, artist = q.artist, artists = q.artist, duration = q.duration, thumbnail = q.thumbnail)
            }.distinctBy { it.source + ":" + it.key() }

    /** `findAudio`'s local places: the downloaded file, then the provider's playback cache. */
    fun localAudio(item: com.muratkirazkaya.meiro.playback.QItem): java.io.File? =
        downloads.file(item.provider(), item.key) ?: cacheFor(item.provider()).get(item.key)

    /** Once audio plays: a downloaded track is marked as listened now; a streamed one is cached from its URL (`audioStarted`). */
    val audioHooks = com.muratkirazkaya.meiro.playback.AudioHooks { item, r ->
        if (downloads.get(item.provider(), item.key) != null) { downloads.touch(item.provider(), item.key); pushDownloadsLater() }
        else if (!r.local) cacheFor(item.provider()).add(item, r.url, r.hls)
    }

    /** The resolver the downloads use (yt-dlp on the phone, its own URL cache). */
    private val dlResolver by lazy { com.muratkirazkaya.meiro.playback.AudioResolver(context, dlScope, log = { Log.i(DL_TAG, it) }) }

    private val _downloadEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Snacks the downloads ask for (a failure, mostly); the shell shows them. */
    val downloadEvents: kotlinx.coroutines.flow.SharedFlow<String> = _downloadEvents

    /** The download queue: 2 at a time, 30 minutes each, a batch per list or track asked for. */
    val downloader: com.muratkirazkaya.meiro.downloads.Downloader by lazy {
        com.muratkirazkaya.meiro.downloads.Downloader(
            downloads, dlScope,
            resolve = { YtdlpSetup.ensure(context) { m -> Log.i(DL_TAG, m) }; dlResolver.network(it) },
            fetch = fetch,
            cached = { cacheFor(it.provider()).get(it.key) },
            onFinished = { b ->
                Log.i(DL_TAG, "download: \"${b.name}\" ended, ${b.done} kept, ${b.failed} failed")
                if (b.failed > 0) _downloadEvents.tryEmit(if (b.single) "\u201C${b.name}\u201D indirilemedi" else "${b.failed} şarkı indirilemedi")
            },
            onStored = { rec ->
                pushDownloadsLater()
                // The cover is kept with the file: fetched now into the cover cache, so it shows offline.
                if (rec.thumbnail.startsWith("http")) coil.Coil.imageLoader(context).enqueue(coil.request.ImageRequest.Builder(context).data(rec.thumbnail).build())
            },
            log = { Log.i(DL_TAG, it) },
            allowed = { downloadAllowed() },
        )
    }

    /** Takes a download out (its file goes); signed in, the removal is sent as `deleted` (`removeDownload`). */
    fun removeDownload(source: String, key: String) {
        val gone = downloads.remove(source, key)
        Log.i(DL_TAG, "download: removed $source:$key ${if (gone == null) "(was not kept)" else gone.file}")
        pushDownloadsLater()
    }

    /** The downloads as the server wants them (`syncSnapshot`'s downloads): every kept track and every removal. */
    private fun downloadSnapshot(): List<com.muratkirazkaya.meiro.sync.Download> = com.muratkirazkaya.meiro.downloads.DownloadSync.snapshot(downloads, Build.MODEL)

    private val pushes = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED).also { ch ->
        dlScope.launch {
            for (u in ch) {
                try {
                    delay(500)
                    engine.syncSnapshot().queue(engine.outbox)
                    engine.syncNow(); engine.settled()
                    Log.i(DL_TAG, "download: pushed, outbox pending=${engine.outbox.pending()} error=${engine.lastError.ifEmpty { "none" }}")
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.i(DL_TAG, "download: push failed: $e") }
            }
        }
    }

    /** Signed in, the downloads are queued and sent (the desktop sends them with its next sync); changes close together go once. */
    fun pushDownloadsLater() {
        if (session().signedIn) pushes.trySend(Unit)
    }

    /** Where the playback engine files plays: the history and the sync outbox (`recordHistory`, `queueEvent`). */
    fun playStore(): PlayStore = playStore

    fun session(): Session {
        val s = engine.settings
        return Session(s.syncEmail.isNotEmpty() && !engine.expired, engine.expired, s.syncEmail, s.syncUrl)
    }

    /** Signs in to [server] (a bare host means https://). Returns the error to show, or null once signed in. */
    suspend fun signIn(server: String, email: String, password: String): String? = withContext(Dispatchers.IO) {
        val url = normalizeServer(server)
        engine.setSyncUrl(url)
        Log.i(TAG, "sign-in: server=${engine.settings.syncUrl} email=${email.trim()}")
        engine.signIn(email, password)
        if (engine.token.isNotEmpty()) {
            Log.i(TAG, "sign-in: ok, account ${engine.settings.syncEmail}")
            SyncScheduler.schedule(context)
            null
        } else {
            val err = engine.lastError.ifEmpty { "Could not sign in." }
            Log.i(TAG, "sign-in: failed: $err")
            err
        }
    }

    /**
     * Makes a list (`library.Create`): into the local store, and, when signed in, into the outbox right away (so it
     * waits there if the server cannot be reached) and on its way to the server. Returns the new list's local id.
     */
    suspend fun createList(name: String): String = withContext(Dispatchers.IO) {
        val lib = libraries.create(name)
        Log.i(TAG, "list: created id=${lib.id} name=${lib.name}")
        if (session().signedIn) {
            engine.syncSnapshot().queue(engine.outbox)
            Log.i(TAG, "list: queued, outbox pending=${engine.outbox.pending()}")
            engine.syncNow(); engine.settled()
            Log.i(TAG, "list: pushed, outbox pending=${engine.outbox.pending()} error=${engine.lastError.ifEmpty { "none" }}")
        }
        lib.id
    }

    /**
     * `duplicateAsMeiro` (`librarypage.go:164`): a new list named [name] holding [items], with [cover] as its picture,
     * then (signed in) queued and sent as [createList] does. Returns the new list's local id and how many tracks it got.
     */
    suspend fun copyAsList(name: String, cover: String, items: List<com.muratkirazkaya.meiro.data.Item>): Pair<String, Int> = withContext(Dispatchers.IO) {
        val lib = libraries.create(name.trim().ifEmpty { "Kopyalanan liste" })
        if (cover.isNotEmpty()) libraries.setCover(lib.id, cover)
        val added = libraries.addItems(lib.id, items)
        Log.i(TAG, "list: copied id=${lib.id} name=${lib.name} tracks=$added")
        // The desktop links the copied tracks to MusicBrainz in the background (`autoLinkAll(copied)`).
        linker.autoLinkAll(items.map { it.toQItem() })
        if (session().signedIn) {
            engine.syncSnapshot().queue(engine.outbox)
            engine.syncNow(); engine.settled()
            Log.i(TAG, "list: copy pushed, outbox pending=${engine.outbox.pending()} error=${engine.lastError.ifEmpty { "none" }}")
        }
        lib.id to added
    }

    /** Signed in, the lists' changes are queued in the outbox and sent (as [createList] does); [what] names them in the log. */
    private suspend fun pushLists(what: String) {
        if (!session().signedIn) return
        engine.syncSnapshot().queue(engine.outbox)
        engine.syncNow(); engine.settled()
        Log.i(TAG, "list: $what pushed, outbox pending=${engine.outbox.pending()} error=${engine.lastError.ifEmpty { "none" }}")
    }

    /**
     * `addItemToLibrary`: puts [item] in the list [id] unless it holds it already (the same track, or the same recording
     * from the other provider). True when it was added. Signed in, the change goes to the server.
     */
    suspend fun addToList(id: String, item: com.muratkirazkaya.meiro.data.Item): Boolean = withContext(Dispatchers.IO) {
        val added = libraries.addItem(id, item)
        Log.i(TAG, "list: add ${item.source}:${item.key} to id=$id added=$added")
        if (added) { pushLists("add"); linker.autoLinkAll(listOf(item.toQItem())) }
        added
    }

    /** `removeFromLibrary`: takes the track with [key] out of the list [id] (a tombstone the server is told of). */
    suspend fun removeFromList(id: String, key: String) = withContext(Dispatchers.IO) {
        libraries.removeItem(id, key)
        Log.i(TAG, "list: removed $key from id=$id")
        pushLists("remove")
    }

    suspend fun renameList(id: String, name: String) = withContext(Dispatchers.IO) {
        libraries.rename(id, name)
        Log.i(TAG, "list: renamed id=$id to $name")
        pushLists("rename")
    }

    suspend fun deleteList(id: String) = withContext(Dispatchers.IO) {
        libraries.delete(id)
        Log.i(TAG, "list: deleted id=$id")
        pushLists("delete")
    }

    /** "Sanatçıya git" for a track whose artist page is not known: YouTube Music's search for the artist's name. */
    suspend fun findArtist(name: String): String? = withContext(Dispatchers.IO) {
        com.muratkirazkaya.meiro.ui.TrackMenuMapping.pickArtist(ytm.search(name), name)
            .also { Log.i(TAG, "menu: artist \"$name\" -> ${it ?: "none"}") }
    }

    /** "Albüme git": YouTube Music's search for the album's name and the artist. */
    suspend fun findAlbum(album: String, artist: String): String? = withContext(Dispatchers.IO) {
        com.muratkirazkaya.meiro.ui.TrackMenuMapping.pickAlbum(ytm.search("$album $artist".trim()), album)
            .also { Log.i(TAG, "menu: album \"$album\" -> ${it ?: "none"}") }
    }

    /** "Kaynağı değiştir" on a row of the list [id]: the list plays the track at [key] from [source] from now on. */
    suspend fun setListSource(id: String, key: String, source: String): Boolean = withContext(Dispatchers.IO) {
        val changed = libraries.setSource(id, key, source)
        Log.i(TAG, "list: source of $key in id=$id -> $source changed=$changed")
        if (changed) pushLists("source")
        changed
    }

    /** The anonymous YouTube Music client the album and artist pages read (the search has its own). */
    val ytm: YtmClient by lazy { YtmClient() }

    /** Auto-play's generated queue (`GetUpNext`): YouTube Music's tracks after a video, as queue items. */
    val upNextSource = com.muratkirazkaya.meiro.playback.UpNextSource { videoId, playlistId, continuation ->
        val page = ytm.upNext(videoId, playlistId, continuation)
        val items = page.items.filter { it.videoId.isNotEmpty() }.map { m ->
            with(com.muratkirazkaya.meiro.ui.SearchMapping) { m.toQItem() }
                .copy(kind = if (m.kind == com.muratkirazkaya.meiro.youtube.ItemKind.Video) com.muratkirazkaya.meiro.playback.Kind.Video else com.muratkirazkaya.meiro.playback.Kind.Track)
        }
        com.muratkirazkaya.meiro.playback.GeneratedPage(items, page.continuation, page.playlistId)
    }

    /**
     * Meiro's own picks for the queue (`recs.picks`): the stored Daily mix's tracks, with their reasons. The desktop keeps
     * the day's ranking in memory; the phone ranks only when it makes the mix, so the mix is the ranking it keeps.
     */
    fun suggestionPicks(): List<com.muratkirazkaya.meiro.playback.QItem> =
        if (!_prefs.value.suggestions) emptyList() else com.muratkirazkaya.meiro.ui.HomeMapping.dailyMix(libraries.list(), engine.settings.dailyMixId)?.items?.map { it.toQItem() }.orEmpty()

    private val playPrefs = context.getSharedPreferences("meiro-playback", Context.MODE_PRIVATE)
    /** Auto-play (`settings.AutoPlay`), on unless turned off; kept on this device only (the desktop keeps it in its settings file). */
    fun autoPlay(): Boolean = playPrefs.getBoolean("autoPlay", true)
    fun saveAutoPlay(on: Boolean) { playPrefs.edit().putBoolean("autoPlay", on).apply() }

    private val _staticLyrics = MutableStateFlow(playPrefs.getBoolean("staticLyrics", false))
    /** "Düz" chosen over "Senkron" for timed lyrics (`settings.StaticLyrics`); kept on this device only. */
    val staticLyrics: StateFlow<Boolean> = _staticLyrics
    fun saveStaticLyrics(on: Boolean) { _staticLyrics.value = on; playPrefs.edit().putBoolean("staticLyrics", on).apply() }

    /** The loads behind the player's "Sözler" and "Benzerler" tabs: YouTube Music then LRCLIB, and YouTube Music's related tab. */
    val trackTabs: com.muratkirazkaya.meiro.lyrics.TrackTabs by lazy {
        val lrclib = com.muratkirazkaya.meiro.lyrics.LrclibClient(log = { Log.i(TAG, it) })
        com.muratkirazkaya.meiro.lyrics.TrackTabs(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            com.muratkirazkaya.meiro.lyrics.LyricsFinder({ ytm.lyrics(it) { m -> Log.i(TAG, m) } }, { lrclib.get(it) }, { Log.i(TAG, it) }),
            { videoId ->
                val t0 = System.nanoTime()
                YtmRelated.tracks(ytm.related(videoId)).distinctBy { it.videoId }
                    .also { Log.i(TAG, "related: $videoId ${it.size} tracks in ${(System.nanoTime() - t0) / 1_000_000} ms") }
            },
        )
    }

    /**
     * `setLibrarySource`: the lists that hold the track that played under [oldKey] and also hold it at [source] play it
     * from [source] from now on; signed in, the change is queued and sent.
     */
    suspend fun switchListSource(oldKey: String, source: String) = withContext(Dispatchers.IO) {
        var changed = 0
        for (lib in libraries.list()) for (item in lib.items) {
            val holds = item.sources.any { it.source == source } || item.source == source
            val same = item.key == oldKey || item.sources.any { it.id == oldKey || it.url == oldKey }
            if (holds && same && libraries.setSource(lib.id, item.key, source)) changed++
        }
        Log.i(TAG, "source: $changed list tracks now play from $source")
        if (changed > 0 && session().signedIn) {
            engine.syncSnapshot().queue(engine.outbox)
            engine.syncNow(); engine.settled()
        }
    }

    private val pages = object : LinkedHashMap<String, BrowsePage>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BrowsePage>?) = size > 20
    }

    /**
     * An album (`MPR...`) or artist (`UC...`) page, read once per process and kept for the last 20 pages, so going
     * back to a page shows it at once (the desktop keeps its pages the same way while the app runs).
     */
    suspend fun browsePage(id: String): BrowsePage {
        synchronized(pages) { pages[id] }?.let { return it }
        val t0 = System.nanoTime()
        val page = if (com.muratkirazkaya.meiro.youtube.isAlbumId(id)) ytm.album(id) else ytm.artist(id)
        Log.i(TAG, "browse: $id \"${page.title}\" ${page.sections.size} sections in ${(System.nanoTime() - t0) / 1_000_000} ms")
        synchronized(pages) { pages[id] = page }
        return page
    }

    private val recScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val _mixBusy = MutableStateFlow(false)
    /** A Daily mix run is on its way (the settings say so and keep "Şimdi yap" from asking twice). */
    val mixBusy: StateFlow<Boolean> = _mixBusy

    /** Makes the Daily mix (recommendations.go): related songs from YouTube Music and SoundCloud, kept for a day on disk. */
    val dailyMix: DailyMixMaker by lazy {
        DailyMixMaker(AppRelatedSources(context, ytm), RelatedCache(java.io.File(context.filesDir, "recommendations-cache.json")) { Log.i(MIX_TAG, it) }, log = { Log.i(MIX_TAG, it) })
    }

    /**
     * The desktop's `recommendTick`, on its schedule: the first check 45 s after the app starts, then every hour while
     * the process lives. The phone uses the ranking only for the Daily mix (its queue takes no suggestions yet), so a
     * check does nothing once today's mix is made, here or on another device.
     */
    fun startRecommendations() {
        recScope.launch {
            delay(DailyMixMaker.FIRST_CHECK.toMillis())
            while (true) {
                try { recommendTick("schedule") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.i(MIX_TAG, "daily mix: run failed: $e") }
                delay(DailyMixMaker.CHECK_EVERY.toMillis())
            }
        }
    }

    /**
     * One run (`refreshRecommendations` + `writeDailyMix`): nothing when today's mix is made or a run is on its way;
     * otherwise gather, rank, write the mix when there are at least 5 picks and (signed in) send it. True when it wrote.
     */
    suspend fun recommendTick(reason: String, remake: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!_prefs.value.suggestions) { Log.i(MIX_TAG, "daily mix ($reason): recommendations are off"); return@withContext false }
        if (!remake && engine.dailyMixMadeToday()) { Log.i(MIX_TAG, "daily mix ($reason): today's mix is made (${engine.settings.dailyMixDate})"); return@withContext false }
        if (!recBusy.compareAndSet(false, true)) return@withContext false
        _mixBusy.value = true
        try {
            val t0 = System.nanoTime()
            val items = kotlinx.coroutines.withTimeoutOrNull(DailyMixMaker.RUN_TIMEOUT.toMillis()) {
                dailyMix.make(
                    profileNow = {
                        val dl = downloads.list().map { com.muratkirazkaya.meiro.recommend.Signal(it.source, it.key, it.title, it.artist, com.muratkirazkaya.meiro.recommend.SignalKind.Downloaded, java.time.Instant.ofEpochMilli(it.downloadedAt)) }
                        dailyMix.profile(engine.history(), libraries.list(), engine.settings.dailyMixId, java.time.Instant.now(), dl)
                    },
                    history = { engine.history() }, now = java.time.Instant.now(), zone = java.time.ZoneId.systemDefault(),
                    explore = _prefs.value.explore / 100.0,
                )
            }
            if (items == null) { Log.i(MIX_TAG, "daily mix ($reason): gave up after ${DailyMixMaker.RUN_TIMEOUT.toMinutes()} min"); return@withContext false }
            val wrote = engine.writeDailyMix(items, remake)
            Log.i(MIX_TAG, "daily mix ($reason): ${items.size} picks in ${(System.nanoTime() - t0) / 1_000_000} ms, written=$wrote")
            if (wrote && session().signedIn) {
                engine.syncSnapshot().queue(engine.outbox)
                engine.syncNow(); engine.settled()
                Log.i(MIX_TAG, "daily mix ($reason): pushed, outbox pending=${engine.outbox.pending()} error=${engine.lastError.ifEmpty { "none" }}")
            }
            wrote
        } finally {
            recBusy.set(false)
            _mixBusy.value = false
        }
    }

    /** One round of what the desktop does every 5 minutes: send what waits, read the lists and the plays. */
    suspend fun sync(reason: String) = withContext(Dispatchers.IO) {
        if (!session().signedIn) { Log.i(TAG, "sync ($reason): skipped, not signed in"); return@withContext }
        engine.loadSyncToken()
        engine.settled()
        engine.syncNow(); engine.pullChanges(); engine.pullEvents()
        engine.settled()
        _historyVersion.value += 1
        val s = engine.settings
        Log.i(
            TAG,
            "sync ($reason): done, lists=${libraries.list().size} lastAt=${s.syncLastAt} cursor=${s.syncCursor} " +
                "error=${engine.lastError.ifEmpty { "none" }} sessionEnded=${engine.expired}",
        )
    }

    // ---- M6b: settings, history, stats, account ---------------------------------------------------------------

    private val settingsPrefs = context.getSharedPreferences("meiro-settings", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(loadPrefs())

    /** The phone's own settings (the desktop keeps the same ones in its settings file; none of them is synced). */
    val prefs: StateFlow<AppPrefs> = _prefs

    private fun loadPrefs() = AppPrefs(
        theme = com.muratkirazkaya.meiro.ui.screens.ThemeChoice.entries.getOrElse(settingsPrefs.getInt("theme", 0)) { com.muratkirazkaya.meiro.ui.screens.ThemeChoice.System },
        coverColor = settingsPrefs.getBoolean("coverColor", true),
        suggestions = settingsPrefs.getBoolean("suggestions", true),
        explore = settingsPrefs.getInt("explore", 30),
        wifiOnly = settingsPrefs.getBoolean("wifiOnly", true),
        historyPaused = settingsPrefs.getBoolean("historyPaused", false),
    )

    /** Changes the settings and writes them at once. */
    fun updatePrefs(f: (AppPrefs) -> AppPrefs) {
        val p = f(_prefs.value)
        _prefs.value = p
        settingsPrefs.edit().putInt("theme", p.theme.ordinal).putBoolean("coverColor", p.coverColor).putBoolean("suggestions", p.suggestions)
            .putInt("explore", p.explore).putBoolean("wifiOnly", p.wifiOnly).putBoolean("historyPaused", p.historyPaused).apply()
        Log.i(TAG, "settings: $p")
    }

    /** "Yalnız Wi-Fi'de indir": a download may start only on an unmetered network while it is on. */
    fun downloadAllowed(): Boolean = !_prefs.value.wifiOnly || com.muratkirazkaya.meiro.downloads.Online.unmetered.value

    /** "Geçmişi temizle" (`clearHistory`): every play goes here; signed in, the account's history on every device. */
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        engine.clearHistory()
        _historyVersion.value += 1
        Log.i(TAG, "history: cleared (signed in: ${session().signedIn}), pending clear=${engine.settings.historyClearPending}")
        if (session().signedIn) {
            engine.settled()
            Log.i(TAG, "history: clear pushed, pending clear=${engine.settings.historyClearPending} clearedAt=${engine.settings.historyClearedAt} error=${engine.lastError.ifEmpty { "none" }}")
        }
    }

    /**
     * "Özet" for [period]: the server's summary signed in (every device), else the one folded from this device's history.
     * The second value says where it came from ("server" or "local"); a server that cannot be reached falls back to local.
     */
    suspend fun stats(period: com.muratkirazkaya.meiro.ui.StatsPeriod, now: java.time.ZonedDateTime): Pair<com.muratkirazkaya.meiro.sync.StatsSummary, String> = withContext(Dispatchers.IO) {
        val anchor = com.muratkirazkaya.meiro.ui.StatsMapping.anchor(period, now)
        if (session().signedIn && engine.token.isNotEmpty()) {
            try {
                val s = com.muratkirazkaya.meiro.sync.MeiroClient(engine.settings.syncUrl, engine.token).statsSummary(if (period == com.muratkirazkaya.meiro.ui.StatsPeriod.Year) "year" else "month", anchor)
                Log.i(TAG, "stats: server $anchor plays=${s.totals.plays} playedMs=${s.totals.playedMs} tracks=${s.totals.uniqueTracks} artists=${s.totals.uniqueArtists}")
                return@withContext s to "server"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                Log.i(TAG, "stats: server failed ($e), folding the local history")
            }
        }
        val s = com.muratkirazkaya.meiro.ui.StatsMapping.local(engine.history(), period, now)
        Log.i(TAG, "stats: local $anchor plays=${s.totals.plays} playedMs=${s.totals.playedMs}")
        s to "local"
    }

    /** "Çıkış yap" (`signOutSync`): the account is forgotten (its token leaves the disk); the lists and plays here stay. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        // The engine writes its settings to Room, which must not happen on the main thread.
        engine.signOut()
        WorkManager.getInstance(context).cancelUniqueWork(SyncScheduler.NAME)
        _historyVersion.value += 1
        Log.i(TAG, "sign-out: done, account=${engine.settings.syncEmail.ifEmpty { "none" }}")
    }

    /** The playback caches' and the other caches' sizes, for the settings ("480 MB / 512 MB · 112 şarkı"). */
    fun coverCacheUsage(): Pair<Long, Int> {
        val dir = java.io.File(context.cacheDir, "covers")
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        // Coil keeps each picture as a ".1" data file beside its ".0" metadata file.
        return files.sumOf { it.length() } to files.count { it.name.endsWith(".1") }
    }

    fun clearCoverCache() {
        val loader = coil.Coil.imageLoader(context)
        loader.diskCache?.clear(); loader.memoryCache?.clear()
        Log.i(TAG, "settings: cover cache cleared")
    }

    private val mbDir get() = java.io.File(context.cacheDir, "musicbrainz")
    fun mbCacheUsage(): Long = mbDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clearMbCache() {
        val n = mbDir.listFiles()?.count { it.isFile && it.delete() } ?: 0
        Log.i(TAG, "settings: MusicBrainz cache cleared ($n files)")
    }

    /** The bundled yt-dlp's version ("2026.10.12"), null when it cannot be read. */
    suspend fun ytdlpVersion(): String? = withContext(Dispatchers.IO) {
        try {
            YtdlpSetup.ensure(context) { Log.i(TAG, it) }
            YoutubeDL.getInstance().version(context.applicationContext)
                ?: YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList()).apply { addOption("--version") }).out.trim().takeIf { it.isNotEmpty() }
        } catch (e: Throwable) { Log.i(TAG, "yt-dlp version: $e"); null }
    }

    /** "Güncelle": yt-dlp is updated now (it is also updated once a day by itself). Returns the snack. */
    suspend fun updateYtdlp(): String = withContext(Dispatchers.IO) {
        try {
            YtdlpSetup.ensure(context) { Log.i(TAG, it) }
            val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            context.getSharedPreferences("meiro-ytdlp", Context.MODE_PRIVATE).edit().putLong("updatedAt", System.currentTimeMillis()).apply()
            Log.i(TAG, "yt-dlp update (settings): $status")
            if (status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE) "yt-dlp güncel" else "yt-dlp güncellendi"
        } catch (e: Throwable) {
            Log.i(TAG, "yt-dlp update (settings) failed: $e")
            "yt-dlp güncellenemedi"
        }
    }

    /** The app's own version, as the package names it. */
    fun appVersion(): String = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    companion object {
        const val TAG = "MeiroSync"
        const val SEARCH_TAG = "MeiroSearch"
        const val MIX_TAG = "MeiroMix"
        const val LINK_TAG = "MeiroLink"
        const val DL_TAG = "MeiroDownload"

        /** The server field takes a bare host as the mock shows it; anything with a scheme is kept. */
        fun normalizeServer(s: String): String {
            val t = s.trim()
            return if (t.isEmpty() || t.contains("://")) t else "https://$t"
        }

        /** The server field shows the address without https://, as the mock does. */
        fun displayServer(url: String): String = url.removePrefix("https://")
    }
}

/**
 * Periodic sync. Android runs periodic work at most every 15 minutes (the desktop syncs every 5); the app also syncs
 * when it comes to the foreground and right after signing in. Needs a network.
 */
object SyncScheduler {
    const val NAME = "meiro-sync"
    const val INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as MeiroApplication).container().sync("worker")
        return Result.success()
    }
}

/** What the Daily mix asks: YouTube Music's "Related" tab, and SoundCloud's recommendations through the bundled yt-dlp. */
class AppRelatedSources(private val context: Context, private val ytm: YtmClient) : RelatedSources {
    private val pids = AtomicLong()

    override suspend fun youtube(videoId: String): List<MusicItem> = YtmRelated.tracks(ytm.related(videoId))

    override suspend fun soundCloud(pageUrl: String): List<MusicItem> {
        YtdlpSetup.ensure(context) { Log.i(AppContainer.MIX_TAG, it) }
        val pid = "screlated-${pids.incrementAndGet()}"
        val cache = java.io.File(context.cacheDir, "yt-dlp").apply { mkdirs() }
        val req = YoutubeDLRequest(emptyList()).apply { addCommands(listOf("--cache-dir", cache.path) + SoundCloudSearch.relatedArgs(pageUrl)) }
        val out = try {
            runInterruptible(Dispatchers.IO) { YoutubeDL.getInstance().execute(req, pid, null).out }
        } catch (e: kotlinx.coroutines.CancellationException) {
            YoutubeDL.getInstance().destroyProcessById(pid)
            throw e
        }
        return SoundCloudSearch.parse(out)
    }
}

/** The real providers: the anonymous YouTube Music client, and SoundCloud through the bundled yt-dlp (`scsearch50:`). */
class AppSearchProviders(private val context: Context) : SearchProviders {
    private val ytm = YtmClient()
    private val pids = AtomicLong()

    override suspend fun ytm(query: String, params: String): SearchResult = ytm.search(query, params)
    override suspend fun ytmMore(token: String): SearchResult = ytm.continueSearch(token)

    override suspend fun soundCloud(query: String): List<MusicItem> {
        YtdlpSetup.ensure(context) { Log.i(AppContainer.SEARCH_TAG, it) }
        val pid = "scsearch-${pids.incrementAndGet()}"
        // youtubedl-android passes --no-cache-dir; a cache lets yt-dlp keep SoundCloud's client id instead of reading
        // soundcloud.com and its scripts again on every search (two requests fewer each time).
        val cache = java.io.File(context.cacheDir, "yt-dlp").apply { mkdirs() }
        val req = YoutubeDLRequest(emptyList()).apply { addCommands(listOf("--cache-dir", cache.path) + SoundCloudSearch.args(query)) }
        val out = try {
            // Interruptible, so the 7 s search timeout really ends it; the yt-dlp process is ended with it.
            runInterruptible(Dispatchers.IO) { YoutubeDL.getInstance().execute(req, pid, null).out }
        } catch (e: kotlinx.coroutines.CancellationException) {
            YoutubeDL.getInstance().destroyProcessById(pid)
            throw e
        }
        return SoundCloudSearch.parse(out)
    }
}
