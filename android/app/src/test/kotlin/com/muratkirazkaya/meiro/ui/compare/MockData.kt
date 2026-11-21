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
            return CssColor(p[0].toInt(), p[1].toInt(), p[2].toInt(), p.getOrElse(3) { 1.0 })
        }
    }
}

class InvEl(val json: JSONObject, val theme: String) {
    val path: String = json.getString("path")
    val kind: String = json.getString("kind")
    val icon: String? = json.optString("icon").takeIf { it.isNotEmpty() && it != "null" }
    val text: String? = json.optString("text").takeIf { it.isNotEmpty() && it != "null" }
    val x = json.getJSONObject("bbox").getDouble("x")
    val y = json.getJSONObject("bbox").getDouble("y")
    val w = json.getJSONObject("bbox").getDouble("w")
    val h = json.getJSONObject("bbox").getDouble("h")
    val offscreen = json.optBoolean("offscreen")
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

class KeyEntry(val path: String, val key: String?, val system: Boolean, val styleFrom: String?)
class Centered(val child: String, val parent: String, val axes: String)
class Row(val name: String, val keys: List<String>, val edges: List<String>)

class KeyMap(
    val screen: String, val entries: List<KeyEntry>, val aux: Set<String>, val centered: List<Centered>, val rows: List<Row>,
    val tolerance: Map<String, Map<String, Double>>,
) {
    companion object {
        fun load(name: String): KeyMap {
            val txt = KeyMap::class.java.getResourceAsStream("/mock-keys/$name.json")!!.readBytes().decodeToString()
            val j = JSONObject(txt)
            fun arr(a: JSONArray) = (0 until a.length()).map { a.get(it) }
            return KeyMap(
                name,
                arr(j.getJSONArray("elements")).map { o -> o as JSONObject
                    KeyEntry(o.getString("path"), o.optString("key").ifEmpty { null }, o.optBoolean("system"), o.optString("styleFrom").ifEmpty { null }) },
                arr(j.getJSONArray("aux")).map { it as String }.toSet(),
                arr(j.getJSONArray("centered")).map { o -> o as JSONObject; Centered(o.getString("child"), o.getString("parent"), o.getString("axes")) },
                arr(j.getJSONArray("rows")).map { o -> o as JSONObject
                    Row(o.getString("name"), arr(o.getJSONArray("keys")).map { it as String }, arr(o.getJSONArray("edges")).map { it as String }) },
                j.optJSONObject("tolerance")?.let { t -> t.keys().asSequence().associateWith { k -> t.getJSONObject(k).let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } } } } ?: emptyMap(),
            )
        }
    }
}

fun loadInventory(screen: String, theme: String): List<InvEl> {
    val j = JSONObject(File(Dirs.mock, "inventory/$screen.json").readText())
    val a = j.getJSONArray("elements")
    return (0 until a.length()).map { InvEl(a.getJSONObject(it), theme) }
}
