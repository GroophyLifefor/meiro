package com.muratkirazkaya.meiro.playback

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The bundled yt-dlp, set up once per process before its first use (a resolve or a SoundCloud search). YouTube changes
 * under yt-dlp, and the copy that ships in the app ages: it is updated in the app (PLAN.md), at most once a day, before
 * the first use of a process. A failed update (offline) is tried again next time.
 */
object YtdlpSetup {
    const val UPDATE_EVERY_MS = 24 * 60 * 60 * 1000L
    private val lock = Mutex()
    @Volatile private var ready = false

    suspend fun ensure(context: Context, log: (String) -> Unit) {
        if (ready) return
        lock.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context.applicationContext)
                updateIfDue(context, log)
            }
            ready = true
        }
    }

    private fun updateIfDue(context: Context, log: (String) -> Unit) {
        val prefs = context.getSharedPreferences("meiro-ytdlp", Context.MODE_PRIVATE)
        val last = prefs.getLong("updatedAt", 0)
        if (System.currentTimeMillis() - last < UPDATE_EVERY_MS) return
        val t0 = System.nanoTime()
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("updatedAt", System.currentTimeMillis()).apply()
            log("yt-dlp update: $status in ${(System.nanoTime() - t0) / 1_000_000} ms")
        } catch (e: Throwable) {
            log("yt-dlp update failed (will retry next start): ${e.message}")
        }
    }
}
