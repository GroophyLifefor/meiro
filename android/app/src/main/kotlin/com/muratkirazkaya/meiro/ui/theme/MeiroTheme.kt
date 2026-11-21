package com.muratkirazkaya.meiro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.muratkirazkaya.meiro.R

val LocalMeiroColors = staticCompositionLocalOf { LightMeiroColors }

object Meiro {
    val colors: MeiroColors
        @Composable @ReadOnlyComposable get() = LocalMeiroColors.current
}

/** Light or dark colour set; follows the system setting unless forced (tests force it). */
@Composable
fun MeiroTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMeiroColors provides if (darkTheme) DarkMeiroColors else LightMeiroColors,
        content = content,
    )
}

/** Google Sans (variable, 100-1000) at the weights the mock uses. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val GoogleSans = FontFamily(
    listOf(400, 500, 600, 700).map { w ->
        Font(R.font.google_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** A text style of the mock: CSS font-size/weight/line-height/letter-spacing, all in px = dp = sp at 1x. */
@Immutable
data class MeiroText(
    val sizeSp: Float, val weight: Int, val lineHeightSp: Float, val letterSpacingSp: Float,
    /** True when the CSS line-height is smaller than the font's natural height (Compose then needs Trim.Both to honour it). */
    val tightLine: Boolean = false,
) {
    fun toStyle(color: Color) = TextStyle(
        color = color,
        fontFamily = GoogleSans,
        fontSize = sizeSp.sp,
        fontWeight = FontWeight(weight),
        lineHeight = lineHeightSp.sp,
        letterSpacing = letterSpacingSp.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, if (tightLine) LineHeightStyle.Trim.Both else LineHeightStyle.Trim.None),
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}

/** Styles used so far, named by the inventory values they come from. Buttons have letter-spacing normal. */
object MeiroType {
    private const val Ls = BodyLetterSpacingSp
    val TopBarTitle = MeiroText(22f, 500, 28f, Ls)
    val AvatarInitial = MeiroText(13f, 600, BodyLineHeightSp, 0f)
    val TabLabel = MeiroText(14f, 500, BodyLineHeightSp, 0f)
    val EmptyTitle = MeiroText(18f, 500, BodyLineHeightSp, Ls, tightLine = true)
    val EmptyBody = MeiroText(BodyFontSizeSp, 400, BodyLineHeightSp, Ls)
    val ButtonLabel = MeiroText(14f, 500, BodyLineHeightSp, 0f)
    val MiniTitle = MeiroText(14f, 500, BodyLineHeightSp, Ls)
    val MiniArtist = MeiroText(12f, 400, BodyLineHeightSp, Ls)
    val NavLabel = MeiroText(12f, 500, BodyLineHeightSp, 0f)
    // Sign-in (`.welcome`): logo 34/700 in a 20px line, h1 30/38, 16px input, 12px label and note.
    val SignInLogo = MeiroText(34f, 700, BodyLineHeightSp, Ls, tightLine = true)
    val SignInTitle = MeiroText(30f, 500, 38f, Ls)
    val Body = MeiroText(BodyFontSizeSp, 400, BodyLineHeightSp, Ls)
    val FieldLabel = MeiroText(12f, 400, BodyLineHeightSp, Ls)
    val FieldInput = MeiroText(16f, 400, BodyLineHeightSp, 0f)
    val SnackAction = MeiroText(BodyFontSizeSp, 600, BodyLineHeightSp, Ls)
}

object MeiroShapes {
    val Mini = RoundedCornerShape(16f)
}
