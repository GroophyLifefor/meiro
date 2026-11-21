package com.muratkirazkaya.meiro.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.muratkirazkaya.meiro.playback.PlayerUi
import com.muratkirazkaya.meiro.playback.QItem
import com.muratkirazkaya.meiro.ui.components.Cover
import com.muratkirazkaya.meiro.ui.components.CoverSpec
import com.muratkirazkaya.meiro.ui.components.LetterboxTrim
import com.muratkirazkaya.meiro.ui.components.LocalCoverImageLoader
import com.muratkirazkaya.meiro.ui.components.MiniPlayer
import com.muratkirazkaya.meiro.ui.components.Provider
import com.muratkirazkaya.meiro.ui.components.isVideoThumbnail
import com.muratkirazkaya.meiro.ui.components.toMini
import com.muratkirazkaya.meiro.ui.inv.InvDimKey
import com.muratkirazkaya.meiro.ui.theme.MeiroTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real-data rules the mock cannot show (it has no pictures): what a cover really displays when the picture is a YouTube
 * video thumbnail (4:3 with black bars), and what the mini player shows for a track. The picture comes from a fake Coil
 * fetcher that serves a bitmap shaped like `hqdefault.jpg`, so the whole path (request, transformation, Crop) is real.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w370dp-h824dp-mdpi")
class CoverRealDataTest {
    @get:Rule val rule = createComposeRule()

    private val PICTURE = AColor.rgb(40, 120, 200)
    private val YT = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
    private val YTM_SQUARE = "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj"

    /** 480x360 like hqdefault.jpg: a 16:9 picture (a vertical gradient, never near black) between 45px black bars. */
    private fun letterboxed(): Bitmap {
        val b = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(AColor.BLACK)
        val p = Paint()
        for (y in 45 until 315) { p.color = AColor.rgb(40, 120 + (y - 45) / 6, 200); c.drawRect(0f, y.toFloat(), 480f, y + 1f, p) }
        return b
    }

    private fun loader(): ImageLoader {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ImageLoader.Builder(ctx).memoryCache(null).diskCache(null).components {
            add(Fetcher.Factory<Uri> { _, options: Options, _ ->
                Fetcher { DrawableResult(BitmapDrawable(options.context.resources, letterboxed()), false, DataSource.MEMORY) }
            })
        }.build()
    }

    private fun Bitmap.dark(y: Int): Boolean {
        for (x in width / 5 until width * 4 / 5) { val p = getPixel(x, y); if (AColor.red(p) >= 24 || AColor.green(p) >= 24 || AColor.blue(p) >= 24) return false }
        return true
    }

    /** The rule: no row at the top or bottom edge of what a cover draws is uniformly near black. */
    private fun assertNoDarkBand(b: Bitmap, what: String) {
        for (y in listOf(0, 1, 2, b.height - 3, b.height - 2, b.height - 1)) {
            assertFalse("$what: row $y of ${b.height} is a uniform dark band (letterbox bar shows)", b.dark(y))
        }
    }

    private fun renderCover(url: String, sizeDp: Int, radius: Int): Bitmap {
        rule.setContent {
            CompositionLocalProvider(LocalCoverImageLoader provides loader()) {
                MeiroTheme(darkTheme = false) { Cover(CoverSpec(url, 10), radius.dp, "cv", Modifier.size(sizeDp.dp)) }
            }
        }
        return waitForPicture { shotOf("cv") }
    }

    private val out = File(System.getProperty("roborazzi.output.dir") ?: "build/check/roborazzi").absoluteFile.parentFile.resolve("cover-real").also { it.mkdirs() }
    private var n = 0

    /** What the node drew, as a bitmap (Roborazzi's capture; Compose's own captureToImage cannot run on the JVM). */
    private fun shotOf(tag: String): Bitmap {
        val f = File(out, "${javaClass.simpleName}-${n++}-$tag.png")
        rule.onNodeWithTag(tag).captureRoboImage(f.path)
        return android.graphics.BitmapFactory.decodeFile(f.path)
    }

