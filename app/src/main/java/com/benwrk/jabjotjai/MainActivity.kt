package com.benwrk.jabjotjai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.benwrk.jabjotjai.capture.Capture
import com.benwrk.jabjotjai.capture.CaptureState
import com.benwrk.jabjotjai.monitor.MonitorViewModel
import com.benwrk.jabjotjai.ui.BluetoothPermissionGate
import com.benwrk.jabjotjai.ui.monitor.MonitorScreen
import com.benwrk.jabjotjai.ui.picker.DevicePickerScreen
import com.benwrk.jabjotjai.ui.picker.ScanViewModel
import com.benwrk.jabjotjai.ui.theme.JabjotjaiTheme
import com.benwrk.jabjotjai.ui.theme.MonitorColors

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
                    else -> DevicePickerScreen(
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
