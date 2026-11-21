package com.muratkirazkaya.meiro.sync

import java.security.MessageDigest
import java.security.SecureRandom

/** Port of internal/sync/uuid.go. `deriveId` is NOT RFC 4122 UUIDv5: it hashes the namespace as 36-char text. */
object Ids {
    const val LIBRARY_NS = "6ba7b811-9dad-11d1-80b4-00c04fd430c8"
    const val DAILY_MIX_NS = "6ba7b812-9dad-11d1-80b4-00c04fd430c8"
    private val random = SecureRandom()

    /** A random UUID (version 4), generated once per record and kept, so a retry is the same record. */
    fun newId(): String {
        val b = ByteArray(16)
        random.nextBytes(b)
        b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        return format(b)
    }

    fun deriveId(namespace: String, name: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(namespace.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(name.toByteArray(Charsets.UTF_8))
        val b = md.digest().copyOf(16)
        b[6] = ((b[6].toInt() and 0x0f) or 0x50).toByte()
        b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
        return format(b)
    }

    fun libraryId(localId: String): String = deriveId(LIBRARY_NS, localId)

    /** The Daily mix's server identity: the same on every device of an account. */
    fun dailyMixId(): String = deriveId(DAILY_MIX_NS, "daily-mix")

    private fun format(b: ByteArray): String {
        val h = b.joinToString("") { "%02x".format(it) }
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }
}
