package com.benwrk.jabjotjai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.ui.theme.MonitorColors

/** Needed to find and connect to the strap; without them nothing else can work. */
private val REQUIRED = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

/** Asked for together with [REQUIRED], but not required: without it the capture notification is hidden. */
private val REQUESTED = REQUIRED + Manifest.permission.POST_NOTIFICATIONS

private fun hasRequired(context: Context): Boolean =
    REQUIRED.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Shows [content] once the Bluetooth permissions are granted, and asks for them until then. */
@Composable
fun BluetoothPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasRequired(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasRequired(context)
    }
    if (granted) {
        content()
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall, color = MonitorColors.Text)
        Text(stringResource(R.string.permission_body), color = MonitorColors.Secondary)
        Button(onClick = { launcher.launch(REQUESTED) }) { Text(stringResource(R.string.permission_grant)) }
        // Once a permission has been refused twice the system stops asking, and only Settings can grant it.
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text(stringResource(R.string.permission_settings)) }
    }
}
