package com.muratkirazkaya.meiro.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keeps the session token apart from the settings (internal/sync/token.go `TokenStore`). [load] returns null when no
 * token is saved (Go's ErrNoToken).
 */
interface TokenStore {
    fun load(): String?
    fun save(token: String)
    fun delete()
}

/** For JVM tests and for a signed-out default. */
class InMemoryTokenStore(initial: String? = null) : TokenStore {
    @Volatile private var token: String? = initial
    override fun load(): String? = token?.trim()?.ifEmpty { null }
    override fun save(token: String) { this.token = token }
    override fun delete() { token = null }
}

/** The token in EncryptedSharedPreferences (AES-256 keys held in the Android Keystore). */
class EncryptedTokenStore(context: Context, name: String = "meiro-sync-token") : TokenStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context, name,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    override fun load(): String? = prefs.getString(KEY, null)?.trim()?.ifEmpty { null }
    override fun save(token: String) { prefs.edit().putString(KEY, token).commit() }
    override fun delete() { prefs.edit().remove(KEY).commit() }
    private companion object { const val KEY = "token" }
}
