package com.muratkirazkaya.meiro

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * Holds the app's one [AppContainer]. It opens the Room database and the Keystore-backed token store, which must
 * not happen on the main thread, so it is built on a background thread at process start and awaited by the UI
 * and by the WorkManager job.
 */
class MeiroApplication : Application() {
    private lateinit var ready: Deferred<AppContainer>

    override fun onCreate() {
        super.onCreate()
        ready = CoroutineScope(Dispatchers.IO).async { AppContainer(this@MeiroApplication) }
    }

    suspend fun container(): AppContainer = ready.await()
}
