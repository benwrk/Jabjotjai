package com.benwrk.jabjotjai.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Resumes the capture an app update interrupted. Installing an update ends the app's process, and
 * Android then sends the updated app `ACTION_MY_PACKAGE_REPLACED`, receiving which is listed as an
 * exemption that allows starting a foreground service from the background
 * (developer.android.com/develop/background-work/services/fgs/restrictions-bg-start, read
 * 2026-11-27). Decision `0013`.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Finds nothing if the service has already resumed the capture, or is resuming it, in
        // this process.
        when (val found = CaptureRecovery.find(context)) {
            is Recovery.Found -> {
                Log.i(TAG, "updated; capture to resume: ${found.unclean.capture.session}, by itself: ${found.unclean.resumesByItself}")
                if (found.unclean.resumesByItself) Capture.resume(context, found.unclean.capture)
            }
            // Shown again when the app is next opened, which looks once more.
            is Recovery.Failed -> Log.w(TAG, "updated; cannot tell how the last capture ended: ${found.message}")
            Recovery.None -> Log.i(TAG, "updated; no capture to resume")
        }
    }

    private companion object {
        const val TAG = "UpdateReceiver"
    }
}
