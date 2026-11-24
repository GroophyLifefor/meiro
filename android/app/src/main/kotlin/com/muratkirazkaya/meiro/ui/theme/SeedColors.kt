package com.muratkirazkaya.meiro.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * M6h: the colour the app grows from ("Duvar kağıdından renk" and the swatches of the settings). The mock's first
 * swatch is the mock's own palette ([LightMeiroColors]/[DarkMeiroColors], generated from `tokens.json`); another swatch
 * builds every Material role from its seed as the desktop's `m3` package does (`NewPalettes` with the tonal-spot style,
 * `NewScheme`: tonal palettes in Oklch); the wallpaper's colours come from Android (12+). The mock's own colours with no
 * Material role (the scrim, the page around the phone, the sign-in frame) stay as the tokens give them.
 */
object SeedColors {
    /** The mock's swatches, in its order; the first is the mock's own palette. */
    val SWATCHES: List<String> = listOf("#7c5cff", "#3b82f6", "#10b981", "#f59e0b", "#ef4444")
    val DEFAULT: String = SWATCHES.first()

    /** The colours of [seed] (a "#rrggbb" swatch) for the light or dark theme; the default seed is the mock's palette. */
    fun of(seed: String, dark: Boolean): MeiroColors {
        val base = if (dark) DarkMeiroColors else LightMeiroColors
        if (seed.equals(DEFAULT, ignoreCase = true)) return base
        val rgb = parse(seed) ?: return base
        return scheme(rgb, dark, base)
    }

    /** Android's wallpaper colours (Android 12+), or null below it. */
    fun wallpaper(context: Context, dark: Boolean): MeiroColors? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val s = if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
        return fromScheme(s, if (dark) DarkMeiroColors else LightMeiroColors)
    }

    /** A Material colour scheme in the app's roles. */
    fun fromScheme(s: ColorScheme, base: MeiroColors): MeiroColors = base.copyRoles(
        surface = s.surface, surfaceContainerLow = s.surfaceContainerLow, surfaceContainer = s.surfaceContainer,
        surfaceContainerHigh = s.surfaceContainerHigh, surfaceContainerHighest = s.surfaceContainerHighest,
        onSurface = s.onSurface, onSurfaceVariant = s.onSurfaceVariant, outline = s.outline, outlineVariant = s.outlineVariant,
        primary = s.primary, onPrimary = s.onPrimary, primaryContainer = s.primaryContainer, onPrimaryContainer = s.onPrimaryContainer,
        secondaryContainer = s.secondaryContainer, onSecondaryContainer = s.onSecondaryContainer,
        tertiaryContainer = s.tertiaryContainer, onTertiaryContainer = s.onTertiaryContainer,
        error = s.error, errorContainer = s.errorContainer, onErrorContainer = s.onErrorContainer,
    )

    fun parse(hex: String): Int? = hex.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)

    // ---- the desktop's m3/color.go ----

    /** One hue and chroma (Oklch), read at any tone (`Palette.Tone`). */
    data class Palette(val hue: Double, val chroma: Double) {
        fun tone(t: Double): Color = oklchToColor(toneToLightness(t.coerceIn(0.0, 100.0)), chroma, hue)
    }

    /** `NewScheme(NewPalettes(seed, TonalSpot), dark)` in the app's roles. */
    fun scheme(rgb: Int, dark: Boolean, base: MeiroColors): MeiroColors {
        val hue = hueOf(rgb)
        val primary = Palette(hue, 0.125)
        val secondary = Palette(hue, 0.042)
        val tertiary = Palette((hue + 60) % 360, 0.07)
        val neutral = Palette(hue, 0.008)
        val variant = Palette(hue, 0.017)
        val error = Palette(29.0, 0.19)
        fun pick(light: Int, darkTone: Int) = (if (dark) darkTone else light).toDouble()
        return base.copyRoles(
            primary = primary.tone(pick(40, 80)), onPrimary = primary.tone(pick(100, 20)),
            primaryContainer = primary.tone(pick(90, 30)), onPrimaryContainer = primary.tone(pick(10, 90)),
            secondaryContainer = secondary.tone(pick(90, 30)), onSecondaryContainer = secondary.tone(pick(10, 90)),
            tertiaryContainer = tertiary.tone(pick(90, 30)), onTertiaryContainer = tertiary.tone(pick(10, 90)),
            error = error.tone(pick(40, 80)), errorContainer = error.tone(pick(90, 30)), onErrorContainer = error.tone(pick(10, 90)),
            surface = neutral.tone(pick(98, 6)), onSurface = neutral.tone(pick(10, 90)), onSurfaceVariant = variant.tone(pick(30, 80)),
            surfaceContainerLow = neutral.tone(pick(96, 10)), surfaceContainer = neutral.tone(pick(94, 12)),
            surfaceContainerHigh = neutral.tone(pick(92, 17)), surfaceContainerHighest = neutral.tone(pick(90, 22)),
            outline = variant.tone(pick(50, 60)), outlineVariant = variant.tone(pick(80, 30)),
        )
    }

    /** `HueOf`: the Oklch hue of a colour, in degrees. */
    fun hueOf(rgb: Int): Double {
        val (_, a, b) = toOklab(rgb)
        val h = Math.toDegrees(atan2(b, a))
        return if (h < 0) h + 360 else h
    }

    private fun toneToLightness(tone: Double): Double {
        val y = if (tone > 8) ((tone + 16) / 116).pow(3) else tone / 903.2963
        return cbrt(y)
    }

    private fun srgbToLinear(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun linearToSrgb(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055

    private fun toOklab(rgb: Int): Triple<Double, Double, Double> {
        val r = srgbToLinear(((rgb shr 16) and 0xFF) / 255.0)
        val g = srgbToLinear(((rgb shr 8) and 0xFF) / 255.0)
        val bl = srgbToLinear((rgb and 0xFF) / 255.0)
        val lc = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * bl)
        val mc = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * bl)
        val sc = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * bl)
        return Triple(
            0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc,
            1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc,
            0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc,
        )
    }

    private fun oklabToLinear(l: Double, a: Double, b: Double): DoubleArray {
        var lc = l + 0.3963377774 * a + 0.2158037573 * b
        var mc = l - 0.1055613458 * a - 0.0638541728 * b
        var sc = l - 0.0894841775 * a - 1.2914855480 * b
        lc = lc * lc * lc; mc = mc * mc * mc; sc = sc * sc * sc
        return doubleArrayOf(
            4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc,
            -1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc,
            -0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc,
        )
    }

    private fun inGamut(c: DoubleArray): Boolean { val e = 1e-4; return c.all { it >= -e && it <= 1 + e } }

    /** `oklchToColor`: the sRGB colour of an Oklch colour, chroma taken away until it fits (22 halvings). */
    fun oklchToColor(l: Double, c: Double, h: Double): Color {
        if (l <= 0) return Color(0xFF000000)
        if (l >= 1) return Color(0xFFFFFFFF)
        val rad = Math.toRadians(h)
        val cs = cos(rad); val sn = sin(rad)
        var rgb = oklabToLinear(l, c * cs, c * sn)
        if (!inGamut(rgb)) {
            var lo = 0.0; var hi = c
            repeat(22) {
                val mid = (lo + hi) / 2
                if (inGamut(oklabToLinear(l, mid * cs, mid * sn))) lo = mid else hi = mid
            }
            rgb = oklabToLinear(l, lo * cs, lo * sn)
        }
        // Go's math.Round and roundToInt agree on these non-negative values.
        fun to8(v: Double): Int = (max(0.0, min(1.0, linearToSrgb(max(0.0, min(1.0, v))))) * 255).roundToInt()
        return Color(to8(rgb[0]), to8(rgb[1]), to8(rgb[2]))
    }
}

