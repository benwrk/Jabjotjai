package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat

/**
 * The camera cut-out's areas, in pixels: in the window's coordinates where the chart provides them,
 * and in a band's own coordinates inside the band (see `Band`). Empty while the chart keeps clear of
 * the cut-out, so nothing needs to move.
 */
internal val LocalCutout = compositionLocalOf { emptyList<Rect>() }

/**
 * How far [area] must move right to clear every one of the [cutout]'s areas it overlaps, with [gap]
 * to spare; zero if it overlaps none. For what sits at a band's left edge.
 */
internal fun clearanceRight(area: Rect, cutout: List<Rect>, gap: Float): Float =
    cutout.filter { it.overlaps(area) }.maxOfOrNull { it.right - area.left + gap } ?: 0f

/** As [clearanceRight], but moving left, for what sits at a band's right edge. */
internal fun clearanceLeft(area: Rect, cutout: List<Rect>, gap: Float): Float =
    cutout.filter { it.overlaps(area) }.maxOfOrNull { area.right - it.left + gap } ?: 0f

/**
 * How far a strip at [bounds] must keep in from its left and from its right edge to clear the
 * [cutout], with [gap] to spare: the camera's reach into its left half, and into its right half;
 * zero on a side the camera does not overlap.
 */
internal fun edgeClearance(bounds: Rect, cutout: List<Rect>, gap: Float): Pair<Float, Float> {
    if (bounds.isEmpty) return 0f to 0f
    val left = clearanceRight(Rect(bounds.left, bounds.top, bounds.center.x, bounds.bottom), cutout, gap)
    val right = clearanceLeft(Rect(bounds.center.x, bounds.top, bounds.right, bounds.bottom), cutout, gap)
    return left to right
}

/**
 * [content] reaching the screen's edges, but kept in from a side where the camera cut-out, in the
 * window's coordinates in [LocalCutout], overlaps it (see [edgeClearance]).
 */
@Composable
internal fun ClearOfCutout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cutout = LocalCutout.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val (left, right) = with(LocalDensity.current) {
        edgeClearance(bounds, cutout, CUTOUT_GAP.toPx()).let { (left, right) -> left.toDp() to right.toDp() }
    }
    Box(modifier.onGloballyPositioned { bounds = it.boundsInWindow() }.absolutePadding(left = left, right = right)) {
        content()
    }
}

/**
 * The camera cut-out's areas as Android reports them now (`DisplayCutout.getBoundingRects`); empty
 * where there is none. They are documented as areas of the display; the monitor's window fills the
 * display, so they are its window coordinates too.
 */
@Composable
internal fun rememberCutoutInWindow(): List<Rect> {
    val view = LocalView.current
    val density = LocalDensity.current
    // Read so that this runs again when the cut-out's insets change, as they do on rotation.
    val insets = WindowInsets.displayCutout
    val sides = listOf(
        insets.getLeft(density, LayoutDirection.Ltr),
        insets.getTop(density),
        insets.getRight(density, LayoutDirection.Ltr),
        insets.getBottom(density),
    )
    return remember(view, sides) {
        ViewCompat.getRootWindowInsets(view)?.displayCutout?.boundingRects.orEmpty().map { it.toComposeRect() }
    }
}
