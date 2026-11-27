package com.benwrk.jabjotjai.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.benwrk.jabjotjai.MainActivity
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.Phone
import com.benwrk.jabjotjai.raw.RAW_SCHEMA
import com.benwrk.jabjotjai.raw.RawWriter
import com.benwrk.jabjotjai.raw.Sensor
import com.benwrk.jabjotjai.raw.Stamp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs one capture in the foreground, independent of any screen: it connects to the strap,
 * writes the raw file, and keeps the CPU awake while it does. Type `connectedDevice`, whose
 * runtime prerequisite is a granted Bluetooth permission
 * (developer.android.com/develop/background-work/services/fgs/service-types, read 2026-11-27).
 */
class CaptureService : Service() {

    /** The capture thread: every session and SDK-facing call runs here, in order. */
    private val captureExecutor = Executors.newSingleThreadExecutor { Thread(it, "jabjotjai-capture") }
    private val captureDispatcher = captureExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(
        SupervisorJob() + captureDispatcher + CoroutineExceptionHandler { _, error -> fail(error) },
    )

    // Touched only on the main thread.
    private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    // Touched only on the capture thread.
    private var session: CaptureSession? = null
    private var link: PolarH10Link? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                sensorId = requireNotNull(intent.getStringExtra(EXTRA_SENSOR_ID)),
                sensorName = requireNotNull(intent.getStringExtra(EXTRA_SENSOR_NAME)),
                sensorAddress = requireNotNull(intent.getStringExtra(EXTRA_SENSOR_ADDRESS)),
            )
            ACTION_STOP -> stop(STOP_BY_USER)
            else -> if (!running) stopSelf(startId)
        }
        // A capture is not resumed after the process dies: 0.1 has no way to continue a session.
        return START_NOT_STICKY
    }

    private fun start(sensorId: String, sensorName: String, sensorAddress: String) {
        if (running) return
        running = true
        startForeground(NOTIFICATION_ID, notification(sensorName), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jabjotjai:capture")
            .apply { acquire() }

        val api = PolarApi.get(this)
        val clock = SystemPhoneClock
        val start = clock.stamp()
        val sessionId = SessionNames.newId(start.wallMs)
        val file = SessionNames.rawFile(File(filesDir, RAW_DIRECTORY), sessionId)
        val header = header(sessionId, start, Sensor(id = sensorId, name = sensorName, address = sensorAddress))

        scope.launch {
            val newSession = CaptureSession(RawWriter.create(file, clock::monoNs))
            newSession.start(header)
            session = newSession
            link = PolarH10Link(api, sensorId, sensorAddress, newSession, clock, scope).also { it.connect() }
            Capture.publish(CaptureState.Running(file, sensorId, sensorName))
            while (isActive && !newSession.ended) {
                delay(SYNC_TICK_MS)
                newSession.syncIfDue()
            }
        }
    }

    private fun stop(reason: String) {
        if (!running) {
            stopSelf()
            return
        }
        running = false
        scope.launch {
            endCapture(reason)
            Capture.publish(CaptureState.Idle)
            withContext(Dispatchers.Main) { finish() }
        }
    }

    /** Runs on the capture thread. */
    private suspend fun endCapture(reason: String) {
        val currentLink = link
        val currentSession = session
        link = null
        session = null
        if (currentLink != null) {
            currentLink.stop(reason)
        } else if (currentSession != null && !currentSession.ended) {
            currentSession.end(SystemPhoneClock.stamp(), reason)
        }
    }

    /** Any failure on the capture thread ends the capture, loudly: it is published, never swallowed. */
    private fun fail(error: Throwable) {
        Log.e(TAG, "capture failed", error)
        Capture.publish(CaptureState.Failed(error.message ?: error.toString()))
        scope.launch {
            try {
                endCapture(STOP_ON_ERROR)
            } catch (e: Exception) {
                Log.e(TAG, "could not end the session after a failure", e)
            }
            withContext(Dispatchers.Main) {
                running = false
                finish()
            }
        }
    }

    private fun finish() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) {
            // The system is destroying the service mid-capture: end the file properly before the
            // capture thread goes away.
            running = false
            runBlocking(captureDispatcher) { endCapture(STOP_SERVICE_DESTROYED) }
            Capture.publish(CaptureState.Idle)
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        scope.cancel()
        captureExecutor.shutdown()
        super.onDestroy()
    }

    private fun header(sessionId: String, start: Stamp, sensor: Sensor): HeaderRecord {
        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        return HeaderRecord(
            schema = RAW_SCHEMA,
            session = sessionId,
            app = "${packageInfo.versionName} (${packageInfo.longVersionCode})",
            sdk = PolarApi.sdkVersion,
            phone = Phone(make = Build.MANUFACTURER, model = Build.MODEL, android = Build.VERSION.RELEASE, api = Build.VERSION.SDK_INT),
            start = start,
            sensor = sensor,
            sensorEpoch = "2000-01-01",
        )
    }

    private fun notification(sensorName: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(this, 1, stopIntent(this), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_capture)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(sensorName)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val ACTION_START = "com.benwrk.jabjotjai.action.START_CAPTURE"
        private const val ACTION_STOP = "com.benwrk.jabjotjai.action.STOP_CAPTURE"
        private const val EXTRA_SENSOR_ID = "sensor_id"
        private const val EXTRA_SENSOR_NAME = "sensor_name"
        private const val EXTRA_SENSOR_ADDRESS = "sensor_address"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val SYNC_TICK_MS = 1_000L

        /** Raw files live in `files/raw/` (`docs/raw-format.md`). */
        const val RAW_DIRECTORY = "raw"

        const val STOP_BY_USER = "user_stop"
        const val STOP_ON_ERROR = "error"
        const val STOP_SERVICE_DESTROYED = "service_destroyed"

        fun startIntent(context: Context, sensorId: String, sensorName: String, sensorAddress: String): Intent =
            Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SENSOR_ID, sensorId)
                .putExtra(EXTRA_SENSOR_NAME, sensorName)
                .putExtra(EXTRA_SENSOR_ADDRESS, sensorAddress)

        fun stopIntent(context: Context): Intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}
