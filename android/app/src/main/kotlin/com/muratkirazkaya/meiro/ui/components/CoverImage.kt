package com.muratkirazkaya.meiro.ui.components

import android.graphics.Bitmap
import androidx.compose.runtime.staticCompositionLocalOf
import coil.ImageLoader
import coil.size.Size
import coil.transform.Transformation

/** Tests supply their own loader (a fake fetcher) here; null means the app's default Coil loader. */
val LocalCoverImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** True for a YouTube video thumbnail (`i.ytimg.com/vi/<id>/hqdefault.jpg`, ...): 4:3 with black bars, unlike YTM's square covers. */
fun isVideoThumbnail(url: String): Boolean {
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase()
    return (host == "ytimg.com" || host.endsWith(".ytimg.com")) && url.substringAfter(host).startsWith("/vi")
}

/**
 * Cuts the black letterbox bars off a picture: the rows at the top and bottom whose pixels are all near black, at most
 * [MAX_BAND] of the height from each side (a dark frame is not a bar). YouTube's `hqdefault.jpg` is the 16:9 frame
 * padded to 4:3 with such bars; the square crop that follows then shows the frame only, like the mock's covers.
 * Works on whatever resolution Coil decoded, so it needs no second URL (hq720 and maxresdefault 404 on older videos).
 */
class LetterboxTrim : Transformation {
    override val cacheKey: String = "letterboxTrim-v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val (top, bottom) = darkBands(input)
        if (top == 0 && bottom == 0) return input
        val h = input.height - top - bottom
        if (h <= 0) return input
        return Bitmap.createBitmap(input, 0, top, input.width, h)
    }

    companion object {
        const val MAX_BAND = 0.2f
        /** A row counts as bar if every sampled pixel is below this (0..255 per channel). */
        const val DARK = 24

        /** How many rows to cut from the top and from the bottom (the first row of picture is blended with the bar, so one extra goes). */
        fun darkBands(b: Bitmap): Pair<Int, Int> {
            val w = b.width; val h = b.height
            if (w < 4 || h < 8) return 0 to 0
            val limit = (h * MAX_BAND).toInt()
            val row = IntArray(w)
            fun dark(y: Int): Boolean {
                b.getPixels(row, 0, w, 0, y, w, 1)
                for (p in row) {
                    if (((p shr 16) and 0xFF) >= DARK || ((p shr 8) and 0xFF) >= DARK || (p and 0xFF) >= DARK) return false
                }
                return true
            }
            var top = 0
            while (top < limit && dark(top)) top++
            var bottom = 0
            while (bottom < limit && dark(h - 1 - bottom)) bottom++
            if (top > 0) top = minOf(top + 1, limit)
            if (bottom > 0) bottom = minOf(bottom + 1, limit)
            return top to bottom
        }
    }
}