    /** Coil loads on its own threads: poll until the picture (not the gradient placeholder under it) is drawn. */
    private fun waitForPicture(shot: () -> Bitmap): Bitmap {
        val end = System.currentTimeMillis() + 8000
        var b = shot()
        while (System.currentTimeMillis() < end) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeBy(50)
            b = shot()
            val p = b.getPixel(b.width / 2, b.height / 2)
            if (AColor.blue(p) > 150 && AColor.red(p) < 80) return b
            Thread.sleep(50)
        }
        return b
    }

    @Test fun heroCover_ytThumbnail_hasNoDarkBand() = assertNoDarkBand(renderCover(YT, 220, 20), "hero cover 220dp")
    @Test fun rowCover_ytThumbnail_hasNoDarkBand() = assertNoDarkBand(renderCover(YT, 48, 8), "row cover 48dp")

    @Test fun ytmSquareCover_isLeftAlone() {
        // The same bitmap behind a YTM URL: not a video thumbnail, so nothing is trimmed (the dark rows stay in the crop).
        val b = renderCover(YTM_SQUARE, 220, 20)
        assertTrue("a YTM square cover must not be trimmed", b.dark(1))
    }

    @Test fun onlyVideoThumbnailsAreTrimmed() {
        assertTrue(isVideoThumbnail(YT)); assertTrue(isVideoThumbnail("https://i.ytimg.com/vi_webp/x/hq720.webp"))
        assertFalse(isVideoThumbnail(YTM_SQUARE)); assertFalse(isVideoThumbnail("https://i.scdn.co/image/x"))
        assertFalse(isVideoThumbnail("https://example.com/i.ytimg.com/vi/x.jpg"))
    }

    @Test fun trim_cutsBarsOnly_andNeverAWholeDarkFrame() {
        val (top, bottom) = LetterboxTrim.darkBands(letterboxed())
        assertTrue("bars of 45px are cut (+1 blend row): $top", top in 45..47); assertTrue(bottom in 45..47)
        val black = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(AColor.BLACK) }
        val (t2, b2) = LetterboxTrim.darkBands(black)
        assertTrue("a black frame is not a bar: at most 20% from each side", t2 <= 20 && b2 <= 20)
        val trimmed = runBlocking { LetterboxTrim().transform(letterboxed(), coil.size.Size.ORIGINAL) }
        assertEquals(480, trimmed.width); assertTrue(trimmed.height in 262..270)
        assertEquals("a picture with no bars is returned as is", 100, runBlocking { LetterboxTrim().transform(letterboxedPlain(), coil.size.Size.ORIGINAL) }.height)
    }

    private fun letterboxedPlain() = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(PICTURE) }

    // ---- mini player ----

    private fun mini(item: QItem) = PlayerUi(item, true, 1000, 100000, false).toMini()

    private fun renderMini(item: QItem) {
        rule.setContent {
            CompositionLocalProvider(LocalCoverImageLoader provides loader()) {
                MeiroTheme(darkTheme = false) { Box(Modifier.size(370.dp, 80.dp)) { MiniPlayer(mini(item)) } }
            }
        }
        rule.waitForIdle()
    }

    /** What the mini player's source icons show: provider -> dim. */
    private fun icons(): Map<String, Boolean> = listOf("ytm", "sc").mapNotNull { k ->
        rule.onAllNodesWithTag("mini.src.$k").fetchSemanticsNodes().firstOrNull()?.let { k to it.config[InvDimKey] }
    }.toMap()

    @Test fun mini_ytOnlyTrack_showsOneIcon_bright() {
        renderMini(QItem(key = "dQw4w9WgXcQ", title = "T", artist = "A", sources = listOf("ytm")))
        assertEquals(mapOf("ytm" to false), icons())
    }

    @Test fun mini_trackWithoutSourceList_showsOnlyItsOwnSource() {
        renderMini(QItem(key = "x", title = "T", artist = "A"))
        assertEquals(mapOf("ytm" to false), icons())
    }

    @Test fun mini_ytAndScWithScActive_dimsYt_brightSc() {
        renderMini(QItem(source = "soundcloud", key = "https://soundcloud.com/a/b", title = "T", artist = "A", sources = listOf("ytm", "soundcloud")))
        assertEquals(mapOf("ytm" to true, "sc" to false), icons())
    }

    @Test fun mini_ytAndScWithYtActive_dimsSc() {
        renderMini(QItem(key = "k", title = "T", artist = "A", sources = listOf("ytm", "soundcloud")))
        assertEquals(mapOf("ytm" to false, "sc" to true), icons())
    }

    @Test fun mini_showsTheTracksRealCover_notTheGradient() {
        renderMini(QItem(key = "dQw4w9WgXcQ", title = "T", artist = "A", thumbnail = YT, sources = listOf("ytm")))
        val b = waitForPicture { shotOf("mini.cover") }
        val p = b.getPixel(b.width / 2, b.height / 2)
        assertTrue("mini cover shows the picture (centre pixel ${Integer.toHexString(p)})", AColor.blue(p) > 150 && AColor.red(p) < 80)
        assertNoDarkBand(b, "mini cover 48dp")
    }

    @Test fun mini_withoutPicture_usesTheGradient() {
        val s = mini(QItem(key = "k", title = "T"))
        assertEquals(null, s.cover.url)
        assertEquals(listOf(Provider.Ytm), s.sources)
    }
}
