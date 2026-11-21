package com.muratkirazkaya.meiro

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateListOf

const val TAG = "MeiroSpike"

object SpikeLog {
    val lines = mutableStateListOf<String>()
    private val t0 = SystemClock.elapsedRealtime()

    fun log(msg: String) {
        val line = "[+${SystemClock.elapsedRealtime() - t0}ms] $msg"
        Log.i(TAG, line)
        android.os.Handler(android.os.Looper.getMainLooper()).post { lines.add(line) }
    }
}
