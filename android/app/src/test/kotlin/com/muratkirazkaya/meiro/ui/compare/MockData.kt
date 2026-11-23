package com.muratkirazkaya.meiro.ui.compare

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where the mock's inventory and reference images live, and where results go (set by build.gradle.kts). */
object Dirs {
    val mock = File(System.getProperty("meiro.mockDir") ?: error("meiro.mockDir not set"))
    val out = File(System.getProperty("meiro.outDir") ?: error("meiro.outDir not set")).also { it.mkdirs() }
}

class CssColor(val r: Int, val g: Int, val b: Int, val a: Double) {
    override fun toString() = "rgba($r,$g,$b,${"%.2f".format(a)})"
    companion object {
        fun parse(s: String): CssColor {
            val p = Regex("[0-9.]+").findAll(s).map { it.value.toDouble() }.toList()
            // Chrome writes a `color-mix()` result as "color(srgb r g b / a)" with channels from 0 to 1.
            if (s.trimStart().startsWith("color(srgb")) {
                fun ch(v: Double) = Math.round(v * 255).toInt()
                return CssColor(ch(p[0]), ch(p[1]), ch(p[2]), p.getOrElse(3) { 1.0 })
            }
            return CssColor(p[0].toInt(), p[1].toInt(), p[2].toInt(), p.getOrElse(3) { 1.0 })
        }
    }
}

/**
 * One element of the mock inventory. [shiftY] is how far the screen's scrolling content was scrolled when the app is
 * compared: elements of the scrolling content (not the sticky top bar, mini player or bottom bar) are then expected
 * [shiftY] higher, and count as off screen unless they lie wholly inside the content viewport (y 100..672).
 */
class InvEl(val json: JSONObject, val theme: String, shiftY: Double = 0.0) {
    val path: String = json.getString("path")
    val kind: String = json.getString("kind")
    val icon: String? = json.optString("icon").takeIf { it.isNotEmpty() && it != "null" }
    val text: String? = json.optString("text").takeIf { it.isNotEmpty() && it != "null" }
    private val scrolls = shiftY != 0.0 && path.contains("div#content.content:nth-child(2)>") && !path.contains("div.topbar")
    // A pseudo element the mock does not draw (a `::before` of a page under the full player) has no box at all.
    private val box = json.optJSONObject("bbox")
    val x = box?.getDouble("x") ?: 0.0
    val y = (box?.getDouble("y") ?: 0.0) - (if (scrolls) shiftY else 0.0)
    val w = box?.getDouble("w") ?: 0.0
    val h = box?.getDouble("h") ?: 0.0
    // Scrolled vertically, an element of a horizontal shelf that lies right of the screen stays off screen.
    val offscreen = box == null || if (scrolls) (y < 100.0 || y + h > 672.0 || x < 0.0 || x + w > 370.0) else json.optBoolean("offscreen")
    val occluded = json.optBoolean("occluded", false)
    val clipped = json.optBoolean("clipped")
    private val st = json.getJSONObject("style")
    val fontSize = st.getString("fontSize").removeSuffix("px").toFloat()
    val fontWeight = st.getString("fontWeight").toInt()
    val lineHeight = st.getString("lineHeight").removeSuffix("px").toFloat()
    val letterSpacing = st.getString("letterSpacing").let { if (it == "normal") 0f else it.removeSuffix("px").toFloat() }
    val radiusRaw = st.getString("borderRadius")
    val color = CssColor.parse(st.getJSONObject("color").getString(theme))
    val bg = CssColor.parse(st.getJSONObject("backgroundColor").getString(theme))
    val fill = CssColor.parse(st.getJSONObject("fill").getString(theme))
    val radiusDp: Float get() = if (radiusRaw.endsWith("%")) (minOf(w, h) / 2).toFloat() else radiusRaw.split(" ")[0].removeSuffix("px").toFloat()
}

/** [spacer]: an empty block of the mock (a `div` with a height); it has no counterpart to tag, the next element's position proves it. */
class KeyEntry(val path: String, val key: String?, val system: Boolean, val styleFrom: String?, val spacer: Boolean = false)
class Centered(val child: String, val parent: String, val axes: String)
class Row(val name: String, val keys: List<String>, val edges: List<String>)

class KeyMap(
    val screen: String, val entries: List<KeyEntry>, val aux: Set<String>, val centered: List<Centered>, val rows: List<Row>,
    val noOverlap: List<Pair<String, String>>,
    /** Keys whose text the mock itself ends in an ellipsis (its inventory lists the element under `clippedText`). */
    val ellipsis: Set<String>,
    val tolerance: Map<String, Map<String, Double>>,
    /**
     * Key prefixes of elements in a horizontally scrolling row (the mock's `overflow-x:auto` chips and shelf): such an
     * element may lie partly outside the screen, so the outside-the-screen rule skips them. Every other rule applies.
     */
    val hscroll: List<String> = emptyList(),
) {
    companion object {
        fun load(name: String): KeyMap {
            val txt = KeyMap::class.java.getResourceAsStream("/mock-keys/$name.json")!!.readBytes().decodeToString()
            val j = JSONObject(txt)
            fun arr(a: JSONArray) = (0 until a.length()).map { a.get(it) }
            return KeyMap(
                name,
                arr(j.getJSONArray("elements")).map { o -> o as JSONObject
                    KeyEntry(o.getString("path"), o.optString("key").ifEmpty { null }, o.optBoolean("system"), o.optString("styleFrom").ifEmpty { null }, o.optBoolean("spacer")) },
                arr(j.getJSONArray("aux")).map { it as String }.toSet(),
                arr(j.getJSONArray("centered")).map { o -> o as JSONObject; Centered(o.getString("child"), o.getString("parent"), o.getString("axes")) },
                arr(j.getJSONArray("rows")).map { o -> o as JSONObject
                    Row(o.getString("name"), arr(o.getJSONArray("keys")).map { it as String }, arr(o.getJSONArray("edges")).map { it as String }) },
                j.optJSONArray("noOverlap")?.let { a -> arr(a).map { p -> p as JSONArray; p.getString(0) to p.getString(1) } } ?: emptyList(),
                j.optJSONArray("ellipsis")?.let { a -> arr(a).map { it as String }.toSet() } ?: emptySet(),
                j.optJSONObject("tolerance")?.let { t -> t.keys().asSequence().associateWith { k -> t.getJSONObject(k).let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } } } } ?: emptyMap(),
                j.optJSONArray("hscroll")?.let { a -> arr(a).map { it as String } } ?: emptyList(),
            )
        }
    }
}

fun loadInventory(screen: String, theme: String, shiftY: Double = 0.0): List<InvEl> {
    val j = JSONObject(File(Dirs.mock, "inventory/$screen.json").readText())
    val a = j.getJSONArray("elements")
    return (0 until a.length()).map { InvEl(a.getJSONObject(it), theme, shiftY) }
}
