package com.benwrk.jabjotjai.ui.monitor

import android.os.SystemClock
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
import androidx.compose.runtime.mutableLongStateOf
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
import kotlin.time.Duration.Companion.milliseconds

/**
 * [content] with a tip that a tap or a long press shows below it for a few seconds, longer for a
 * longer tip, and that also describes it to a screen reader. With [onClick], a tap does that
 * instead and only a long press shows the tip.
 */
@Composable
fun WithTip(
    tip: String,
    modifier: Modifier = Modifier,
    tint: Color = MonitorColors.Text,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    // A tap on the anchor while the tip shows first closes it, as a touch outside the tip, and then
    // reaches the anchor as a click; without this, that click showed the tip again at once.
    var closedAtMs by remember { mutableLongStateOf(0L) }
    val toggle = {
        if (shown) {
            shown = false
        } else if (SystemClock.uptimeMillis() - closedAtMs > REOPEN_AFTER_CLOSE_MS) {
            shown = true
        }
    }
    Box(
        modifier
            .combinedClickable(onLongClick = { shown = true }) { if (onClick != null) onClick() else toggle() }
            .semantics { contentDescription = tip },
    ) {
        content()
        if (shown) {
            val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
            val close = {
                shown = false
                closedAtMs = SystemClock.uptimeMillis()
            }
            Popup(popupPositionProvider = BelowAnchor(gap), onDismissRequest = close) {
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
            // Timed from when the tip was shown, for the text it showed: a tip whose text changes
            // every second, such as the last RR interval, would otherwise never hide.
            LaunchedEffect(Unit) {
                delay((TIP_MIN_MS + tip.length * TIP_MS_PER_CHAR).coerceAtMost(TIP_MAX_MS).milliseconds)
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

internal val TIP_BACKGROUND = Color(0xFF2A2A2A)
private const val TIP_MIN_MS = 2_000L
private const val TIP_MS_PER_CHAR = 40L
private const val TIP_MAX_MS = 9_000L

/** How soon after the tip closes a click on its anchor counts as the touch that closed it. */
private const val REOPEN_AFTER_CLOSE_MS = 500L
