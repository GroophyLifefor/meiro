package com.muratkirazkaya.meiro.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult

/**
 * The full player's background from the cover's colours (the mock's `cov(h, 70)`: a 135° gradient from a lighter to a
 * darker colour). The picture is read small; the mean colour of its top-left and of its bottom-right quarter give the two
 * hues, set to the mock's lightness (52% and 26%) so the white text keeps its contrast. Null while it loads or without a
 * picture (the caller then draws the hue gradient).
 */
@Composable
fun rememberCoverBrush(url: String?): Brush? {
    val ctx = LocalContext.current
    val loader = LocalCoverImageLoader.current ?: ctx.imageLoader
    val brush by produceState<Brush?>(null, url) {
        value = null
        if (url.isNullOrBlank()) return@produceState
        val req = ImageRequest.Builder(ctx).data(url).size(24, 24).allowHardware(false)
            .apply { if (isVideoThumbnail(url)) transformations(LetterboxTrim()) }.build()
        val bmp = ((loader.execute(req) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@produceState
        value = coverColours(bmp)?.let { (a, b) -> Brush.linearGradient(listOf(a, b)) }
    }
    return brush
}

/** The two colours of [coverColours]: the top-left quarter at 52% lightness, the bottom-right one at 26%. */
fun coverColours(b: Bitmap): Pair<Color, Color>? {
    if (b.width < 2 || b.height < 2) return null
    fun mean(x0: Int, y0: Int, x1: Int, y1: Int): FloatArray {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = b.getPixel(x, y)
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
        }
        val hsl = FloatArray(3)
        androidx.core.graphics.ColorUtils.RGBToHSL((r / n).toInt(), (g / n).toInt(), (bl / n).toInt(), hsl)
        return hsl
    }
    val w = b.width; val h = b.height
    val a = mean(0, 0, w / 2, h / 2)
    val z = mean(w / 2, h / 2, w, h)
    // A grey cover keeps a little colour, as the mock's covers all have (55% and 60% saturation there).
    fun colour(hsl: FloatArray, light: Float, sat: Float) = Color.hsl(hsl[0], maxOf(hsl[1], 0.15f).coerceAtMost(sat), light)
    return colour(a, 0.52f, 0.55f) to colour(z, 0.26f, 0.60f)
}
