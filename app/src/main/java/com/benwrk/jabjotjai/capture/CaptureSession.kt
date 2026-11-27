package com.benwrk.jabjotjai.capture

import com.benwrk.jabjotjai.raw.AccRecord
import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.EndRecord
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.GapRecord
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawRecord
import com.benwrk.jabjotjai.raw.RawStreams
import com.benwrk.jabjotjai.raw.RawWriter
import com.benwrk.jabjotjai.raw.Stamp

/**
 * Turns what happens during one run into raw records, including the gap bookkeeping: when the
 * connection drops or a stream fails, each stream that had been delivering gets an open gap, and
 * the gap record is written, with both ends, just before that stream's next frame.
 *
 * Not thread-safe: the owner calls it from one thread.
 */
class CaptureSession(private val writer: RawWriter) {

    private data class LastFrame(val monoNs: Long, val tNs: Long?)

    private val lastFrames = LinkedHashMap<String, LastFrame>()
    private val openGaps = LinkedHashMap<String, GapRecord>()

    var ended: Boolean = false
        private set

    fun start(header: HeaderRecord) = write(header)

    fun event(event: EventRecord) = write(event)

    /** Records the disconnect and opens a gap for every stream that has delivered a frame. */
    fun disconnected(event: EventRecord) {
        write(event)
        for (stream in lastFrames.keys) openGap(stream, RawEvents.DISCONNECTED)
    }

    /** Records the error and opens a gap for its stream, unless that stream is already in one. */
    fun streamError(event: EventRecord) {
        write(event)
        event.stream?.let { openGap(it, RawEvents.STREAM_ERROR) }
    }

    fun frame(record: EcgRecord) =
        frame(RawStreams.ECG, record.monoNs, record.tNs.firstOrNull(), record.tNs.lastOrNull(), record)

    fun frame(record: AccRecord) =
        frame(RawStreams.ACC, record.monoNs, record.tNs.firstOrNull(), record.tNs.lastOrNull(), record)

    fun frame(record: HrRecord) = frame(RawStreams.HR, record.monoNs, null, null, record)

    /** Writes the `end` record, listing the gaps that never closed, and closes the file. */
    fun end(stamp: Stamp, reason: String) {
        write(EndRecord(wallMs = stamp.wallMs, monoNs = stamp.monoNs, reason = reason, openGaps = openGaps.values.toList()))
        ended = true
        writer.close()
    }

    fun syncIfDue() {
        if (!ended) writer.syncIfDue()
    }

    private fun frame(stream: String, monoNs: Long, firstTNs: Long?, lastTNs: Long?, record: RawRecord) {
        openGaps.remove(stream)?.let { write(it.copy(toMonoNs = monoNs, toTNs = firstTNs)) }
        write(record)
        lastFrames[stream] = LastFrame(monoNs, lastTNs)
    }

    /** A stream that never delivered has nothing to resume, so it gets no gap. */
    private fun openGap(stream: String, reason: String) {
        if (stream in openGaps) return
        val last = lastFrames[stream] ?: return
        openGaps[stream] = GapRecord(stream = stream, reason = reason, fromMonoNs = last.monoNs, fromTNs = last.tNs)
    }

    private fun write(record: RawRecord) {
        check(!ended) { "the session has ended; nothing more may be written" }
        writer.write(record)
    }
}