/** The same colours with the Material roles replaced; the mock-only colours kept. */
fun MeiroColors.copyRoles(
    surface: Color = this.surface, surfaceContainerLow: Color = this.surfaceContainerLow, surfaceContainer: Color = this.surfaceContainer,
    surfaceContainerHigh: Color = this.surfaceContainerHigh, surfaceContainerHighest: Color = this.surfaceContainerHighest,
    onSurface: Color = this.onSurface, onSurfaceVariant: Color = this.onSurfaceVariant, outline: Color = this.outline, outlineVariant: Color = this.outlineVariant,
    primary: Color = this.primary, onPrimary: Color = this.onPrimary, primaryContainer: Color = this.primaryContainer, onPrimaryContainer: Color = this.onPrimaryContainer,
    secondaryContainer: Color = this.secondaryContainer, onSecondaryContainer: Color = this.onSecondaryContainer,
    tertiaryContainer: Color = this.tertiaryContainer, onTertiaryContainer: Color = this.onTertiaryContainer,
    error: Color = this.error, errorContainer: Color = this.errorContainer, onErrorContainer: Color = this.onErrorContainer,
): MeiroColors = MeiroColors(
    surface, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest, onSurface, onSurfaceVariant,
    outline, outlineVariant, primary, onPrimary, primaryContainer, onPrimaryContainer, secondaryContainer, onSecondaryContainer,
    tertiaryContainer, onTertiaryContainer, error, errorContainer, onErrorContainer,
    scrim, page, wBg, wText, wSub, wStrip, wUrl, wLock, wLine, wLink, wBtn, wBtnText,
)
