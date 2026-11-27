package com.benwrk.jabjotjai.capture

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.benwrk.jabjotjai.raw.AccRecord
import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.EndRecord
import com.benwrk.jabjotjai.raw.EndingNote
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.ProcessExit
import com.benwrk.jabjotjai.raw.RawCodec
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.RawTail
import com.benwrk.jabjotjai.raw.Stamp
import java.io.File
import java.io.RandomAccessFile

/** The capture that is running, as remembered across the app's process dying (decision `0013`). */
data class RunningCapture(val session: String, val file: File, val sensorId: String, val sensorName: String, val sensorAddress: String)

/** Remembers the running capture in shared preferences, from its start until it ends properly. */
object RunningCaptureStore {
    private const val PREFS = "capture"

    fun save(context: Context, capture: RunningCapture) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("session", capture.session)
            .putString("file", capture.file.path)
            .putString("sensor_id", capture.sensorId)
            .putString("sensor_name", capture.sensorName)
            .putString("sensor_address", capture.sensorAddress)
            // Written at once: the process may be killed a moment later, which is the point.
            .commit()
    }

    fun load(context: Context): RunningCapture? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return RunningCapture(
            session = prefs.getString("session", null) ?: return null,
            file = File(prefs.getString("file", null) ?: return null),
            sensorId = prefs.getString("sensor_id", null) ?: return null,
            sensorName = prefs.getString("sensor_name", null) ?: return null,
            sensorAddress = prefs.getString("sensor_address", null) ?: return null,
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

/**
 * Android's reasons for a process ending (`ApplicationExitInfo.REASON_*`, SDK 37 source, read
 * 2026-11-27), and which of them mean nobody chose the stop, so the capture resumes by itself.
 */
object ExitReasons {
    private val NAMES = listOf(
        "UNKNOWN", "EXIT_SELF", "SIGNALED", "LOW_MEMORY", "CRASH", "CRASH_NATIVE", "ANR",
        "INITIALIZATION_FAILURE", "PERMISSION_CHANGE", "EXCESSIVE_RESOURCE_USAGE", "USER_REQUESTED",
        "USER_STOPPED", "DEPENDENCY_DIED", "OTHER", "FREEZER", "PACKAGE_STATE_CHANGE", "PACKAGE_UPDATED",
    )

    /**
     * The system ending the process for its own reasons, or an update. Everything else (Force
     * stop, the task manager, a crash, a revoked permission, or no record at all) is offered
     * instead: the user chose it, resuming could repeat it, or the cause is unknown.
     */
    private val RESUME_BY_ITSELF = setOf("SIGNALED", "LOW_MEMORY", "EXCESSIVE_RESOURCE_USAGE", "FREEZER", "PACKAGE_UPDATED")

    fun name(code: Int): String = NAMES.getOrNull(code) ?: "UNKNOWN_$code"

    fun resumesByItself(exit: ProcessExit?): Boolean = exit != null && exit.reason in RESUME_BY_ITSELF
}

/** A capture that ended without its `end` record, as noted afterwards. */
data class UncleanEnd(val capture: RunningCapture, val note: EndingNote) {
    val resumesByItself: Boolean get() = ExitReasons.resumesByItself(note.exit)
}

/**
 * Keeps a look for an unfinished capture away from a capture this process is running. Such a look
 * takes the remembered capture for one the app did not end whenever its file has no `end` record,
 * which is just as true while a capture is starting, running or being ended. From [hold], before
 * the capture is remembered, until [release], once it has ended, [unlessHeld] does nothing. One
 * lock covers all three, so a look already under way when [hold] is called finishes before it
 * returns, and the capture is remembered only after.
 */
internal class RecoveryGuard {
    private var held = false

    @Synchronized
    fun hold() {
        held = true
    }

    /** Runs [then], such as forgetting the capture, before a look can see it again. */
    @Synchronized
    fun release(then: () -> Unit = {}) {
        then()
        held = false
    }

    @Synchronized
    fun <T : Any> unlessHeld(look: () -> T?): T? = if (held) null else look()
}

/**
 * Looks, when the app runs again, for a remembered capture whose file has no `end` record, and
 * notes how it ended beside it (`docs/raw-format.md`, "The ending note"). While this process runs
 * a capture, from [hold] to [release], [find] finds nothing.
 */
object CaptureRecovery {
    private const val TAG = "CaptureRecovery"
    private const val TAIL_BYTES = 64 * 1024L

    private val guard = RecoveryGuard()

    fun ending(file: File): File = File(file.parentFile, file.name.removeSuffix(".jsonl") + ".ending.json")

    /** A capture is starting in this process: [find] finds nothing until [release]. */
    fun hold() = guard.hold()

    /**
     * The capture this process ran has ended: forgotten if its `end` record was written ([ended]),
     * otherwise left remembered, for [find] to note and offer as one the app did not end.
     */
    fun release(context: Context, ended: Boolean) = guard.release { if (ended) RunningCaptureStore.clear(context) }

    /**
     * The capture that ended uncleanly, its note written if it was not already; null if there is
     * none, or while this process is running one.
     */
    fun find(context: Context): UncleanEnd? = guard.unlessHeld { look(context) }

    private fun look(context: Context): UncleanEnd? {
        val capture = RunningCaptureStore.load(context) ?: return null
        if (!capture.file.isFile) {
            RunningCaptureStore.clear(context)
            return null
        }
        val lines = lastLines(capture.file)
        if (lines.any { it is RawLine.Record && it.record is EndRecord }) {
            // It ended properly after all, and only forgetting it was cut short.
            RunningCaptureStore.clear(context)
            return null
        }
        val noteFile = ending(capture.file)
        val existing = noteFile.takeIf { it.isFile }?.readText()?.trim()?.let(RawCodec::decodeEnding)
        val note = existing ?: EndingNote(
            session = capture.session,
            notedWallMs = System.currentTimeMillis(),
            lastLine = lines.asReversed().firstNotNullOfOrNull { stampOf(it) },
            exit = lastExit(context, startWallMs = runCatching { RawTail.readHeader(capture.file) }.getOrNull()?.start?.wallMs),
        ).also {
            noteFile.writeText(RawCodec.encode(it) + "\n")
            Log.i(TAG, "noted how ${capture.session} ended: ${it.exit?.reason ?: "no record"}")
        }
        return UncleanEnd(capture, note)
    }

    /** Android's record of the app's last process exit after the capture began, if it kept one. */
    private fun lastExit(context: Context, startWallMs: Long?): ProcessExit? {
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 10)
        val exit = exits.firstOrNull { startWallMs == null || it.timestamp >= startWallMs } ?: return null
        return ProcessExit(ExitReasons.name(exit.reason), exit.reason, exit.timestamp, exit.description)
    }

    /** The complete lines in the last part of the file, decoded; a torn last line is left out. */
    private fun lastLines(file: File): List<RawLine> {
        val text = RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - TAIL_BYTES).coerceAtLeast(0L)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
        val complete = text.substringBeforeLast('\n', missingDelimiterValue = "")
        return complete.split('\n').drop(1).filter { it.isNotBlank() }.map(RawCodec::decode)
    }

    private fun stampOf(line: RawLine): Stamp? = when (val record = (line as? RawLine.Record)?.record) {
        is EventRecord -> Stamp(record.wallMs, record.monoNs)
        is EcgRecord -> Stamp(record.wallMs, record.monoNs)
        is AccRecord -> Stamp(record.wallMs, record.monoNs)
        is HrRecord -> Stamp(record.wallMs, record.monoNs)
        else -> null
    }
}
