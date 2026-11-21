package com.muratkirazkaya.meiro.data

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Time rules of the sync (PORTING 1.4.1). Last write wins by `updated_at` with sub-second precision, so every
 * timestamp is an [Instant] (nanoseconds) and goes on the wire as RFC 3339 with nanoseconds, UTC, trailing zeros
 * trimmed, exactly what Go's `time.RFC3339Nano` writes for a UTC time.
 */
object Rfc3339Nano {
    private val base = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)

    /** Go's zero `time.Time`, 0001-01-01T00:00:00Z: what an unset time is. */
    val ZERO: Instant = Instant.parse("0001-01-01T00:00:00Z")

    fun format(t: Instant): String {
        val head = base.format(t)
        if (t.nano == 0) return head + "Z"
        val frac = t.nano.toString().padStart(9, '0').trimEnd('0')
        return "$head.${frac}Z"
    }

    /** Any RFC 3339 time: fractional seconds of any length, any offset. */
    fun parse(s: String): Instant = OffsetDateTime.parse(s.trim()).toInstant()
}

val Instant.isZero: Boolean get() = this == Rfc3339Nano.ZERO

fun Instant?.orZero(): Instant = this ?: Rfc3339Nano.ZERO

/** The clock the stores stamp changes with; tests give one they control. */
fun interface Clock {
    fun now(): Instant

    companion object {
        val System: Clock = Clock { Instant.now() }

        /** A clock that never gives the same instant twice (one microsecond apart at least). */
        fun strictlyIncreasing(): Clock {
            val last = AtomicLong(0)
            return Clock {
                val real = Instant.now()
                val micros = real.epochSecond * 1_000_000 + real.nano / 1000
                val next = last.updateAndGet { prev -> if (micros > prev) micros else prev + 1 }
                Instant.ofEpochSecond(next / 1_000_000, (next % 1_000_000) * 1000)
            }
        }
    }
}
