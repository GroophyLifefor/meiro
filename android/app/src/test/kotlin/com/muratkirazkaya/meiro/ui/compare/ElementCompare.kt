package com.muratkirazkaya.meiro.ui.compare

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.muratkirazkaya.meiro.ui.inv.InvBgKey
import com.muratkirazkaya.meiro.ui.inv.InvOverflowKey
import com.muratkirazkaya.meiro.ui.inv.InvRadiusKey
import com.muratkirazkaya.meiro.ui.inv.InvTextKey
import com.muratkirazkaya.meiro.ui.inv.InvTintKey
import kotlin.math.abs
import kotlin.math.roundToInt

class ElementSummary(
    val theme: String, val inventoryTotal: Int, val system: Int, val matched: Int,
    val missing: Int, val extra: Int, val maxBboxDelta: Double, val worstKey: String, val failures: List<String>,
    val toleranceKeys: List<String>,
) {
    fun line() = "elements $theme: $matched matched of ${inventoryTotal - system} app elements ($system system-UI excluded), " +
        "$missing missing, $extra extra, max bbox delta %.2fpx (%s), ${failures.size} mismatches; ${toleranceKeys.size} elements with a documented wider tolerance (renderer text width): $toleranceKeys".format(maxBboxDelta, worstKey)
}

/** Compares the Compose semantics tree with the mock inventory (rules 2, 3, 4, 5). */
object ElementCompare {
    private const val BBOX_TOL = 1.0
    private const val ALIGN_TOL = 0.5

