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
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.benwrk.jabjotjai.MainActivity
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.monitor.MonitorModel
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.Phone
import com.benwrk.jabjotjai.raw.RAW_SCHEMA
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawTail
import com.benwrk.jabjotjai.raw.RawWriter
import com.benwrk.jabjotjai.raw.Sensor
import com.benwrk.jabjotjai.raw.Stamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
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

    /**
     * Keeps the notification current, on the main thread. Separate from [scope] because a failure
     * there must not end the capture, and [finish] cancels it before taking the notification down,
     * so a late update cannot put it back.
     */
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
        val clock = SystemPhoneClock
        val start = clock.stamp()
        startForeground(
            NOTIFICATION_ID,
            notification(sensorName, start.wallMs, NotificationContent.STARTING),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jabjotjai:capture")
            .apply { acquire() }

        val api = PolarApi.get(this)
        val sessionId = SessionNames.newId(start.wallMs)
        val file = SessionNames.rawFile(File(filesDir, RAW_DIRECTORY), sessionId)
        val header = header(sessionId, start, Sensor(id = sensorId, name = sensorName, address = sensorAddress))

        scope.launch {
            val newSession = CaptureSession(RawWriter.create(file, clock::monoNs))
            newSession.start(header)
            // The file exists from here on, which a tail needs.
            notificationScope.launch { keepNotificationCurrent(file, sensorName, start.wallMs) }
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
        notificationScope.coroutineContext.cancelChildren()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        notificationScope.cancel()
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

    /**
     * Shows what the raw file says, the way the monitor does: by tailing it (`AGENTS.md`, "Live is
     * a reader tailing the raw file"), so a capture that stops writing shows as one. It reads
     * continuously, which keeps the connection and battery events from early in the file. The
     * system "might drop updates" posted "many in less than one second"
     * (developer.android.com/develop/ui/views/notifications/build-notification, read 2026-11-27),
     * so it posts at most once a second, and only when the content has changed.
     *
     * While the screen is off, the heart rate is posted as no value: following it then would mean
     * posting every second all night for nobody, and leaving the last value up would show a stale
     * number on an always-on display and for a moment after the screen wakes. The rest changes
     * rarely and is still posted.
     */
    private suspend fun keepNotificationCurrent(file: File, sensorName: String, startWallMs: Long) {
        val manager = getSystemService(NotificationManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        val model = MonitorModel(keepNs = 0)
        var readFailed = false
        coroutineScope {
            launch {
                try {
                    RawTail(file, pollIntervalMs = NOTIFICATION_TAIL_POLL_MS).lines().collect(model::accept)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "the notification cannot read the raw file", e)
                    readFailed = true
                }
            }
            var shown: NotificationContent? = null
            while (true) {
                val current = NotificationContent.of(model.snapshot(), SystemPhoneClock.monoNs(), readFailed)
                val content = if (power.isInteractive) current else current.copy(bpm = null)
                if (content != shown && running) {
                    manager.notify(NOTIFICATION_ID, notification(sensorName, startWallMs, content))
                    shown = content
                }
                delay(NOTIFICATION_UPDATE_MS)
            }
        }
    }

    private fun notification(sensorName: String, startWallMs: Long, content: NotificationContent): Notification {
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
        val details = if (content.readFailed) {
            getString(R.string.capture_notification_read_error)
        } else {
            listOfNotNull(
                getString(
                    when (content.connection) {
                        RawEvents.CONNECTED -> R.string.monitor_connected
                        RawEvents.CONNECTING -> R.string.monitor_connecting
                        RawEvents.DISCONNECTED -> R.string.monitor_disconnected
                        else -> R.string.monitor_waiting
                    },
                ),
                getString(R.string.monitor_gaps, content.gaps),
                content.batteryPct?.let { getString(R.string.monitor_battery, it) },
            ).joinToString(" · ")
        }
        val icon = content.bpm?.takeUnless { content.readFailed }?.let { HeartRateIcon.create(this, it) }
            ?: Icon.createWithResource(this, R.drawable.ic_stat_capture)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(content.bpm?.let { getString(R.string.capture_notification_hr, it) } ?: getString(R.string.capture_notification_hr_none))
            .setContentText(details)
            .setSubText(sensorName)
            // The system draws the elapsed time itself, counting up from `when`, between updates.
            .setWhen(startWallMs)
            .setShowWhen(true)
            .setUsesChronometer(true)
            // The owner's choice: heart rate shows on the lock screen. PUBLIC is documented as "Show
            // this notification in its entirety on all lockscreens" (Notification.VISIBILITY_PUBLIC,
            // SDK 37 source, read 2026-11-27).
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .apply { requestLiveUpdate(this, content) }
            .build()
    }

    /**
     * Asks for the notification to be a promoted ongoing one (a "Live Update"), which the system
     * may show as a chip in the status bar; the chip's text is the heart rate, or, without one, the
     * elapsed time. Whether it is promoted is the system's and the user's choice: meeting the
     * requirements "does not guarantee" it (`Notification.hasPromotableCharacteristics`, SDK 37
     * source, read 2026-11-27). The request needs Android 16 QPR2 (API 36.1), and the chip text
     * API 36 (`api-versions.xml`, SDK 37).
     *
     * On the test phone (Galaxy S25, Android 17, One UI 9) the notification was promoted (its flags
     * gained `PROMOTED_ONGOING`) and pinned to the top of the shade, but the status bar drew no chip
     * (seen 2026-11-27).
     */
    private fun requestLiveUpdate(builder: Notification.Builder, content: NotificationContent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        builder.setShortCriticalText(content.bpm?.takeUnless { content.readFailed }?.let { getString(R.string.capture_notification_chip, it) })
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) builder.setRequestPromotedOngoing(true)
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
        private const val NOTIFICATION_UPDATE_MS = 1_000L

        /** Slower than the monitor's tail: the notification changes at most once a second. */
        private const val NOTIFICATION_TAIL_POLL_MS = 250L

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
