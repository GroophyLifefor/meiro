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
class MeiroApplication : Application(), coil.ImageLoaderFactory {
    private lateinit var ready: Deferred<AppContainer>

    override fun onCreate() {
        super.onCreate()
        com.muratkirazkaya.meiro.downloads.Online.start(this)
        ready = CoroutineScope(Dispatchers.IO).async { AppContainer(this@MeiroApplication).also { it.startRecommendations(); it.startAutoLink() } }
    }

    suspend fun container(): AppContainer = ready.await()

    /**
     * The cover cache (M5): Coil's disk cache in the app's cache dir (250 MB), read whatever the server's cache headers
     * say, so the covers of downloaded and recently seen tracks show without a network.
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(250L shl 20).build() }
        .respectCacheHeaders(false)
        .build()
}
