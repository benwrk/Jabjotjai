package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toComposeRect
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
