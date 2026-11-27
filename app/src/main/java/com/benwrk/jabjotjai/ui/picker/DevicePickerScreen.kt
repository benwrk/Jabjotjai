package com.benwrk.jabjotjai.ui.picker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.ui.theme.MonitorColors

/** Lists the H10 straps in range; picking one starts a capture. */
@Composable
fun DevicePickerScreen(
    viewModel: ScanViewModel,
    failure: String?,
    onPick: (FoundSensor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val found by viewModel.found.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val error by viewModel.error.collectAsState()

    LaunchedEffect(Unit) { viewModel.scan() }

    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.picker_title), style = MaterialTheme.typography.headlineSmall, color = MonitorColors.Text)
        Text(stringResource(R.string.picker_hint), style = MaterialTheme.typography.bodyMedium, color = MonitorColors.Secondary)
        failure?.let { Text(stringResource(R.string.picker_failed, it), color = MonitorColors.Alarm) }
        error?.let { Text(it, color = MonitorColors.Alarm) }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.picker_scanning), color = MonitorColors.Secondary)
            } else {
                Button(onClick = viewModel::scan) { Text(stringResource(R.string.picker_scan)) }
                if (found.isEmpty()) Text(stringResource(R.string.picker_none), color = MonitorColors.Secondary)
            }
        }

        LazyColumn {
            items(found, key = { it.id }) { sensor ->
                ListItem(
                    headlineContent = { Text(sensor.name) },
                    supportingContent = { Text(stringResource(R.string.picker_rssi, sensor.id, sensor.rssiDbm)) },
                    colors = ListItemDefaults.colors(containerColor = MonitorColors.Background),
                    modifier = Modifier.clickable { onPick(sensor) },
                )
            }
        }
    }
}
