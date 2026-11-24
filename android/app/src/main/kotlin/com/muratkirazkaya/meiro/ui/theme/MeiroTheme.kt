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
    /** `font-variant-numeric: tabular-nums` (digits of one width). */
    val tabular: Boolean = false,
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
        fontFeatureSettings = if (tabular) "tnum" else null,
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
    // Library and playlist: `.row .t b` 16/22, `.row .meta` 13/18, `.note` 12/17, `.hero h2` 24/30, `.hero .kind` and `.sub` 13/20, `.h-sec h2` 20/26.
    val ListTitle = MeiroText(16f, 400, 22f, Ls)
    val ListMeta = MeiroText(13f, 400, 18f, Ls)
    val Note = MeiroText(12f, 400, 17f, Ls)
    /** M6f: the sign-in page's address strip (`.webview .url`, 12/20). */
    val WebUrl = MeiroText(12f, 400, BodyLineHeightSp, Ls)
    // M5: the offline banner (`.banner`), the storage meter (`.meter` b, `.sub`, `.legend`).
    val Banner = MeiroText(13f, 400, BodyLineHeightSp, Ls)
    val MeterUsed = MeiroText(14f, 700, BodyLineHeightSp, Ls)
    val MeterSub = MeiroText(13f, 400, BodyLineHeightSp, Ls)
    val Legend = MeiroText(12f, 400, BodyLineHeightSp, Ls)
    /** The `.why` line of a recommended track (primary, one line with an ellipsis). */
    val Why = MeiroText(12f, 400, 16f, Ls)
    /** The history's `.why.muted` note: the `.why` line in on-surface-variant with tabular digits. */
    val WhyMuted = MeiroText(12f, 400, 16f, Ls, tabular = true)
    // History, stats and settings (M6b): `.stat b` 26/600/32 and `.stat span` 12 on the body's line; `.rank .n` 600 and
    // `.rank .t b` on the body's 15/20; `.rank .t span` 12; `.seg button` 13/500; `.sgroup` 13/500; `.srow .k b` 16/22,
    // `.srow .k span` 13/18 (it also sizes the `.tag` inside the title).
    val StatValue = MeiroText(26f, 600, 32f, Ls)
    val StatLabel = MeiroText(12f, 400, BodyLineHeightSp, Ls)
    val RankNum = MeiroText(BodyFontSizeSp, 600, BodyLineHeightSp, Ls)
    val RankTitle = MeiroText(BodyFontSizeSp, 400, BodyLineHeightSp, Ls)
    val RankSub = MeiroText(12f, 400, BodyLineHeightSp, Ls)
    val Segment = MeiroText(13f, 500, BodyLineHeightSp, 0f)
    val SettingsGroup = MeiroText(13f, 500, BodyLineHeightSp, Ls)
    val SettingTitle = MeiroText(16f, 400, 22f, Ls)
    val SettingSub = MeiroText(13f, 400, 18f, Ls)
    /** The account sheet's 48px avatar letter (`.shead .avatar`, 18/600). */
    val AvatarLarge = MeiroText(18f, 600, BodyLineHeightSp, Ls)
    val HeroTitle = MeiroText(24f, 600, 30f, Ls)
    val HeroKind = MeiroText(13f, 400, 20f, Ls)
    val SectionTitle = MeiroText(20f, 500, 26f, Ls)
    val SheetTitle = MeiroText(20f, 500, 26f, Ls)
    val SnackAction = MeiroText(BodyFontSizeSp, 600, BodyLineHeightSp, Ls)
    // Search: `.h-sec .more` 13/500, `.chips .chip` 13, the row `.chip` 12, `.tile b` 14/500/18 and `.tile span` 12/16.
    val SectionAction = MeiroText(13f, 500, BodyLineHeightSp, 0f)
    val ChipLabel = MeiroText(13f, 400, BodyLineHeightSp, 0f)
    val ChipSmall = MeiroText(12f, 400, BodyLineHeightSp, Ls)
    val TileTitle = MeiroText(14f, 500, 18f, Ls)
    val TileMeta = MeiroText(12f, 400, 16f, Ls)

    // Home (`.hello`, `.sub`, `.mix`).
    val Greeting = MeiroText(28f, 500, 36f, Ls)
    val GreetingSub = MeiroText(13f, 400, 20f, Ls)
    val MixKicker = MeiroText(12f, 400, 20f, 0.6f)
    val MixTitle = MeiroText(22f, 600, 28f, Ls)
    val MixBody = MeiroText(13f, 400, 18f, Ls)

    // Full player (`.np`): the context line's kicker 11/16 (.6 spacing, upper case) and list 12/16, `h2` 22/600/28,
    // `.a` 15/20, the link chip and source buttons 12 (buttons: spacing normal), times 12, low tabs 13/500 (.4, upper
    // case), the preparing note 13 and its second line 12.
    val NpKicker = MeiroText(11f, 400, 16f, 0.6f)
    val NpContext = MeiroText(12f, 400, 16f, Ls)
    val NpTitle = MeiroText(22f, 600, 28f, Ls)
    val NpArtist = MeiroText(15f, 400, 20f, Ls)
    val NpButton = MeiroText(12f, 400, BodyLineHeightSp, 0f)
    val NpTime = MeiroText(12f, 400, BodyLineHeightSp, Ls)
    val NpTab = MeiroText(13f, 500, BodyLineHeightSp, 0.4f)
    val NpPrep = MeiroText(13f, 400, BodyLineHeightSp, Ls)
    val NpPrepNote = MeiroText(12f, 400, BodyLineHeightSp, Ls)
    // Up next sheet: `.qtabs button` 14/500, `.qhead b` 14/500, `.qhead span` 12, `.qlabel` 13/500, `.tag` 11/500/16.
    val QTab = MeiroText(14f, 500, BodyLineHeightSp, 0f)
    val QHeadTitle = MeiroText(14f, 500, BodyLineHeightSp, Ls)
    val QHeadSub = MeiroText(12f, 400, BodyLineHeightSp, Ls)
    val QLabel = MeiroText(13f, 500, BodyLineHeightSp, Ls)
    val QTag = MeiroText(11f, 500, 16f, Ls)
    // Lyrics tab: `.lyrics` 24/34/600, `.lyrsrc .sub` 13 on the body's 20 line.
    val Lyric = MeiroText(24f, 600, 34f, Ls)
    val LyricSource = MeiroText(13f, 400, BodyLineHeightSp, Ls)
    // `.lyrsrc .chip`: a <button>, so the body's letter-spacing does not reach it.
    val LyricChip = MeiroText(12f, 400, BodyLineHeightSp, 0f)
    // Menu sheets: `.shead b` 16/500 and `.shead span` 13 on the body's 20 line; `.mi` 15 and its `.r` 12 (buttons:
    // spacing normal).
    val SheetHeadTitle = MeiroText(16f, 500, BodyLineHeightSp, Ls)
    val SheetHeadSub = MeiroText(13f, 400, BodyLineHeightSp, Ls)
    val MenuItem = MeiroText(15f, 400, BodyLineHeightSp, 0f)
    val MenuRight = MeiroText(12f, 400, BodyLineHeightSp, 0f)
}

object MeiroShapes {
    val Mini = RoundedCornerShape(16f)
}
