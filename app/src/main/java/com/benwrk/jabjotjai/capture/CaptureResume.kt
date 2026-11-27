package com.benwrk.jabjotjai.capture

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.benwrk.jabjotjai.raw.AccRecord
import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.EndRecord
import com.benwrk.jabjotjai.raw.EndingNote
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.ProcessExit
import com.benwrk.jabjotjai.raw.RawCodec
import com.benwrk.jabjotjai.raw.RawDecoders
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.RawSchema
import com.benwrk.jabjotjai.raw.RawTail
import com.benwrk.jabjotjai.raw.Stamp
import com.benwrk.jabjotjai.raw.UnreadableRawFileException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** The capture that is running, as remembered across the app's process dying (decision `0013`). */
data class RunningCapture(val session: String, val file: File, val sensorId: String, val sensorName: String, val sensorAddress: String)

/** Remembers the running capture in shared preferences, from its start until it ends properly. */
object RunningCaptureStore {
    private const val PREFS = "capture"

    fun save(context: Context, capture: RunningCapture) {
        // Written at once (commit, not apply): the process may be killed a moment later, which is
        // the point.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putString("session", capture.session)
            putString("file", capture.file.path)
            putString("sensor_id", capture.sensorId)
            putString("sensor_name", capture.sensorName)
            putString("sensor_address", capture.sensorAddress)
        }
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { clear() }
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

/** What looking for a capture the app did not end found. */
sealed interface Recovery {
    /** No remembered capture was left unfinished, or this process is running it. */
    data object None : Recovery

    /** A capture that ended without its `end` record, its note written. */
    data class Found(val unclean: UncleanEnd) : Recovery

    /** Whether the remembered capture ended could not be told: [message] says what could not be read or written. */
    data class Failed(val message: String) : Recovery
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
     * The capture that ended uncleanly, its note written if it was not already; [Recovery.None] if
     * there is none, or while this process is running one; [Recovery.Failed] if its file or note
     * could not be read or written, so whether it ended cannot be told.
     */
    fun find(context: Context): Recovery = guard.unlessHeld { look(context) } ?: Recovery.None

    private fun look(context: Context): Recovery {
        val capture = RunningCaptureStore.load(context) ?: return Recovery.None
        if (!capture.file.isFile) {
            // Remembered, but stopped before its file was made: nothing was recorded.
            RunningCaptureStore.clear(context)
            return Recovery.None
        }
        return when (val inspected = inspect(capture.file, capture.session, System.currentTimeMillis()) { lastExit(context, it) }) {
            Inspection.Ended -> {
                // It ended properly after all, and only forgetting it was cut short.
                RunningCaptureStore.clear(context)
                Recovery.None
            }
            is Inspection.Unclean -> {
                Log.i(TAG, "${capture.session} ended without its end record: ${inspected.note.exit?.reason ?: "no exit record"}")
                Recovery.Found(UncleanEnd(capture, inspected.note))
            }
            is Inspection.Failed -> {
                Log.w(TAG, "cannot tell how ${capture.session} ended: ${inspected.message}")
                Recovery.Failed(inspected.message)
            }
        }
    }

    /** What [inspect] found in a remembered capture's file. */
    internal sealed interface Inspection {
        data object Ended : Inspection
        data class Unclean(val note: EndingNote) : Inspection
        data class Failed(val message: String) : Inspection
    }

    /**
     * Whether [file] ended with its `end` record, and if not, the ending note beside it: the one
     * already there, or a new one written now with [exitAfter]'s record of how the process ended
     * after the capture's start. The last lines are decoded as the file's header says (decision
     * `0006`). A header, file or note that cannot be read, or a note that cannot be written, is a
     * failure; an existing note is never written over.
     */
    internal fun inspect(file: File, session: String, notedWallMs: Long, exitAfter: (startWallMs: Long?) -> ProcessExit?): Inspection = try {
        val header = RawTail.readHeader(file)
        val lines = if (header == null) emptyList() else lastLines(file, decoderFor(header, file))
        val noteFile = ending(file)
        when {
            lines.any { it is RawLine.Record && it.record is EndRecord } -> Inspection.Ended
            noteFile.isFile -> RawCodec.decodeEnding(noteFile.readText().trim())?.let { Inspection.Unclean(it) }
                ?: Inspection.Failed("the ending note ${noteFile.name} cannot be read")
            else -> {
                val note = EndingNote(
                    session = session,
                    notedWallMs = notedWallMs,
                    lastLine = lines.asReversed().firstNotNullOfOrNull { stampOf(it) },
                    exit = exitAfter(header?.start?.wallMs),
                )
                noteFile.writeText(RawCodec.encode(note) + "\n")
                Inspection.Unclean(note)
            }
        }
    } catch (e: IOException) {
        Inspection.Failed(e.message ?: e.toString())
    }

    private fun decoderFor(header: HeaderRecord, file: File): (String) -> RawLine =
        RawSchema.parse(header.schema)?.let(RawDecoders::forSchema)
            ?: throw UnreadableRawFileException("$file declares schema ${header.schema}, which this build cannot read")

    /** Android's record of the app's last process exit after the capture began, if it kept one. */
    private fun lastExit(context: Context, startWallMs: Long?): ProcessExit? {
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 10)
        val exit = exits.firstOrNull { startWallMs == null || it.timestamp >= startWallMs } ?: return null
        return ProcessExit(ExitReasons.name(exit.reason), exit.reason, exit.timestamp, exit.description)
    }

    /** The complete lines in the last part of the file, decoded; a torn last line is left out. */
    private fun lastLines(file: File, decode: (String) -> RawLine): List<RawLine> {
        val text = RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - TAIL_BYTES).coerceAtLeast(0L)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
        val complete = text.substringBeforeLast('\n', missingDelimiterValue = "")
        // The first line read may be the end of one that started earlier, so it is left out.
        return complete.split('\n').drop(1).filter { it.isNotBlank() }.map(decode)
    }

    private fun stampOf(line: RawLine): Stamp? = when (val record = (line as? RawLine.Record)?.record) {
        is EventRecord -> Stamp(record.wallMs, record.monoNs)
        is EcgRecord -> Stamp(record.wallMs, record.monoNs)
        is AccRecord -> Stamp(record.wallMs, record.monoNs)
        is HrRecord -> Stamp(record.wallMs, record.monoNs)
        else -> null
    }
}
