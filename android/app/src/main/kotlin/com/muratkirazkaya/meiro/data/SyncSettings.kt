package com.muratkirazkaya.meiro.data

import java.time.Instant

/** The settings fields the sync uses (internal/settings/settings.go). Null times are Go's zero time. */
data class SyncSettings(
    /** The Meiro server this device syncs with. */
    val syncUrl: String = DEFAULT_URL,
    /** While off nothing is sent and the plays that wait are kept. */
    val syncEnabled: Boolean = false,
    /** The account signed in for syncing, empty when none is. The token is not kept here: see TokenStore. */
    val syncEmail: String = "",
    /** When a sync last delivered everything it had. */
    val syncLastAt: Instant? = null,
    /** How far into the server's order of changes this device has read, for which server and account. */
    val syncCursor: Long = 0,
    val syncCursorUrl: String = "",
    val syncCursorEmail: String = "",
    val eventCursor: Long = 0,
    val eventCursorUrl: String = "",
    val eventCursorEmail: String = "",
    /** The newest clearing of the history, from any device, that this device has applied. */
    val historyClearedAt: Instant? = null,
    /** When the history was cleared here while signed in and the server has not been told yet. */
    val historyClearPending: Instant? = null,
    /** This device has begun reading lists back from the server (the first-sync rule). */
    val syncTwoWay: Boolean = false,
    /** The local list the Daily mix lives in, and the local date (yyyy-MM-dd) today's mix was made. */
    val dailyMixId: String = "",
    val dailyMixDate: String = "",
) {
    companion object {
        /** The server Meiro talks to on a first run (`sync.DefaultURL`). Tests and dev builds use their own. */
        const val DEFAULT_URL = "https://meiro.muratkirazkaya.com"
    }
}
