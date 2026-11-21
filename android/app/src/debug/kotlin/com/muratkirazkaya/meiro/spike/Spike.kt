package com.muratkirazkaya.meiro.spike

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.muratkirazkaya.meiro.firstUrlLine
import com.muratkirazkaya.meiro.lastErrorLine
import com.muratkirazkaya.meiro.parseSoundCloudJson
import com.muratkirazkaya.meiro.soundCloudArgs
import com.muratkirazkaya.meiro.ytmArgs
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

object Spike {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context
    private var ready = false
    private var controller: MediaController? = null
    private var playTapAt = 0L
    private var gotReady = false

    var ytmUrl: String? = null
    var scUrl: String? = null
    var scHls = false

    fun init(context: Context) {
        if (this::app.isInitialized) return
        app = context.applicationContext
        scope.launch {
            val t = SystemClock.elapsedRealtime()
            try {
                withContext(Dispatchers.IO) { YoutubeDL.getInstance().init(app) }
                ready = true
                SpikeLog.log("INIT ok in ${SystemClock.elapsedRealtime() - t} ms")
                logVersion()
            } catch (e: Throwable) {
                SpikeLog.log("INIT FAILED after ${SystemClock.elapsedRealtime() - t} ms: $e")
            }
        }
    }

    private suspend fun awaitReady() { var n = 0; while (!ready && n++ < 200) delay(100) }

    private suspend fun logVersion() {
        val v = withContext(Dispatchers.IO) { runCatching { YoutubeDL.getInstance().version(app) }.getOrNull() }
        val actual = runCatching { run(listOf("--version"), "ver").first.trim() }.getOrElse { "err: ${it.message?.take(120)}" }
        SpikeLog.log("yt-dlp version: library=$v binary(--version)=$actual")
    }

    private fun logMemory() {
        val rt = Runtime.getRuntime()
        val heapMb = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val pss = Debug.getPss() // KB, this process only
        SpikeLog.log("memory: javaHeap=${heapMb}MB pss=${pss / 1024}MB")
        // Child processes (python) are separate; list our uid's processes via ActivityManager.
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.forEach { p ->
            if (p.processName.startsWith(app.packageName)) {
                val kb = am.getProcessMemoryInfo(intArrayOf(p.pid))[0].totalPss
                SpikeLog.log("memory: process ${p.processName} pid=${p.pid} pss=${kb / 1024}MB")
            }
        }
    }

    private suspend fun run(args: List<String>, pid: String): Pair<String, String> {
        val req = YoutubeDLRequest(emptyList())
        req.addCommands(args)
        return withContext(Dispatchers.IO) {
            try {
                withTimeout(90_000) {
                    val r = YoutubeDL.getInstance().execute(req, pid, null)
                    r.out to r.err
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                YoutubeDL.getInstance().destroyProcessById(pid)
                throw RuntimeException("timeout after 90s")
            }
        }
    }

    fun resolveYtm(videoId: String, jsless: Boolean) = scope.launch {
        val label = if (jsless) "YTM visionos" else "YTM default/JS"
        awaitReady()
        if (!ready) { SpikeLog.log("$label: yt-dlp not initialised"); return@launch }
        val t = SystemClock.elapsedRealtime()
        SpikeLog.log("$label: resolving $videoId ...")
        try {
            val (out, _) = run(ytmArgs(videoId, jsless), "ytm-${if (jsless) "v" else "d"}")
            val url = firstUrlLine(out)
            val ms = SystemClock.elapsedRealtime() - t
            if (url == null) {
                SpikeLog.log("$label: FAIL in $ms ms: empty output")
            } else {
                ytmUrl = url
                SpikeLog.log("$label: OK in $ms ms url=${url.take(90)}...")
            }
        } catch (e: Throwable) {
            val ms = SystemClock.elapsedRealtime() - t
            val msg = e.message.orEmpty()
            SpikeLog.log("$label: FAIL in $ms ms: ${lastErrorLine(msg) ?: msg.lineSequence().lastOrNull { it.isNotBlank() } ?: e.toString()}")
        }
        logVersion()
        logMemory()
    }

    fun resolveSoundCloud(url: String) = scope.launch {
        awaitReady()
        if (!ready) { SpikeLog.log("SC: yt-dlp not initialised"); return@launch }
        val t = SystemClock.elapsedRealtime()
        SpikeLog.log("SC: resolving $url ...")
        try {
            val (out, _) = run(soundCloudArgs(url), "sc")
            val info = parseSoundCloudJson(out)
            scUrl = info.url
            scHls = info.hls
            SpikeLog.log("SC: OK in ${SystemClock.elapsedRealtime() - t} ms duration=${info.durationSec}s hls=${info.hls} url=${info.url.take(90)}...")
        } catch (e: Throwable) {
            val msg = e.message.orEmpty()
            SpikeLog.log("SC: FAIL in ${SystemClock.elapsedRealtime() - t} ms: ${lastErrorLine(msg) ?: msg.lineSequence().lastOrNull { it.isNotBlank() } ?: e.toString()}")
        }
        logVersion()
        logMemory()
    }

    fun update() = scope.launch {
        awaitReady()
        if (!ready) { SpikeLog.log("update: not initialised"); return@launch }
        val t = SystemClock.elapsedRealtime()
        SpikeLog.log("update: updating yt-dlp (STABLE) ...")
        try {
            val r = withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            }
            SpikeLog.log("update: $r in ${SystemClock.elapsedRealtime() - t} ms")
        } catch (e: Throwable) {
            SpikeLog.log("update: FAIL in ${SystemClock.elapsedRealtime() - t} ms: $e")
        }
        logVersion()
    }

