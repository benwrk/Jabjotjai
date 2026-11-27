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
import android.os.SystemClock
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.benwrk.jabjotjai.MainActivity
import com.benwrk.jabjotjai.R
import com.benwrk.jabjotjai.monitor.CaptureStateAt
import com.benwrk.jabjotjai.monitor.CaptureTimeline
import com.benwrk.jabjotjai.monitor.DisplaySettingsStore
import com.benwrk.jabjotjai.monitor.EarlierTrends
import com.benwrk.jabjotjai.monitor.MonitorModel
import com.benwrk.jabjotjai.monitor.TrendSnapshot
import com.benwrk.jabjotjai.monitor.beepAtBeats
import com.benwrk.jabjotjai.monitor.beepGain
import com.benwrk.jabjotjai.monitor.readEarlierTrends
import com.benwrk.jabjotjai.monitor.timeMonoNs
import com.benwrk.jabjotjai.raw.AccRecord
import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.Phone
import com.benwrk.jabjotjai.raw.RAW_SCHEMA
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawLine
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
     * Everything that follows the capture rather than making it: the reader behind [Capture.live],
     * the notification and the beat beep, on the main thread unless they move off it. Separate from
     * [scope] because a failure here must not end the capture: it is logged, and the rest carries
     * on. [finish] cancels it before taking the notification down, so a late update cannot put it
     * back.
     */
    private val liveScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> Log.e(TAG, "live reading failed", error) },
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
                continues = intent.getStringExtra(EXTRA_CONTINUES),
            )
            ACTION_STOP -> stop(STOP_BY_USER)
            // A null intent is Android re-creating the service after killing its process
            // (START_STICKY, below): resume if nobody chose that stop (decision 0013).
            null -> if (!running) resumeAfterRestart(startId)
            else -> if (!running) stopSelf(startId)
        }
        // Re-created if its process is killed while it is started (Service.START_STICKY).
        return START_STICKY
    }

    private fun resumeAfterRestart(startId: Int) {
        val unclean = CaptureRecovery.find(this)
        if (unclean == null || !unclean.resumesByItself) {
            // Nothing to resume, or something the user is asked about when they next open the app.
            stopSelf(startId)
            return
        }
        val previous = unclean.capture
        start(previous.sensorId, previous.sensorName, previous.sensorAddress, continues = previous.session)
    }

    /** @param continues the session this capture resumes, if it resumes one */
    private fun start(sensorId: String, sensorName: String, sensorAddress: String, continues: String? = null) {
        if (running) return
        running = true
        DisplaySettingsStore.init(this)
        val clock = SystemPhoneClock
        val start = clock.stamp()
        try {
            startForeground(
                NOTIFICATION_ID,
                notification(NotificationContent.STARTING),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: IllegalStateException) {
            // Android refused to start it in the foreground from the background, which battery
            // optimisation may cause. The capture stays remembered, so the app offers to resume
            // it when next opened (decision 0013).
            Log.w(TAG, "could not start in the foreground", e)
            running = false
            stopSelf()
            return
        }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jabjotjai:capture")
            .apply { acquire() }

        val api = PolarApi.get(this)
        val sessionId = SessionNames.newId(start.wallMs)
        val file = SessionNames.rawFile(File(filesDir, RAW_DIRECTORY), sessionId)
        val header = header(sessionId, start, Sensor(id = sensorId, name = sensorName, address = sensorAddress), continues)
        RunningCaptureStore.save(this, RunningCapture(sessionId, file, sensorId, sensorName, sensorAddress))

        scope.launch {
            val newSession = CaptureSession(RawWriter.create(file, clock::monoNs))
            newSession.start(header)
            // The file exists from here on, which a tail needs.
            liveScope.launch { readLive(file) }
            liveScope.launch { keepNotificationCurrent() }
            liveScope.launch { beepWhileWanted() }
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
        // Ended with its `end` record: nothing to note or resume.
        RunningCaptureStore.clear(this)
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
        liveScope.coroutineContext.cancelChildren()
        Capture.publishLive(LiveReading.EMPTY)
        Capture.publishBeepError(null)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        liveScope.cancel()
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

    private fun header(sessionId: String, start: Stamp, sensor: Sensor, continues: String?): HeaderRecord {
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
            continues = continues,
        )
    }

    /**
     * Reads the raw file from its first line, as it grows, and publishes what it has read as
     * [Capture.live]: the one reader behind the notification, the beat beep, and the monitor's
     * connection, heart rate, battery, gaps and beats (`AGENTS.md`, "Live is a reader tailing the
     * raw file"). Reading the file, not the capture, means a capture that stops writing shows as
     * one. Frames are not published one by one, since nothing that reads [Capture.live] draws them.
     */
    /**
     * Reads the heart-rate and RR history of the files [file]'s capture continues, in the
     * background, publishing its progress and then the history (see [EarlierTrends]); the
     * capture's own reading, the monitor and the notification never wait for it. It stops when the
     * capture's reading stops, and all it publishes is tagged with the capture's session, so a late
     * result cannot stand for another capture. A failure is logged and published as an earlier
     * recording that could not be read, never thrown into the capture's own reading.
     */
    private suspend fun readEarlierTrendsOf(file: File) {
        val header = runCatching { RawTail.readHeader(file) }.getOrNull() ?: return
        val dir = file.parentFile ?: return
        val session = header.session
        val context = currentCoroutineContext()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val earlier = MonitorModel(keepNs = 0)
        try {
            val read = readEarlierTrends(
                earlier,
                dir,
                header,
                onProgress = { fraction -> if (context.isActive) Capture.publishEarlierTrends(EarlierTrends.Reading(session, fraction)) },
                keepGoing = { context.isActive },
            )
            if (!read.finished || !context.isActive) return
            if (read.files == 0) {
                Capture.publishEarlierTrends(EarlierTrends.None)
                return
            }
            val ms = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000
            Log.i(TAG, "read the trends of ${read.files} earlier files, ${read.bytes / 1_000_000} MB, in $ms ms; ${read.unreadable} could not be read")
            val snapshot = earlier.snapshot()
            Capture.publishEarlierTrends(EarlierTrends.Read(session, snapshot.heartRateTrend, snapshot.rrTrend, read.unreadable, read.read))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cannot read the trends of the files this capture continues", e)
            if (context.isActive) Capture.publishEarlierTrends(EarlierTrends.Read(session, TrendSnapshot.EMPTY, TrendSnapshot.EMPTY, unreadable = 1))
        }
    }

    private suspend fun readLive(file: File) = coroutineScope {
        // The heart-rate and RR history of the files this capture continues, read alongside (0.6).
        Capture.publishEarlierTrends(EarlierTrends.None)
        launch(Dispatchers.IO) { readEarlierTrendsOf(file) }
        val model = MonitorModel(keepNs = 0)
        // Mapped as it is read, so the monitor can jump back to any moment (see CaptureTimeline).
        val timeline = CaptureTimeline()
        Capture.publishLive(LiveReading.EMPTY.copy(timeline = timeline))
        try {
            RawTail(file, pollIntervalMs = LIVE_TAIL_POLL_MS).linesWithOffsets().collect { (offset, line) ->
                model.accept(line)
                val record = (line as? RawLine.Record)?.record ?: return@collect
                val time = record.timeMonoNs()
                timeline.addLine(offset, time)
                if (record !is EcgRecord && record !is AccRecord) {
                    val snapshot = model.snapshot()
                    timeline.addState(time, CaptureStateAt(snapshot.connection, snapshot.batteryPercentage, snapshot.gaps))
                    Capture.publishLive(LiveReading(snapshot, readError = null, timeline = timeline))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "cannot read the raw file", e)
            Capture.publishLive(LiveReading(model.snapshot(), readError = e.message ?: e.toString(), timeline = timeline))
        }
    }

    /**
     * Keeps the notification showing [Capture.live]. The system "might drop updates" posted "many
     * in less than one second" (developer.android.com/develop/ui/views/notifications/build-notification,
     * read 2026-11-27), so it posts at most once a second, and only when the content has changed;
     * see [NotificationContent.of] for what is left out while the screen is off.
     */
    private suspend fun keepNotificationCurrent() {
        val manager = getSystemService(NotificationManager::class.java)
        val power = getSystemService(PowerManager::class.java)
        var shown: NotificationContent? = null
        while (true) {
            val live = Capture.live.value
            val content = NotificationContent.of(live.snapshot, SystemPhoneClock.monoNs(), live.readError != null, power.isInteractive)
            if (content != shown && running) {
                manager.notify(NOTIFICATION_ID, notification(content))
                shown = content
            }
            delay(NOTIFICATION_UPDATE_MS)
        }
    }

    /**
     * Sounds the beat beep while the user wants it: with the beep on, while the monitor is on
     * screen, and also when it is not if they chose to keep it going. It lives here rather than in
     * the screen so that it can go on with the app in the background. Its calls block, so it runs
     * off the main thread; any failure stops the beep, is shown on the monitor, and leaves the
     * capture alone.
     */
    private suspend fun beepWhileWanted() {
        combine(DisplaySettingsStore.settings, Capture.monitorVisible) { settings, visible ->
            settings.beep && (visible || settings.beepInBackground)
        }.distinctUntilChanged().collectLatest { wanted ->
            Capture.publishBeepError(null)
            if (!wanted) return@collectLatest
            try {
                withContext(Dispatchers.IO) {
                    beepAtBeats(
                        beats = { Capture.replayBeats.value ?: Capture.live.value.snapshot.beats },
                        gain = { DisplaySettingsStore.settings.value.beepGain },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the beat beep stopped", e)
                Capture.publishBeepError(e.message ?: e.toString())
            }
        }
    }

    private fun notification(content: NotificationContent): Notification {
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
        // Stop opens the app to ask first, as the monitor's own stop does, so a stray tap in the
        // notification shade cannot end a recording.
        val stop = PendingIntent.getActivity(
            this,
            STOP_REQUEST_CODE,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_ASK_TO_STOP, true),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val connection = getString(
            when (content.connection) {
                RawEvents.CONNECTED -> R.string.monitor_connected
                RawEvents.CONNECTING -> R.string.monitor_connecting
                RawEvents.DISCONNECTED -> R.string.monitor_disconnected
                else -> R.string.monitor_waiting
            },
        )
        val heartRate = content.bpm?.let { getString(R.string.capture_notification_hr, it) } ?: getString(R.string.capture_notification_hr_none)
        val details = if (content.readFailed) {
            getString(R.string.capture_notification_read_error)
        } else {
            listOfNotNull(
                getString(R.string.monitor_gaps, content.gaps),
                content.batteryPercentage?.let { getString(R.string.monitor_battery, it) },
                getString(
                    R.string.capture_notification_contact,
                    getString(
                        when (content.contact) {
                            true -> R.string.monitor_contact_ok_short
                            false -> R.string.monitor_contact_off_short
                            null -> R.string.monitor_no_value
                        },
                    ),
                ),
            ).joinToString(" · ")
        }
        val icon = content.bpm?.takeUnless { content.readFailed }?.let { HeartRateIcon.create(this, it) }
            ?: Icon.createWithResource(this, R.drawable.ic_stat_capture)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(listOfNotNull(heartRate, content.rrMs?.let { getString(R.string.capture_notification_rr, it) }, connection).joinToString(" · "))
            .setContentText(details)
            // The elapsed time is part of the text instead of the system's chronometer: One UI drew
            // that against the end of the title, as "HR 72 bpm06:52" (seen 2026-11-27).
            .setShowWhen(false)
            // `when` is the time of what the notification shows, not the capture's start. One UI's
            // default "Sort notifications: By time" puts the newest first, and with the start time
            // every later notification sorted ahead of this one, until its icon fell behind the
            // three the status bar shows (seen 2026-11-27).
            .setWhen(SystemPhoneClock.wallMs())
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
     * may show as a chip in the status bar, with the heart rate as its text. Whether it is promoted
     * is the system's and the user's choice: meeting the
     * requirements "does not guarantee" it (`Notification.hasPromotableCharacteristics`, SDK 37
     * source, read 2026-11-27). The request needs Android 16 QPR2 (API 36.1), and the chip text
     * API 36 (`api-versions.xml`, SDK 37).
     *
     * On the test phone (Galaxy S25, Android 17, One UI 9) the notification was promoted (its flags
     * gained `PROMOTED_ONGOING`) and shown in the shade open, with its Stop action, where without
     * promotion it was folded to one line; it was not always first, and the status bar drew no chip
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
        private const val STOP_REQUEST_CODE = 1
        private const val EXTRA_SENSOR_ID = "sensor_id"
        private const val EXTRA_SENSOR_NAME = "sensor_name"
        private const val EXTRA_SENSOR_ADDRESS = "sensor_address"
        private const val EXTRA_CONTINUES = "continues"
        private const val CHANNEL_ID = "capture"
        private const val NOTIFICATION_ID = 1
        private const val SYNC_TICK_MS = 1_000L
        private const val NOTIFICATION_UPDATE_MS = 1_000L

        /**
         * How often the live reader looks for new lines. A beat reported after its slot is sounded
         * when read, and is passed over if read more than 150 ms late, so this stays well under that.
         */
        private const val LIVE_TAIL_POLL_MS = 50L

        /** Raw files live in `files/raw/` (`docs/raw-format.md`). */
        const val RAW_DIRECTORY = "raw"

        const val STOP_BY_USER = "user_stop"
        const val STOP_ON_ERROR = "error"
        const val STOP_SERVICE_DESTROYED = "service_destroyed"

        fun startIntent(context: Context, sensorId: String, sensorName: String, sensorAddress: String, continues: String? = null): Intent =
            Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SENSOR_ID, sensorId)
                .putExtra(EXTRA_SENSOR_NAME, sensorName)
                .putExtra(EXTRA_SENSOR_ADDRESS, sensorAddress)
                .putExtra(EXTRA_CONTINUES, continues)

        fun stopIntent(context: Context): Intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
    }
}
