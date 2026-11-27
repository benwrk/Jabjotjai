package com.benwrk.jabjotjai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
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
import com.benwrk.jabjotjai.capture.CaptureState
import com.benwrk.jabjotjai.capture.CaptureRecovery
import com.benwrk.jabjotjai.capture.RunningCaptureStore
import com.benwrk.jabjotjai.capture.UncleanEnd
import com.benwrk.jabjotjai.monitor.MonitorViewModel
import com.benwrk.jabjotjai.ui.BluetoothPermissionGate
import com.benwrk.jabjotjai.ui.monitor.MonitorScreen
import com.benwrk.jabjotjai.ui.picker.DevicePickerScreen
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
    }
}

@Composable
private fun App(scanViewModel: ScanViewModel, monitorViewModel: MonitorViewModel) {
    val context = LocalContext.current
    val state by Capture.state.collectAsState()
    Scaffold(containerColor = MonitorColors.Background) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            BluetoothPermissionGate {
                when (val current = state) {
                    is CaptureState.Running -> MonitorScreen(
                        viewModel = monitorViewModel,
                        running = current,
                        onStop = { Capture.stop(context) },
                    )
                    else -> Column {
                        // A capture the app did not end, which nothing resumed by itself: offer it.
                        var unclean by remember { mutableStateOf<UncleanEnd?>(null) }
                        LaunchedEffect(Unit) { unclean = withContext(Dispatchers.IO) { CaptureRecovery.find(context) } }
                        unclean?.let { stopped ->
                            ResumeOffer(
                                unclean = stopped,
                                onResume = {
                                    unclean = null
                                    scanViewModel.stopScan()
                                    Capture.resume(context, stopped.capture)
                                },
                                onDismiss = {
                                    unclean = null
                                    RunningCaptureStore.clear(context)
                                },
                                modifier = Modifier.padding(24.dp),
                            )
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