    /** which: "ytm" or "sc" */
    fun play(which: String) = scope.launch {
        val url = if (which == "ytm") ytmUrl else scUrl
        if (url == null) { SpikeLog.log("play $which: nothing resolved yet"); return@launch }
        playTapAt = SystemClock.elapsedRealtime()
        gotReady = false
        SpikeLog.log("play $which: tap")
        val c = controller ?: connect().also { controller = it }
        val item = MediaItem.Builder()
            .setMediaId(which)
            .setUri(url)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(android.net.Uri.parse(url)).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Spike $which").setArtist("Meiro").build())
            .apply { if (which == "sc" && scHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
        launch {
            delay(5000)
            SpikeLog.log("play $which: +5s position=${c.currentPosition} ms isPlaying=${c.isPlaying} state=${c.playbackState}")
        }
    }

    fun stop() = scope.launch { controller?.stop(); SpikeLog.log("stopped") }

    private suspend fun connect(): MediaController {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val c = kotlinx.coroutines.suspendCancellableCoroutine<MediaController> { cont ->
            val f = MediaController.Builder(app, token).buildAsync()
            f.addListener({ cont.resumeWith(runCatching { f.get() }) }, ContextCompat.getMainExecutor(app))
        }
        SpikeLog.log("MediaController connected after ${SystemClock.elapsedRealtime() - playTapAt} ms from tap")
        c.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) { 1 -> "IDLE"; 2 -> "BUFFERING"; 3 -> "READY"; 4 -> "ENDED"; else -> "?" }
                val since = SystemClock.elapsedRealtime() - playTapAt
                SpikeLog.log("state=$name at ${since} ms from tap")
                if (state == Player.STATE_READY && !gotReady) { gotReady = true; SpikeLog.log("tap->STATE_READY ${since} ms") }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val since = SystemClock.elapsedRealtime() - playTapAt
                SpikeLog.log("isPlaying=$isPlaying at ${since} ms from tap")
            }
            override fun onPlayerError(error: PlaybackException) {
                SpikeLog.log("player error: ${error.errorCodeName}: ${error.message} cause=${error.cause}")
            }
        })
        return c
    }

    /** Debug entry: `adb shell am start -n .../.spike.SpikeActivity --es action resolve_visionos`. */
    fun dispatch(action: String, ytId: String, scLink: String) {
        SpikeLog.log("adb action: $action")
        when (action) {
            "resolve_visionos" -> resolveYtm(ytId, true)
            "resolve_default" -> resolveYtm(ytId, false)
            "resolve_sc" -> resolveSoundCloud(scLink)
            "play_ytm" -> play("ytm")
            "play_sc" -> play("sc")
            "update" -> update()
            "stop" -> stop()
            "memory" -> logMemory()
            else -> SpikeLog.log("unknown action $action")
        }
    }
}
