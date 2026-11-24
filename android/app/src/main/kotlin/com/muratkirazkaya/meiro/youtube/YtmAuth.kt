package com.muratkirazkaya.meiro.youtube

import okhttp3.HttpUrl
import java.security.MessageDigest

/**
 * The browser session's cookies that sign InnerTube requests in (`youtube/cookie.go` `CookieAuth`). Treat [cookie] as a
 * password: [toString] never shows it, and nothing here logs it.
 */
class YtmCookieAuth private constructor(
    val cookie: String,
    private val sapisid: String,
    val accountIndex: Int,
    /** The brand channel the session acts as (`X-Goog-PageId`, `context.user.onBehalfOfUser`), "" for the account's own. */
    val onBehalfOfUser: String,
) {
    /** `authorization`: `SAPISIDHASH <ts>_<sha1hex(ts + " " + SAPISID + " " + origin)>`, fresh for each request. */
    fun authorization(epochSeconds: Long, origin: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$epochSeconds $sapisid $origin".toByteArray())
        return "SAPISIDHASH ${epochSeconds}_" + digest.joinToString("") { "%02x".format(it) }
    }

    override fun toString(): String = REDACTED

    companion object {
        const val REDACTED = "YtmCookieAuth(<redacted>)"

        /** `NewCookieAuth`: a `Cookie:` header value that holds SAPISID; anything else throws without echoing it. */
        fun create(cookie: String, accountIndex: Int = 0, onBehalfOfUser: String = ""): YtmCookieAuth {
            val c = cookie.trim()
            require(c.isNotEmpty() && c.none { it == '\r' || it == '\n' }) { "youtube: cookie header is empty or invalid" }
            val sapisid = cookieValue(c, "SAPISID")
            require(sapisid.isNotEmpty()) { "youtube: cookie header must contain SAPISID" }
            return YtmCookieAuth(c, sapisid, accountIndex, onBehalfOfUser)
        }

        /** `cookieValue`: the value of [name] in a `Cookie:` header, quotes trimmed, "" when it is not there. */
        fun cookieValue(cookie: String, name: String): String {
            for (part in cookie.split(';')) {
                val p = part.trim()
                val i = p.indexOf('=')
                if (i > 0 && p.substring(0, i) == name) return p.substring(i + 1).trim().trim('"')
            }
            return ""
        }

        /** `isYouTubeURL`: the only URLs the cookie may go to: https, no user info, youtube.com or a subdomain. */
        fun isYouTubeUrl(url: HttpUrl): Boolean {
            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
            val host = url.host.lowercase()
            return host == "youtube.com" || host.endsWith(".youtube.com")
        }
    }
}

/** YouTube refused the session's credentials with HTTP 401 or 403 (`ErrUnauthorized`). */
class YtmUnauthorized(message: String) : YtmException(message)

/** `youtube.AccountDetails`: the account the session signs in as. */
data class YtmAccountDetails(val name: String = "", val email: String = "", val channelId: String = "", val thumbnail: String = "")

/** `youtube.AccountChannel`: one channel of the account switcher. */
data class YtmAccountChannel(
    val name: String = "", val byline: String = "", val handle: String = "", val channelId: String = "", val thumbnail: String = "",
    val selected: Boolean = false, val disabled: Boolean = false, val hasChannel: Boolean = false,
)

/** Reading `account/account_menu` (`youtube/accounts.go`); pure, so synthetic fixtures test it on the JVM. */
object YtmAccountParse {
    /** `GetAccountDetails`: the header's name, email and photo; the channel id of the account list's selected item. */
    fun details(text: String): YtmAccountDetails {
        val root = OrderedJson.parse(text)
        var d = YtmAccountDetails()
        Renderers.findRenderer(root, "activeAccountHeaderRenderer")?.let { h ->
            d = d.copy(
                name = Renderers.rendererText(h["accountName"]), email = Renderers.rendererText(h["email"]),
                thumbnail = Renderers.rendererThumbnail(h["accountPhoto"]),
            )
        }
        channels(root).firstOrNull { it.selected }?.let { ch ->
            d = d.copy(channelId = ch.channelId, thumbnail = d.thumbnail.ifEmpty { ch.thumbnail })
        }
        return d
    }

    /** `extractAccountChannels`: every account item, in page order, once each. */
    fun channels(root: Any?): List<YtmAccountChannel> {
        val out = mutableListOf<YtmAccountChannel>()
        val seen = HashSet<String>()
        fun add(ch: YtmAccountChannel) {
            val id = ch.channelId + ch.name + ch.handle
            if (id.isEmpty() || !seen.add(id)) return
            out += ch
        }
        fun walk(v: Any?) {
            when (v) {
                is List<*> -> v.forEach { walk(it) }
                is JObj -> {
                    if (hasChannelFields(v)) { add(channel(v)); return }
                    for ((k, m) in v.members) {
                        if (m !is JObj) { walk(m); continue }
                        if (k == "accountItemRenderer" || k == "accountItem" || hasChannelFields(m)) { add(channel(m)); continue }
                        walk(m)
                    }
                }
            }
        }
        walk(root)
        return out
    }

    private fun channel(r: JObj): YtmAccountChannel {
        var ch = YtmAccountChannel(
            name = Renderers.rendererText(r["accountName"]), byline = Renderers.rendererText(r["accountByline"]),
            handle = Renderers.rendererText(r["channelHandle"]), thumbnail = Renderers.rendererThumbnail(r["accountPhoto"]),
            selected = r["isSelected"] == true, disabled = r["isDisabled"] == true, hasChannel = r["hasChannel"] == true,
            channelId = Renderers.navigationId(r["endpoint"]),
        )
        if (ch.name.isEmpty()) ch = ch.copy(name = Renderers.rendererText(r["account_name"]))
        if (ch.name.isEmpty()) ch = ch.copy(name = Renderers.rendererText(r["name"]))
        if (ch.channelId.isEmpty()) ch = ch.copy(channelId = r.str("channelId").orEmpty())
        if (ch.channelId.isEmpty()) ch = ch.copy(channelId = pageId(r["serviceEndpoint"]))
        if (ch.handle.isEmpty()) ch = ch.copy(handle = r.str("handle").orEmpty())
        if (ch.handle.isEmpty()) ch = ch.copy(handle = r.str("channel_handle").orEmpty())
        if (ch.byline.isEmpty()) ch = ch.copy(byline = Renderers.rendererText(r["account_byline"]))
        if (ch.thumbnail.isEmpty()) ch = ch.copy(thumbnail = Renderers.rendererThumbnail(r["account_photo"]))
        return ch
    }

    /** `accountChannelPageID`: a brand channel's id from the switcher's identity token; the primary account has none. */
    private fun pageId(endpoint: Any?): String {
        val identity = (endpoint as? JObj)?.obj("selectActiveIdentityEndpoint") ?: return ""
        for (t in identity.arr("supportedTokens").orEmpty()) {
            val id = (t as? JObj)?.obj("pageIdToken")?.str("pageId").orEmpty()
            if (id.isNotEmpty()) return id
        }
        return ""
    }

    private fun hasChannelFields(o: JObj) = listOf("channelId", "accountName", "account_name", "channelHandle", "channel_handle").any { o[it] != null }
}
