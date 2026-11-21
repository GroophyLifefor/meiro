package com.muratkirazkaya.meiro.ui.inv

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import com.muratkirazkaya.meiro.ui.theme.MeiroText

/**
 * Hooks for the screen-vs-mock comparison (see android/tools/README in check.sh output and
 * app/src/test/.../ElementComparison). Every drawn element that exists in the mock inventory carries
 * a test tag (its "inventory key") plus the style it is drawn with, exposed as semantics, so the
 * JVM test can read bounds and style from the real composition. No visual effect.
 */
class InvText(val style: MeiroText, val color: Color)

val InvTextKey = SemanticsPropertyKey<InvText>("InvText")
val InvBgKey = SemanticsPropertyKey<Color>("InvBg")
val InvRadiusKey = SemanticsPropertyKey<Float>("InvRadiusDp")
val InvTintKey = SemanticsPropertyKey<Color>("InvTint")
val InvOverflowKey = SemanticsPropertyKey<Boolean>("InvTextOverflow")
/** Text that is meant to end in an ellipsis when it is too long (one line, `text-overflow: ellipsis` in the mock). */
val InvSingleLineKey = SemanticsPropertyKey<Boolean>("InvTextSingleLine")

var SemanticsPropertyReceiver.invText by InvTextKey
var SemanticsPropertyReceiver.invBg by InvBgKey
var SemanticsPropertyReceiver.invRadius by InvRadiusKey
var SemanticsPropertyReceiver.invTint by InvTintKey
var SemanticsPropertyReceiver.invOverflow by InvOverflowKey
var SemanticsPropertyReceiver.invSingleLine by InvSingleLineKey

/** Tag a node with its inventory key; place it after the size/padding modifiers so bounds match the box. */
fun Modifier.inv(
    key: String,
    bg: Color? = null,
    radiusDp: Float? = null,
    tint: Color? = null,
): Modifier = testTag(key).semantics {
    if (bg != null) invBg = bg
    if (radiusDp != null) invRadius = radiusDp
    if (tint != null) invTint = tint
}
