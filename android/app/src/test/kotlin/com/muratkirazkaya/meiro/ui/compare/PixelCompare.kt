package com.muratkirazkaya.meiro.ui.compare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.io.File
import kotlin.math.abs

class PixelResult(val theme: String, val compared: Int, val mismatched: Int, val tolerance: Int, val meanAbsDiff: Double) {
    val percent get() = 100.0 * mismatched / compared
    fun line() = "pixel $theme: mismatch %.3f%% (%d of %d px, per-channel tolerance %d, mean abs diff %.3f/255)"
        .format(percent, mismatched, compared, tolerance, meanAbsDiff)
}

/**
 * Pixel comparison of the app render against the mock's reference PNG (370x824, scale 1).
 * System UI is masked in both images: the status bar rows (y < STATUS_BAR), the gesture pill rect and the
 * rounded phone-frame corners of the mock.
 * Uses android.graphics (Robolectric native graphics) because unit tests compile against android.jar.
 */
object PixelCompare {
    const val STATUS_BAR = 36
    private val PILL = Rect(129, 812, 241, 820)   // mock pill 131,814 108x4 plus 2px margin
    const val TOLERANCE = 24                      // per channel, absorbs Chrome-vs-Skia text antialiasing

    private const val CORNER = 37                 // .screenbox border-radius: the phone bezel shows in the corners

    private fun masked(x: Int, y: Int, w: Int, h: Int): Boolean {
        if (y < STATUS_BAR || (x >= PILL.left && x < PILL.right && y >= PILL.top && y < PILL.bottom)) return true
        val cx = if (x < CORNER) CORNER else if (x >= w - CORNER) w - CORNER - 1 else return false
        val cy = if (y < CORNER) CORNER else if (y >= h - CORNER) h - CORNER - 1 else return false
        val dx = x - cx + 0.0; val dy = y - cy + 0.0
        return dx * dx + dy * dy > (CORNER + 1.0) * (CORNER + 1.0)
    }

    private fun save(bmp: Bitmap, f: File) = f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    fun compare(theme: String, mockPng: File, appPng: File, outDir: File): PixelResult {
        val mock = BitmapFactory.decodeFile(mockPng.path) ?: error("cannot decode $mockPng")
        val app = BitmapFactory.decodeFile(appPng.path) ?: error("cannot decode $appPng")
        require(mock.width == app.width && mock.height == app.height) {
            "size mismatch: mock ${mock.width}x${mock.height} vs app ${app.width}x${app.height}"
        }
        val w = mock.width; val h = mock.height
        val diff = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        var n = 0; var bad = 0; var sum = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            if (masked(x, y, w, h)) { diff.setPixel(x, y, 0xFF808080.toInt()); continue }
            val a = mock.getPixel(x, y); val b = app.getPixel(x, y)
            val dr = abs(Color.red(a) - Color.red(b)); val dg = abs(Color.green(a) - Color.green(b)); val db = abs(Color.blue(a) - Color.blue(b))
            val d = maxOf(dr, dg, db)
            n++; sum += (dr + dg + db) / 3.0
            if (d > TOLERANCE) { bad++; diff.setPixel(x, y, Color.rgb(255, 0, 0)) }
            else { val v = 255 - minOf(255, d * 4); diff.setPixel(x, y, Color.rgb(v, v, v)) }
        }
        outDir.mkdirs()
        save(diff, File(outDir, "diff-$theme.png"))
        val gap = 8
        val sbs = Bitmap.createBitmap(w * 3 + gap * 2, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(sbs)
        c.drawColor(Color.rgb(0x30, 0x30, 0x30))
        c.drawBitmap(mock, 0f, 0f, null); c.drawBitmap(app, (w + gap).toFloat(), 0f, null); c.drawBitmap(diff, (2 * (w + gap)).toFloat(), 0f, null)
        save(sbs, File(outDir, "side-by-side-$theme.png"))
        return PixelResult(theme, n, bad, TOLERANCE, sum / n)
    }
}
