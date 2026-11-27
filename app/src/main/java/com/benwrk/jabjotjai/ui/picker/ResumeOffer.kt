package com.benwrk.jabjotjai.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.capture.UncleanEnd
import com.benwrk.jabjotjai.ui.theme.MonitorColors
import java.text.DateFormat
import java.util.Date

/**
 * Offers to resume a capture the app did not end, when nothing resumed it by itself: after Force
 * stop, the task manager, a crash, or a restart Android refused (decision `0013`). Resuming starts
 * a new file that names the stopped capture as the one it continues.
 */
@Composable
fun ResumeOffer(unclean: UncleanEnd, onResume: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val stoppedAt = unclean.note.lastLine?.wallMs?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
    val why = stringResource(
        when (unclean.note.exit?.reason) {
            "LOW_MEMORY", "EXCESSIVE_RESOURCE_USAGE", "FREEZER", "SIGNALED" -> R.string.resume_why_system
            "PACKAGE_UPDATED" -> R.string.resume_why_updated
            "USER_REQUESTED" -> R.string.resume_why_force_stopped
            "USER_STOPPED" -> R.string.resume_why_task_manager
            "CRASH", "CRASH_NATIVE", "ANR" -> R.string.resume_why_crashed
            else -> R.string.resume_why_unknown
        },
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.resume_title), style = MaterialTheme.typography.titleMedium, color = MonitorColors.Warning)
        Text(
            if (stoppedAt != null) {
                stringResource(R.string.resume_body_at, unclean.capture.sensorName, stoppedAt, why)
            } else {
                stringResource(R.string.resume_body, unclean.capture.sensorName, why)
            },
            color = MonitorColors.Text,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onResume) { Text(stringResource(R.string.resume_resume)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.resume_dismiss)) }
        }
    }
}

/**
 * Says that whether the last capture ended could not be told, with [message] saying why. Dismissing
 * forgets that capture, as dismissing the offer to resume does; until then each opening looks again.
 */
@Composable
fun RecoveryFailure(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.recovery_failed_title), style = MaterialTheme.typography.titleMedium, color = MonitorColors.Warning)
        Text(stringResource(R.string.recovery_failed_body, message), color = MonitorColors.Text)
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.resume_dismiss)) }
    }
}
