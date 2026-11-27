package com.benwrk.jabjotjai.ui.monitor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.capture.Capture
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import kotlinx.coroutines.awaitCancellation

/** How long the heart stays lit after each beat. */
private const val FLASH_NS = 150_000_000L

/**
 * The heart beside the heart-rate numeric: lit for a moment at each of the strap's beats, dim in
 * between. The beats are the same ones the beep sounds ([Capture.live]), so the two stay in step.
 * Its brightness is computed in the layer's draw block, which reads the clock, so it follows every
 * display frame without recomposing anything.
 */
@Composable
fun BeatHeart(beats: () -> List<Long>, nowMonoNs: State<Long>, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_heart),
        contentDescription = null,
        colorFilter = ColorFilter.tint(MonitorColors.Ecg),
        modifier = modifier.graphicsLayer {
            val now = nowMonoNs.value
            val last = beats().lastOrNull { it <= now }
            val lit = last != null && now - last < FLASH_NS
            alpha = if (lit) 1f else 0.2f
            scaleX = if (lit) 1.15f else 1f
            scaleY = scaleX
        },
    )
}

/**
 * Tells the capture service, which sounds the beat beep, whether the monitor is on screen: from
 * when the screen is started until it is stopped, so leaving the app or switching the screen off
 * counts as not on screen.
 */
@Composable
fun ReportMonitorVisible() {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            Capture.setMonitorVisible(true)
            try {
                awaitCancellation()
            } finally {
                Capture.setMonitorVisible(false)
            }
        }
    }
}

/** While [enabled], the volume keys on this screen set the media volume, the one the beep follows. */
@Composable
fun MediaVolumeKeys(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(enabled, activity) {
        if (enabled) activity?.volumeControlStream = AudioManager.STREAM_MUSIC
        onDispose { activity?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE }
    }
}

/** The activity behind [this] context, unwrapping any wrappers, or null if there is none. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
