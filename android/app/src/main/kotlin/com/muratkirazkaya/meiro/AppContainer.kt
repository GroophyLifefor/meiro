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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** What the UI needs to know about the account, read from the engine. */
data class Session(val signedIn: Boolean, val expired: Boolean, val email: String, val server: String)

/** Manual DI: the Room database, the encrypted token store and the sync engine, created once per process. */
class AppContainer(private val context: Context) {
    private val db = Room.databaseBuilder(context, MeiroDatabase::class.java, "meiro.db").build()
    private val persistence = RoomPersistence(db)
    val libraries = LibraryStore(persistence)
    val engine: SyncEngine

    init {
        SyncLog.sink = { Log.i(TAG, it) }
        engine = SyncEngine(
            libraries, Outbox(persistence), EncryptedTokenStore(context), persistence, LinksStore(persistence),
            deviceName = Build.MODEL,
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

    /** One round of what the desktop does every 5 minutes: send what waits, read the lists and the plays. */
    suspend fun sync(reason: String) = withContext(Dispatchers.IO) {
        if (!session().signedIn) { Log.i(TAG, "sync ($reason): skipped, not signed in"); return@withContext }
        engine.loadSyncToken()
        engine.settled()
        engine.syncNow(); engine.pullChanges(); engine.pullEvents()
        engine.settled()
        val s = engine.settings
        Log.i(
            TAG,
            "sync ($reason): done, lists=${libraries.list().size} lastAt=${s.syncLastAt} cursor=${s.syncCursor} " +
                "error=${engine.lastError.ifEmpty { "none" }} sessionEnded=${engine.expired}",
        )
    }

    companion object {
        const val TAG = "MeiroSync"

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
