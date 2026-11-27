package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.monitor.Playback
import com.benwrk.jabjotjai.monitor.elapsedText
import com.benwrk.jabjotjai.monitor.paused
import com.benwrk.jabjotjai.monitor.positionNs
import com.benwrk.jabjotjai.monitor.speed
import com.benwrk.jabjotjai.ui.theme.MonitorColors

/**
 * The capture's timeline, like a live video stream's: the bar runs from the capture's start to
 * now. At live the time is the capture's length and LIVE is lit; in the past the time is how far
 * behind live the playhead is, and tapping LIVE goes back. Dragging the slider shows the moment
 * under the finger, paused; letting go jumps there and plays on as before the drag.
 *
 * @param startNs the capture's start, or null until its header has been read
 */
@Composable
fun PlaybackBar(
    playback: Playback,
    startNs: Long?,
    nowNs: Long,
    onTogglePause: () -> Unit,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = playback is Playback.Live
    val lengthNs = if (startNs == null) 0L else (nowNs - startNs).coerceAtLeast(1L)
    val positionNs = playback.positionNs(nowNs)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (startNs == null || live) 1f else ((positionNs - startNs).toFloat() / lengthNs).coerceIn(0f, 1f)
    val shownBehindNs = if (startNs == null) 0L else ((1f - fraction) * lengthNs).toLong()

    Row(
        modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onTogglePause) {
            Icon(
                painterResource(if (playback.paused) R.drawable.ic_play else R.drawable.ic_pause),
                contentDescription = stringResource(if (playback.paused) R.string.playback_play else R.string.playback_pause),
                tint = MonitorColors.Text,
            )
        }
        Text(
            if (live && dragging == null) {
                elapsedText(lengthNs / 1_000_000_000L)
            } else {
                stringResource(R.string.playback_behind, elapsedText(shownBehindNs / 1_000_000_000L))
            },
            color = MonitorColors.Secondary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Slider(
            value = fraction,
            onValueChange = {
                dragging = it
                if (startNs != null) onScrub(startNs + (it * lengthNs).toLong())
            },
            onValueChangeFinished = {
                val target = dragging
                dragging = null
                if (startNs != null && target != null) onSeek(startNs + (target * lengthNs).toLong())
            },
            enabled = startNs != null,
            colors = SliderDefaults.colors(thumbColor = MonitorColors.Ecg, activeTrackColor = MonitorColors.Ecg),
            modifier = Modifier.weight(1f),
        )
        SpeedButton(playback, onSpeed)
        TextButton(onClick = onLive, enabled = !live) {
            Box(Modifier.size(8.dp).background(if (live) MonitorColors.Alarm else MonitorColors.Secondary, CircleShape))
            Text(
                stringResource(R.string.playback_live),
                color = if (live) MonitorColors.Alarm else MonitorColors.Text,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** The speed, and a menu of the others. Faster than 1× needs something to catch up on, so not at live. */
@Composable
private fun SpeedButton(playback: Playback, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.playback_speed, plainNumber(playback.speed)), color = MonitorColors.Text)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (speed in Playback.SPEEDS) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playback_speed, plainNumber(speed))) },
                    enabled = playback !is Playback.Live || speed <= 1f,
                    onClick = {
                        open = false
                        onSpeed(speed)
                    },
                )
            }
        }
    }
}
