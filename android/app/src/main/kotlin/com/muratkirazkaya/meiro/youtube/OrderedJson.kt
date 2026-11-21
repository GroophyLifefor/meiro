package com.muratkirazkaya.meiro.youtube

/**
 * Port of `youtube/orderedjson.go`. YouTube's responses are order-sensitive: of several competing keys, IDs or
 * thumbnails, the first one a walk finds is the one the app uses, so an object keeps its members in document order
 * ([JObj]). A repeated member name replaces the earlier member's value in place, as the Go decoder does. Arrays are
 * [List], numbers [Double], and `null`, `true`, `false` and strings their Kotlin values.
 */
class JObj(val members: List<Pair<String, Any?>>) {
    /** The value of the first member named [key], or null. */
    operator fun get(key: String): Any? {
        for ((k, v) in members) if (k == key) return v
        return null
    }

    fun has(key: String): Boolean = members.any { it.first == key }

    fun obj(key: String): JObj? = get(key) as? JObj
    fun str(key: String): String? = get(key) as? String
    fun arr(key: String): List<Any?>? = @Suppress("UNCHECKED_CAST") (get(key) as? List<Any?>)
}

object OrderedJson {
    /** Parses [text]; anything that is not JSON gives null, which the extractors read as empty (`decodeResponse`). */
    fun parse(text: String): Any? = try {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) null else v
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() { while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++ }

        fun value(): Any? {
            skipWs()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw IllegalArgumentException("unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        private fun number(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start")
        }

        private fun string(): String {
            i++ // opening quote
            val start = i
            // Fast path: no escapes.
            while (i < s.length && s[i] != '"' && s[i] != '\\') i++
            if (i < s.length && s[i] == '"') { val r = s.substring(start, i); i++; return r }
            val b = StringBuilder().append(s, start, i)
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            '"' -> b.append('"'); '\\' -> b.append('\\'); '/' -> b.append('/')
                            'b' -> b.append('\b'); 'f' -> b.append('\u000C'); 'n' -> b.append('\n')
                            'r' -> b.append('\r'); 't' -> b.append('\t')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun array(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            skipWs()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("expected , or ] at ${i - 1}")
                }
            }
        }

        private fun obj(): JObj {
            i++
            val members = ArrayList<Pair<String, Any?>>()
            skipWs()
            if (s[i] == '}') { i++; return JObj(members) }
            while (true) {
                skipWs()
                require(s[i] == '"') { "expected a member name at $i" }
                val key = string()
                skipWs()
                require(s[i++] == ':') { "expected : at ${i - 1}" }
                val v = value()
                // A repeated name replaces the earlier member, as decoding into a map would keep the last one.
                val at = members.indexOfFirst { it.first == key }
                if (at >= 0) members[at] = key to v else members.add(key to v)
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return JObj(members)
                    else -> throw IllegalArgumentException("expected , or } at ${i - 1}")
                }
            }
        }
    }
}
