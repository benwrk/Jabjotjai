package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import kotlinx.coroutines.delay

/**
 * [content] with a tip that a tap or a long press shows below it for a few seconds, longer for a
 * longer tip, and that also describes it to a screen reader.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithTip(tip: String, modifier: Modifier = Modifier, tint: Color = MonitorColors.Text, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    Box(
        modifier
            .combinedClickable(onClickLabel = tip, onLongClick = { shown = true }) { shown = !shown }
            .semantics { contentDescription = tip },
    ) {
        content()
        if (shown) {
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            Popup(popupPositionProvider = BelowAnchor(gap), onDismissRequest = { shown = false }) {
                Text(
                    tip,
                    color = tint,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .background(TIP_BACKGROUND, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            LaunchedEffect(tip) {
                delay((TIP_MIN_MS + tip.length * TIP_MS_PER_CHAR).coerceAtMost(TIP_MAX_MS))
                shown = false
            }
        }
    }
}

/** Centred below the anchor, kept inside the window. */
private class BelowAnchor(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx
        val y = if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top - gapPx - popupContentSize.height
        return IntOffset(x, y.coerceAtLeast(0))
    }
}

private val TIP_BACKGROUND = Color(0xFF2A2A2A)
private const val TIP_MIN_MS = 2_000L
private const val TIP_MS_PER_CHAR = 40L
private const val TIP_MAX_MS = 9_000L