    fun compare(theme: String, nodes: List<SemanticsNode>, root: Rect, inv: List<InvEl>, keys: KeyMap): ElementSummary {
        val fails = mutableListOf<String>()
        val byKey = HashMap<String, MutableList<SemanticsNode>>()
        for (n in nodes) n.config.getOrNull(SemanticsProperties.TestTag)?.let { byKey.getOrPut(it) { mutableListOf() }.add(n) }
        fun node(k: String): SemanticsNode? = byKey[k]?.singleOrNull()
        fun b(k: String): Rect? = node(k)?.let { layoutRect(it) }

        val invByPath = inv.associateBy { it.path }
        val mapped = keys.entries.mapNotNull { e -> e.key?.let { it } }.toSet()
        var matched = 0; var missing = 0; var maxDelta = 0.0; var worst = "-"
        val systemCount = keys.entries.count { it.system }

        // Inventory entries the key map does not know (rule 2 if visible).
        val knownPaths = keys.entries.map { it.path }.toSet()
        for (e in inv) if (e.path !in knownPaths && !e.offscreen && !e.occluded) {
            missing++; fails += "MISSING (no key mapped for inventory element): ${e.path}"
        }

        for (ke in keys.entries) {
            val e = invByPath[ke.path] ?: run { fails += "key map refers to unknown inventory path ${ke.path}"; continue }
            if (ke.system || e.offscreen || e.occluded) continue
            val key = ke.key!!
            val list = byKey[key]
            if (list == null) { missing++; fails += "MISSING $key: no node tagged for ${e.kind} ${e.icon ?: e.text ?: ""} at ${e.x},${e.y} ${e.w}x${e.h}"; continue }
            if (list.size > 1) { fails += "DUPLICATE $key: ${list.size} nodes carry this tag"; continue }
            val n = list[0]; val r = layoutRect(n)
            val vis = n.boundsInRoot
            if (abs(vis.left - r.left) > 0.5 || abs(vis.top - r.top) > 0.5 || abs(vis.right - r.right) > 0.5 || abs(vis.bottom - r.bottom) > 0.5)
                fails += "CLIPPED $key: visible bounds $vis differ from layout bounds $r (an ancestor clips it)"
            var ok = true
            val tol = keys.tolerance[key]
            fun dif(what: String, app: Double, mock: Double): Double {
                val d = abs(app - mock)
                val limit = tol?.get(what) ?: BBOX_TOL
                if (tol?.containsKey(what) != true) { if (d > maxDelta) { maxDelta = d; worst = "$key $what" } }
                if (d > limit) { fails += "BBOX $key $what: app %.2f vs mock %.2f (delta %.2f > %.1f)".format(app, mock, d, limit); ok = false }
                return d
            }
            dif("x", r.left.toDouble(), e.x); dif("y", r.top.toDouble(), e.y)
            dif("w", r.width.toDouble(), e.w); dif("h", r.height.toDouble(), e.h)

            // text style: from the node itself or from its label child
            val styleNode = ke.styleFrom?.let { node(it) } ?: n
            val t = styleNode.config.getOrNull(InvTextKey)
            val isText = e.kind == "text" || ke.styleFrom != null
            if (isText) {
                if (t == null) { fails += "STYLE $key: no text style exported"; ok = false }
                else {
                    if (t.style.sizeSp != e.fontSize) { fails += "FONT $key size: app ${t.style.sizeSp} vs mock ${e.fontSize}"; ok = false }
                    if (t.style.weight != e.fontWeight) { fails += "FONT $key weight: app ${t.style.weight} vs mock ${e.fontWeight}"; ok = false }
                    if (t.style.lineHeightSp != e.lineHeight) { fails += "FONT $key line-height: app ${t.style.lineHeightSp} vs mock ${e.lineHeight}"; ok = false }
                    if (abs(t.style.letterSpacingSp - e.letterSpacing) > 0.001) { fails += "FONT $key letter-spacing: app ${t.style.letterSpacingSp} vs mock ${e.letterSpacing}"; ok = false }
                    colorCheck("$key text colour", t.color, e.color)?.let { fails += it; ok = false }
                }
            }
            // icon tint (mono glyphs only; logos are multi-colour)
            if (e.kind == "icon" && e.icon?.startsWith("logo:") != true) {
                val tint = n.config.getOrNull(InvTintKey)
                if (tint == null) { fails += "ICON $key: no tint exported"; ok = false }
                else colorCheck("$key icon colour", tint, e.fill)?.let { fails += it; ok = false }
            }
            // background colour
            val bg = n.config.getOrNull(InvBgKey)
            if (e.bg.a > 0.0) {
                if (bg == null) { fails += "BG $key: mock has ${e.bg} but app exports no background"; ok = false }
                else colorCheck("$key background", bg, e.bg)?.let { fails += it; ok = false }
            } else if (bg != null) { fails += "BG $key: mock is transparent but app draws $bg"; ok = false }
            // radius
            val rad = n.config.getOrNull(InvRadiusKey)
            val mockRad = e.radiusDp
            if (mockRad > 0f && (rad == null || abs(rad - mockRad) > 0.01)) { fails += "RADIUS $key: app ${rad ?: "none"} vs mock $mockRad"; ok = false }
            if (mockRad == 0f && rad != null && rad != 0f) { fails += "RADIUS $key: app $rad vs mock 0"; ok = false }
            if (ok) matched++
        }

        // Rule 3: every tagged node is an inventory element or a declared aux label.
        var extra = 0
        for ((k, l) in byKey) if (k !in mapped && k !in keys.aux) { extra++; fails += "EXTRA $k: tagged node with no inventory element (bounds ${layoutRect(l[0])})" }

        // Rule 4: overflow / clipping.
        for ((k, l) in byKey) for (n in l) {
            if (n.config.getOrNull(InvOverflowKey) == true) fails += "OVERFLOW $k: text has visual overflow (clipped or ellipsized) in ${layoutRect(n)}"
            val r = layoutRect(n)
            if (r.left < root.left - 0.01 || r.top < root.top - 0.01 || r.right > root.right + 0.01 || r.bottom > root.bottom + 0.01)
                fails += "CLIPPED $k: bounds $r outside screen $root"
        }

        // Rule 5: centring and row alignment, measured on the Compose tree.
        for (c in keys.centered) {
            val cb = b(c.child); val pb = if (c.parent == "root") root else b(c.parent)
            if (cb == null || pb == null) { fails += "CENTER ${c.child} in ${c.parent}: node missing"; continue }
            if ('x' in c.axes) { val d = abs(cb.center.x - pb.center.x); if (d > ALIGN_TOL) fails += "CENTER ${c.child} in ${c.parent} x: centre ${cb.center.x} vs ${pb.center.x} (delta %.2f > %.1f)".format(d, ALIGN_TOL) }
            if ('y' in c.axes) { val d = abs(cb.center.y - pb.center.y); if (d > ALIGN_TOL) fails += "CENTER ${c.child} in ${c.parent} y: centre ${cb.center.y} vs ${pb.center.y} (delta %.2f > %.1f)".format(d, ALIGN_TOL) }
        }
        for (row in keys.rows) for (edge in row.edges) {
            val vals = row.keys.map { k -> b(k)?.let { r -> when (edge) { "top" -> r.top; "bottom" -> r.bottom; else -> r.center.y } } }
            if (vals.any { it == null }) { fails += "ROW ${row.name}: node missing for $edge"; continue }
            val spread = vals.maxOf { it!! } - vals.minOf { it!! }
            if (spread > ALIGN_TOL) fails += "ROW ${row.name} $edge: spread %.2f > %.1f over ${row.keys.zip(vals).joinToString { "${it.first}=${it.second}" }}".format(spread, ALIGN_TOL)
        }
        return ElementSummary(theme, inv.size, systemCount, matched, missing, extra, maxDelta, worst, fails, keys.tolerance.keys.toList())
    }

    /** Layout bounds (not clipped by ancestors, unlike boundsInRoot). */
    private fun layoutRect(n: SemanticsNode) = Rect(n.positionInRoot, androidx.compose.ui.geometry.Size(n.size.width.toFloat(), n.size.height.toFloat()))

    private fun colorCheck(what: String, app: Color, mock: CssColor): String? {
        val a = (app.alpha * 255).roundToInt(); val r = (app.red * 255).roundToInt(); val g = (app.green * 255).roundToInt(); val b = (app.blue * 255).roundToInt()
        val ma = (mock.a * 255).roundToInt()
        return if (abs(r - mock.r) > 1 || abs(g - mock.g) > 1 || abs(b - mock.b) > 1 || abs(a - ma) > 1)
            "COLOR $what: app rgba($r,$g,$b,$a) vs mock rgba(${mock.r},${mock.g},${mock.b},$ma)" else null
    }
}
