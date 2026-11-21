package com.muratkirazkaya.meiro.sync

/** Where the sync logs what Go's `log.Printf` logs. Swap [sink] (the app points it at Logcat). */
object SyncLog {
    @Volatile var sink: (String) -> Unit = { System.err.println(it) }
    fun log(message: String) = sink(message)
}
