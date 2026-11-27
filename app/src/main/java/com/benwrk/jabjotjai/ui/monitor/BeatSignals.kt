package com.benwrk.jabjotjai.ui.monitor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.monitor.MonitorSnapshot
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How long the heart stays lit after each beat. */
private const val FLASH_NS = 150_000_000L

/** A beat noticed later than this after its time is passed over silently rather than sounded late. */
private const val LATE_BEEP_NS = 150_000_000L

private const val BEEP_POLL_MS = 10L

/**
 * The heart beside the heart-rate numeric: lit for a moment at each of the strap's beats, dim in
 * between. Its brightness is computed in the layer's draw block, which reads the clock, so it
 * follows every display frame without recomposing anything.
 */
@Composable
fun BeatHeart(snapshot: State<MonitorSnapshot>, nowMonoNs: State<Long>, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_heart),
        contentDescription = null,
        colorFilter = ColorFilter.tint(MonitorColors.Ecg),
        modifier = modifier.graphicsLayer {
            val now = nowMonoNs.value
            val last = snapshot.value.beats.lastOrNull { it <= now }
            val lit = last != null && now - last < FLASH_NS
            alpha = if (lit) 1f else 0.2f
            scaleX = if (lit) 1.15f else 1f
            scaleY = scaleX
        },
    )
}

/**
 * Beeps once for each of the strap's beats while [enabled] and while the screen is showing: the
 * loop runs only while the lifecycle is at least STARTED, so leaving the app or switching the
 * screen off silences it, and it starts again from the next beat on return. While it is enabled,
 * the volume keys on this screen set the media volume, which is the one the beep follows.
 *
 * The beeper is driven from a background thread (see [BeatBeeper]); reading the snapshot state
 * from there is safe, as Compose state may be read from any thread.
 *
 * @param onUnavailable called with the reason if the phone cannot open an audio track
 */
@Composable
fun BeatBeeps(snapshot: State<MonitorSnapshot>, enabled: Boolean, onUnavailable: (String?) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val reportUnavailable by rememberUpdatedState(onUnavailable)
    val activity = LocalContext.current.findActivity()
    DisposableEffect(enabled, activity) {
        if (enabled) activity?.volumeControlStream = AudioManager.STREAM_MUSIC
        onDispose { activity?.volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE }
    }
    LaunchedEffect(enabled, lifecycleOwner) {
        reportUnavailable(null)
        if (!enabled) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val beeper = try {
                BeatBeeper()
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "cannot open an audio track for the beat beep", e)
                withContext(Dispatchers.Main) { reportUnavailable(e.message ?: e.toString()) }
                return@withContext
            }
            try {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // Beats already past when the screen came back are not sounded.
                    var signalled = SystemClock.elapsedRealtimeNanos()
                    while (true) {
                        val now = SystemClock.elapsedRealtimeNanos()
                        val due = snapshot.value.beats.lastOrNull { it in (signalled + 1)..now }
                        if (due != null) {
                            if (now - due < LATE_BEEP_NS) beeper.beep()
                            signalled = due
                        }
                        delay(BEEP_POLL_MS)
                    }
                }
            } finally {
                beeper.close()
            }
        }
    }
}

/** The activity behind [this] context, unwrapping any wrappers, or null if there is none. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val TAG = "BeatSignals"
