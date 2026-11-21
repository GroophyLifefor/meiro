package com.muratkirazkaya.meiro.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.muratkirazkaya.meiro.playback.PlaybackHost
import com.muratkirazkaya.meiro.playback.QItem

/**
 * Debug builds only: starts and drives real playback from adb while no list screen exists.
 *
 *   adb shell am start -n com.muratkirazkaya.meiro/.debug.DebugPlayActivity --es items "ytm:dQw4w9WgXcQ,sc:https://soundcloud.com/forss/flickermood"
 *
 * An entry is `ytm:<videoId>` or `sc:<page url>`, optionally followed by `|title|artist|m:ss`. Extras: `index` (int,
 * start place), `action` = next | prev | pause | toggle | seek (with `ms`) | break (damages the stream URL: 403).
 */
class DebugPlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = applicationContext
        intent.getStringExtra("items")?.let { raw ->
            val items = raw.split(',').mapNotNull { QItem.parse(it) }
            Log.i("MeiroPlay", "debug: play ${items.size} items from ${intent.getIntExtra("index", 0)}")
            PlaybackHost.playQueue(ctx, items, intent.getIntExtra("index", 0), "debug")
        }
        when (val a = intent.getStringExtra("action")) {
            null -> {}
            "next" -> PlaybackHost.next(ctx)
            "prev" -> PlaybackHost.previous(ctx)
            "pause" -> PlaybackHost.pause(ctx)
            "toggle" -> PlaybackHost.togglePlay(ctx)
            "seek" -> PlaybackHost.seekTo(ctx, intent.getLongExtra("ms", intent.getStringExtra("ms")?.toLongOrNull() ?: 0L))
            "break" -> PlaybackHost.debugBreak(ctx)
            else -> Log.i("MeiroPlay", "debug: unknown action $a")
        }
        finish()
    }
}
