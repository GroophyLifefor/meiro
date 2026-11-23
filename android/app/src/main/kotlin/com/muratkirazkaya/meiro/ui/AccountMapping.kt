package com.muratkirazkaya.meiro.ui

import com.muratkirazkaya.meiro.AppContainer
import com.muratkirazkaya.meiro.ui.screens.AccountUi
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The account sheet's and the settings' account lines (`syncAccount`): who, whether everything is sent, when the last sync was. */
object AccountMapping {
    private val TR = Locale.forLanguageTag("tr")

    /** The address as the mock shows it: the part before "@", then "@…" ("murat@…"). */
    fun shortEmail(email: String): String = if ('@' in email) email.substringBefore('@') + "@…" else email

    /** When something last happened, in words: "az önce", "2 dk önce", "3 sa önce", "4 gün önce"; "hiç" for never. */
    fun ago(at: Instant?, now: Instant): String {
        if (at == null || at.epochSecond <= 0) return "hiç"
        val d = Duration.between(at, now).coerceAtLeast(Duration.ZERO)
        return when {
            d.toMinutes() < 1 -> "az önce"
            d.toHours() < 1 -> "${d.toMinutes()} dk önce"
            d.toDays() < 1 -> "${d.toHours()} sa önce"
            else -> "${d.toDays()} gün önce"
        }
    }

    /** Whether everything is on the server: "eşitleme güncel", "N kayıt bekliyor", "eşitlenemedi", "oturum bitti". */
    fun state(pending: Int, error: String, expired: Boolean): String = when {
        expired -> "oturum bitti"
        error.isNotEmpty() -> "eşitlenemedi"
        pending > 0 -> "$pending kayıt bekliyor"
        else -> "eşitleme güncel"
    }

    fun sheet(c: AppContainer, email: String, signedIn: Boolean, syncing: Boolean): AccountUi {
        val e = c.engine
        if (!signedIn) return AccountUi(avatarInitial(email), "Hesap yok · bu cihazda", "hesap yok", false)
        val st = state(e.outbox.pending(), e.lastError, e.expired)
        return AccountUi(avatarInitial(email), "${shortEmail(email)} · $st", if (syncing) "eşitleniyor" else ago(e.settings.syncLastAt, Instant.now()), true)
    }

    /** The settings' "Meiro hesabı" line: "murat@… · eşitleme güncel, 2 dk önce", or the guest's. */
    fun settingsLine(c: AppContainer, email: String, signedIn: Boolean): String {
        if (!signedIn) return "Hesap yok · dinlemeler ve listeler bu cihazda kalır"
        val e = c.engine
        return "${shortEmail(email)} · ${state(e.outbox.pending(), e.lastError, e.expired)}, ${ago(e.settings.syncLastAt, Instant.now())}"
    }

    /** The Turkish locative suffix after a number read out ("07:12'de", "10:30'da", "18:40'ta"). */
    fun locative(n: Int): String {
        val word = when {
            n == 0 -> "sıfır"
            n % 10 != 0 -> listOf("", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz")[n % 10]
            else -> listOf("", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan")[(n / 10) % 10]
        }
        val v = word.last { it in "aeıioöuü" }
        val vowel = if (v in "aıou") 'a' else 'e'
        val hard = word.last() in "çfhkpsşt"
        return (if (hard) "t" else "d") + vowel
    }

    /**
     * The settings' Daily mix line (desktop `recommendationsCard` status): "Bugün 07:12'de yapıldı", the day of the last
     * one, a run on its way, or none yet.
     */
    fun dailyMixLine(made: Instant?, madeDate: String, busy: Boolean, on: Boolean, now: Instant, zone: ZoneId): String {
        if (!on) return "Öneriler kapalı."
        if (busy) return "Dinlediklerine benzer şarkılar aranıyor."
        val today = now.atZone(zone).toLocalDate()
        val day = madeDate.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return "Henüz Daily mix yok. Önce biraz dinlemek gerekiyor."
        if (day == today && made != null) {
            val t = made.atZone(zone)
            val hm = "%02d:%02d".format(t.hour, t.minute)
            return "Bugün $hm'${locative(if (t.minute == 0) t.hour else t.minute)} yapıldı"
        }
        if (day == today) return "Bugünün Daily mix'i listelerinde."
        return "Son Daily mix ${day.format(DateTimeFormatter.ofPattern("d MMMM", TR))}'${monthSuffix(day.format(DateTimeFormatter.ofPattern("MMMM", TR)))} yapıldı"
    }

    /** A date ends in the month's name, so its suffix follows the month ("3 Kasım'da", "1 Eylül'de", "5 Mart'ta"). */
    fun monthSuffix(month: String): String {
        val v = month.last { it in "aeıioöuü" }
        val vowel = if (v in "aıou") 'a' else 'e'
        return (if (month.last() in "çfhkpsşt") "t" else "d") + vowel
    }
}
