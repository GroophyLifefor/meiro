package com.muratkirazkaya.meiro.ui.components

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch

/**
 * The mock's motion (`docs/android/mock/index.html`): every duration and curve here is copied from its CSS, so a change
 * there is a change here. `ease` is CSS's default curve (`transition:.2s` with no curve).
 */
object Motion {
    /** `cubic-bezier(.2,0,0,1)`: the sheets, the full player and the page slide. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** CSS `ease`: `.scrim`, `.snack` and `.sw::after` name a time only. */
    val CssEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    const val SHEET_MS = 280     // .sheet transform .28s
    const val SCRIM_MS = 200     // .scrim opacity .2s
    const val PLAYER_MS = 320    // .np transform .32s
    const val SNACK_MS = 200     // .snack .2s
    const val SWITCH_MS = 150    // .sw::after .15s
    const val PAGE_MS = 300      // navSlide (the mock's page slide)
    const val PAGE_FADE_IN_MS = 150
    const val PAGE_FADE_OUT_MS = 200

    /** A closed sheet sits at translateY(105%). */
    const val SHEET_CLOSED = 1.05f

    fun <T> sheetSpec(): FiniteAnimationSpec<T> = tween(SHEET_MS, easing = Standard)
    fun <T> scrimSpec(): FiniteAnimationSpec<T> = tween(SCRIM_MS, easing = CssEase)
    fun <T> playerSpec(): FiniteAnimationSpec<T> = tween(PLAYER_MS, easing = Standard)
    fun <T> snackSpec(): FiniteAnimationSpec<T> = tween(SNACK_MS, easing = CssEase)
    fun <T> switchSpec(): FiniteAnimationSpec<T> = tween(SWITCH_MS, easing = CssEase)
    fun <T> pageSpec(): FiniteAnimationSpec<T> = tween(PAGE_MS, easing = Standard)

    // The page slide: the new page comes in from the right with a short fade; the one under it moves a quarter of the
    // width to the left and fades; Back runs it the other way. Switching the bottom bar's tabs does not animate (the
    // mock's 'tab' action re-renders at once).
    val pageEnter: EnterTransition = slideInHorizontally(pageSpec()) { it } + fadeIn(tween(PAGE_FADE_IN_MS, easing = androidx.compose.animation.core.LinearEasing))
    val pageExit: ExitTransition = slideOutHorizontally(pageSpec()) { -it / 4 } + fadeOut(tween(PAGE_FADE_OUT_MS, easing = androidx.compose.animation.core.LinearEasing))
    val pagePopEnter: EnterTransition = slideInHorizontally(pageSpec()) { -it / 4 } + fadeIn(tween(PAGE_FADE_IN_MS, easing = androidx.compose.animation.core.LinearEasing))
    val pagePopExit: ExitTransition = slideOutHorizontally(pageSpec()) { it } + fadeOut(tween(PAGE_FADE_OUT_MS, easing = androidx.compose.animation.core.LinearEasing))
}

/** How far a sheet and its scrim have come, 0 (closed) to 1 (open). Read in the draw phase only, so a slide does not recompose. */
class SheetProgress(val scrim: Animatable<Float, *>, val sheet: Animatable<Float, *>)

/** Null where a sheet is drawn on its own (the element comparison, previews): settled, open. */
val LocalSheetProgress = compositionLocalOf<SheetProgress?> { null }

/**
 * Keeps [content] for [value] drawn while the sheet slides in and, once [value] is null, while it slides out again
 * (a closing sheet must still be there to be seen). [content] gets the last non-null value. The sheet and the scrim
 * read their progress from [LocalSheetProgress]; touches are swallowed while it is closing.
 */
@Composable
fun <T : Any> SheetPresence(value: T?, content: @Composable (T) -> Unit) {
    val holder = remember { arrayOfNulls<Any>(1) }
    @Suppress("UNCHECKED_CAST")
    if (value != null) holder[0] = value
    val progress = remember { SheetProgress(Animatable(0f), Animatable(0f)) }
    val open = value != null
    LaunchedEffect(open) {
        val target = if (open) 1f else 0f
        launch { progress.scrim.animateTo(target, Motion.scrimSpec()) }
        progress.sheet.animateTo(target, Motion.sheetSpec())
    }
    val visible by remember(open) { derivedStateOf { open || progress.sheet.value > 0f || progress.scrim.value > 0f } }
    @Suppress("UNCHECKED_CAST")
    val shown = holder[0] as T?
    if (shown != null && visible) {
        CompositionLocalProvider(LocalSheetProgress provides progress) {
            Box(
                Modifier.fillMaxSize().pointerInput(open) {
                    if (!open) awaitPointerEventScope {
                        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                },
            ) { content(shown) }
        }
    }
}

/** The scrim's fade (`.scrim` opacity .2s). */
fun Modifier.sheetScrim(p: SheetProgress?): Modifier = if (p == null) this else graphicsLayer { alpha = p.scrim.value }

/** The sheet's slide (`.sheet` translateY(105%) to none in .28s). Put it after the sheet's size modifiers. */
fun Modifier.sheetSlide(p: SheetProgress?): Modifier =
    if (p == null) this else graphicsLayer { translationY = (1f - p.sheet.value) * Motion.SHEET_CLOSED * size.height }

/** Whether [route] is one of the bottom bar's four tabs. */
fun isTabRoute(route: String?): Boolean = Tab.entries.any { it.route == route }

/** What a push (not a tab switch) does to the page it opens and to the page under it; null: a tab switch, no motion. */
fun popEnter(from: String?, to: String?): EnterTransition = if (isTabRoute(from) && isTabRoute(to)) EnterTransition.None else Motion.pagePopEnter
fun popExit(from: String?, to: String?): ExitTransition = if (isTabRoute(from) && isTabRoute(to)) ExitTransition.None else Motion.pagePopExit
fun pushEnter(toRoute: String?): EnterTransition = if (isTabRoute(toRoute)) EnterTransition.None else Motion.pageEnter
fun pushExit(toRoute: String?): ExitTransition = if (isTabRoute(toRoute)) ExitTransition.None else Motion.pageExit
