package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.monitor.DisplaySettings
import com.benwrk.jabjotjai.monitor.JUMP_NS
import com.benwrk.jabjotjai.monitor.Playback
import com.benwrk.jabjotjai.monitor.elapsedText
import com.benwrk.jabjotjai.monitor.paused
import com.benwrk.jabjotjai.monitor.positionNs
import com.benwrk.jabjotjai.monitor.speed
import com.benwrk.jabjotjai.ui.theme.MonitorColors

/**
 * The capture's timeline and its controls, laid out like a video player's and kept quiet so the
 * waveforms stay what the eye goes to: a thin line across the full width, from the capture's start
 * to now, over a row of small grey controls.
 *
 * Dragging the line shows the moment under the finger, paused; letting go jumps there and plays on
 * as before the drag. The arrows either side of play jump 15 seconds back or forward. The time
 * counts back from live, or from the capture's start, and a tap switches between the two. The
 * speaker mutes or unmutes the beat beep, and the slider beside it sets its volume; dragging that
 * to nothing mutes it. The cog opens the display settings.
 *
 * @param startNs the capture's start, or null until its header has been read
 * @param sessionSeconds how long the capture has run, shown under the playhead's time
 */
@Composable
fun PlaybackBar(
    playback: Playback,
    startNs: Long?,
    nowNs: Long,
    sessionSeconds: Long?,
    settings: DisplaySettings,
    onSettings: ((DisplaySettings) -> DisplaySettings) -> Unit,
    onTogglePause: () -> Unit,
    onScrub: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onLive: () -> Unit,
    onJump: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = playback is Playback.Live
    val lengthNs = if (startNs == null) 0L else (nowNs - startNs).coerceAtLeast(1L)
    val positionNs = playback.positionNs(nowNs)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (startNs == null || live) 1f else ((positionNs - startNs).toFloat() / lengthNs).coerceIn(0f, 1f)

    // Touch targets smaller than the 48 dp default, so the bar takes little height from the traces.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) {
        Column(modifier) {
            ThinSlider(
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
                active = dragging != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                JumpButton(R.drawable.ic_jump_back, stringResource(R.string.playback_jump_back, JUMP_SECONDS), enabled = startNs != null) { onJump(-JUMP_NS) }
                SmallIconButton(
                    icon = if (playback.paused) R.drawable.ic_play else R.drawable.ic_pause,
                    description = stringResource(if (playback.paused) R.string.playback_play else R.string.playback_pause),
                    onClick = onTogglePause,
                )
                JumpButton(R.drawable.ic_jump_forward, stringResource(R.string.playback_jump_forward, JUMP_SECONDS), enabled = startNs != null && !live) {
                    onJump(JUMP_NS)
                }
                val time = if (settings.timeFromStart) {
                    elapsedText((fraction * lengthNs).toLong() / 1_000_000_000L)
                } else {
                    stringResource(R.string.playback_behind, elapsedText(((1f - fraction) * lengthNs).toLong() / 1_000_000_000L))
                }
                // The playhead's time, and under it how long the capture has run.
                Column(
                    Modifier
                        .clickable(onClickLabel = stringResource(R.string.playback_time_switch)) {
                            onSettings { it.copy(timeFromStart = !it.timeFromStart) }
                        }
                        .padding(horizontal = 8.dp),
                ) {
                    Text(time, color = MonitorColors.Secondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                    Text(
                        elapsedText(sessionSeconds ?: 0),
                        color = MonitorColors.Secondary.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                LiveButton(live, onLive)
                Spacer(Modifier.weight(1f))
                BeepVolume(settings, onSettings)
                SpeedButton(playback, onSpeed)
                SmallIconButton(R.drawable.ic_settings, stringResource(R.string.display_open), onOpenSettings)
            }
        }
    }
}

/** A slider drawn as a thin line with a small dot, which grows while [active]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    active: Boolean = false,
    lineHeight: Dp = 3.dp,
    dot: Dp = 10.dp,
) {
    val colors = SliderDefaults.colors(
        thumbColor = MonitorColors.Text,
        activeTrackColor = MonitorColors.Text.copy(alpha = 0.75f),
        inactiveTrackColor = MonitorColors.Secondary.copy(alpha = 0.3f),
        disabledActiveTrackColor = MonitorColors.Secondary.copy(alpha = 0.3f),
        disabledInactiveTrackColor = MonitorColors.Secondary.copy(alpha = 0.3f),
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        colors = colors,
        thumb = {
            // The slider gives the thumb its full height and draws it from the top, while the line
            // is centred; filling that height and centring the dot puts the dot on the line.
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (active) dot + 4.dp else dot).background(if (enabled) MonitorColors.Text else Color.Transparent, CircleShape))
            }
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(lineHeight),
                colors = colors,
                enabled = enabled,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
            )
        },
        modifier = modifier.height(24.dp),
    )
}

@Composable
private fun SmallIconButton(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(painterResource(icon), contentDescription = description, tint = MonitorColors.Secondary, modifier = Modifier.size(20.dp))
    }
}

/**
 * Mute and volume for the beat beep, like a video player's: the speaker mutes or unmutes, and the
 * slider shows nothing while muted. Unmuting a volume left near nothing brings it back to full.
 */
@Composable
private fun BeepVolume(settings: DisplaySettings, onSettings: ((DisplaySettings) -> DisplaySettings) -> Unit) {
    SmallIconButton(
        icon = if (settings.beep) R.drawable.ic_volume_on else R.drawable.ic_volume_off,
        description = stringResource(if (settings.beep) R.string.playback_beep_mute else R.string.playback_beep_unmute),
        onClick = {
            onSettings {
                if (it.beep) {
                    it.copy(beep = false)
                } else {
                    it.copy(beep = true, beepVolume = if (it.beepVolume < MIN_UNMUTED_VOLUME) 1f else it.beepVolume)
                }
            }
        },
    )
    val description = stringResource(R.string.playback_beep_volume)
    ThinSlider(
        value = if (settings.beep) settings.beepVolume else 0f,
        onValueChange = { volume ->
            // Dragging to nothing mutes and keeps the last volume heard, for unmuting.
            onSettings { if (volume > 0f) it.copy(beep = true, beepVolume = volume) else it.copy(beep = false) }
        },
        dot = 8.dp,
        modifier = Modifier.width(72.dp).semantics { contentDescription = description },
    )
}

/** The speed, and a menu of the others. Faster than 1× needs something to catch up on, so not at live. */
@Composable
private fun SpeedButton(playback: Playback, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            stringResource(R.string.playback_speed, plainNumber(playback.speed)),
            color = MonitorColors.Secondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clickable { open = true }.padding(horizontal = 10.dp, vertical = 8.dp),
        )
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

/** A red dot at live, as on a live video stream; grey behind it, where a tap goes back to live. */
@Composable
private fun LiveButton(live: Boolean, onLive: () -> Unit) {
    Row(
        Modifier.clickable(enabled = !live, onClick = onLive).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(if (live) MonitorColors.Alarm else MonitorColors.Secondary, CircleShape))
        Text(
            stringResource(R.string.playback_live),
            color = if (live) MonitorColors.Text else MonitorColors.Secondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** The jump buttons' seconds, drawn inside their arrows. */
private const val JUMP_SECONDS = (JUMP_NS / 1_000_000_000L).toInt()

/** Below this, unmuting brings the volume back to full rather than to near silence. */
private const val MIN_UNMUTED_VOLUME = 0.1f

/** A circular arrow with the seconds it jumps inside it, as a video player draws it; grey when [enabled] is off. */
@Composable
private fun JumpButton(icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MonitorColors.Secondary else MonitorColors.Secondary.copy(alpha = 0.38f)
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
            // The arrow's circle is centred a twelfth of the icon below its middle.
            Text(
                JUMP_SECONDS.toString(),
                color = tint,
                fontSize = 7.sp,
                lineHeight = 7.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
