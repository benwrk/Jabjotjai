package com.benwrk.jabjotjai

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.benwrk.jabjotjai.capture.Capture
import com.benwrk.jabjotjai.capture.CaptureRecovery
import com.benwrk.jabjotjai.capture.CaptureState
import com.benwrk.jabjotjai.capture.Recovery
import com.benwrk.jabjotjai.capture.RunningCaptureStore
import com.benwrk.jabjotjai.monitor.MonitorViewModel
import com.benwrk.jabjotjai.ui.BluetoothPermissionGate
import com.benwrk.jabjotjai.ui.monitor.MonitorScreen
import com.benwrk.jabjotjai.ui.monitor.StopQuestion
import com.benwrk.jabjotjai.ui.picker.DevicePickerScreen
import com.benwrk.jabjotjai.ui.picker.RecoveryFailure
import com.benwrk.jabjotjai.ui.picker.ResumeOffer
import com.benwrk.jabjotjai.ui.picker.ScanViewModel
import com.benwrk.jabjotjai.ui.theme.JabjotjaiTheme
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only screen host. It shows the monitor while a capture runs and the strap picker otherwise;
 * the capture itself runs in [com.benwrk.jabjotjai.capture.CaptureService] and does not depend on
 * this activity existing.
 */
class MainActivity : ComponentActivity() {

    private val scanViewModel: ScanViewModel by viewModels()
    private val monitorViewModel: MonitorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JabjotjaiTheme(darkTheme = true, dynamicColor = false) {
                App(scanViewModel, monitorViewModel)
            }
        }
        askToStopIf(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        askToStopIf(intent)
    }

    /**
     * The notification's Stop opens the app with [EXTRA_ASK_TO_STOP], so the monitor asks before
     * stopping, as its own stop does. Only while a capture runs: there is nothing to stop otherwise.
     */
    private fun askToStopIf(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ASK_TO_STOP, false) != true) return
        intent.removeExtra(EXTRA_ASK_TO_STOP)
        if (Capture.state.value is CaptureState.Running) StopQuestion.ask()
    }

    companion object {
        const val EXTRA_ASK_TO_STOP = "com.benwrk.jabjotjai.extra.ASK_TO_STOP"
    }
}

@Composable
private fun App(scanViewModel: ScanViewModel, monitorViewModel: MonitorViewModel) {
    val context = LocalContext.current
    val state by Capture.state.collectAsState()
    // The monitor keeps its bars clear of the system bars and the camera cut-out itself, so that its
    // chart can reach under the cut-out (see MonitorScreen); every other screen keeps clear of both.
    Scaffold(containerColor = MonitorColors.Background, contentWindowInsets = WindowInsets(0)) {
        val insets = if (state is CaptureState.Running) Modifier else Modifier.windowInsetsPadding(ScaffoldDefaults.contentWindowInsets)
        Box(Modifier.fillMaxSize().then(insets)) {
            BluetoothPermissionGate {
                when (val current = state) {
                    is CaptureState.Running -> MonitorScreen(
                        viewModel = monitorViewModel,
                        running = current,
                        onStop = { Capture.stop(context) },
                    )
                    else -> Column {
                        // A capture the app did not end, which nothing resumed by itself: offer it,
                        // or say that whether it ended could not be told.
                        var recovery by remember { mutableStateOf<Recovery>(Recovery.None) }
                        LaunchedEffect(Unit) { recovery = withContext(Dispatchers.IO) { CaptureRecovery.find(context) } }
                        when (val found = recovery) {
                            is Recovery.Found -> ResumeOffer(
                                unclean = found.unclean,
                                onResume = {
                                    recovery = Recovery.None
                                    scanViewModel.stopScan()
                                    Capture.resume(context, found.unclean.capture)
                                },
                                onDismiss = {
                                    recovery = Recovery.None
                                    RunningCaptureStore.clear(context)
                                },
                                modifier = Modifier.padding(24.dp),
                            )
                            is Recovery.Failed -> RecoveryFailure(
                                message = found.message,
                                onDismiss = {
                                    recovery = Recovery.None
                                    RunningCaptureStore.clear(context)
                                },
                                modifier = Modifier.padding(24.dp),
                            )
                            Recovery.None -> Unit
                        }
                        DevicePickerScreen(
                            viewModel = scanViewModel,
                            failure = (current as? CaptureState.Failed)?.message,
                            onPick = { sensor ->
                                scanViewModel.stopScan()
                                Capture.start(context, sensor.id, sensor.name, sensor.address)
                            },
                        )
                    }
                }
            }
        }
    }
}
