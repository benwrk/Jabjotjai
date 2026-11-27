package com.benwrk.jabjotjai.capture

import com.benwrk.jabjotjai.raw.AccRecord
import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.EndRecord
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.GapRecord
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.Phone
import com.benwrk.jabjotjai.raw.RAW_SCHEMA
import com.benwrk.jabjotjai.raw.RawCodec
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.RawRecord
import com.benwrk.jabjotjai.raw.RawStreams
import com.benwrk.jabjotjai.raw.RawWriter
import com.benwrk.jabjotjai.raw.Sensor
import com.benwrk.jabjotjai.raw.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** These read back the file the session wrote, because the file is what has to be right. */
class CaptureSessionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val file by lazy { File(temp.root, "session.jsonl") }
    private val session by lazy { CaptureSession(RawWriter.create(file, monoNs = { 0L })) }

    private val header = HeaderRecord(
        schema = RAW_SCHEMA,
        session = "s",
        app = "0.1.0 (1000)",
        sdk = "9.0.0",
        phone = Phone("samsung", "SM-S931B", "17", 37),
        start = Stamp(0, 0),
        sensor = Sensor("A1B2C3D4", "Polar H10 A1B2C3D4"),
        sensorEpoch = "2000-01-01",
    )

    private fun ecg(monoNs: Long, vararg tNs: Long) = EcgRecord(monoNs / 1_000_000, monoNs, tNs.toList(), tNs.map { 100 })
    private fun acc(monoNs: Long, vararg tNs: Long) =
        AccRecord(monoNs / 1_000_000, monoNs, tNs.toList(), tNs.map { 1 }, tNs.map { 2 }, tNs.map { 3 })
    private fun hr(monoNs: Long) = HrRecord(monoNs / 1_000_000, monoNs, 60, listOf(1024), listOf(1000), true, true, true, 0, 0)
    private fun disconnect(monoNs: Long) = EventRecord(monoNs / 1_000_000, monoNs, RawEvents.DISCONNECTED, reason = "CONNECTION_LOST")
    private fun streamError(monoNs: Long, stream: String) = EventRecord(monoNs / 1_000_000, monoNs, RawEvents.STREAM_ERROR, stream = stream, message = "x")

    private fun written(): List<RawRecord> = file.readLines().map { (RawCodec.decode(it) as RawLine.Record).record }

    @Test
    fun aDisconnectBecomesAGapWrittenWithBothEndsBeforeTheNextFrame() {
        session.start(header)
        session.frame(ecg(1_000, 10, 20))
        session.disconnected(disconnect(2_000))
        session.frame(ecg(9_000, 900, 910))
        session.end(Stamp(10, 10_000), "user_stop")

        assertEquals(
            listOf(
                header,
                ecg(1_000, 10, 20),
                disconnect(2_000),
                GapRecord(RawStreams.ECG, RawEvents.DISCONNECTED, fromMonoNs = 1_000, toMonoNs = 9_000, fromTNs = 20, toTNs = 900),
                ecg(9_000, 900, 910),
                EndRecord(10, 10_000, "user_stop", emptyList()),
            ),
            written(),
        )
    }

    @Test
    fun everyStreamThatWasDeliveringGetsItsOwnGap() {
        session.start(header)
        session.frame(ecg(1_000, 10))
        session.frame(acc(1_100, 11))
        session.frame(hr(1_200))
        session.disconnected(disconnect(2_000))
        session.frame(hr(8_000))
        session.frame(acc(8_100, 811))
        session.frame(ecg(8_200, 812))
        session.end(Stamp(10, 10_000), "user_stop")

        val gaps = written().filterIsInstance<GapRecord>()
        assertEquals(
            listOf(
                // Heart rate has no sensor time, so its gap has none either.
                GapRecord(RawStreams.HR, RawEvents.DISCONNECTED, fromMonoNs = 1_200, toMonoNs = 8_000),
                GapRecord(RawStreams.ACC, RawEvents.DISCONNECTED, fromMonoNs = 1_100, toMonoNs = 8_100, fromTNs = 11, toTNs = 811),
                GapRecord(RawStreams.ECG, RawEvents.DISCONNECTED, fromMonoNs = 1_000, toMonoNs = 8_200, fromTNs = 10, toTNs = 812),
            ),
            gaps,
        )
    }

    @Test
    fun aStreamErrorDuringAnOpenGapDoesNotOpenASecondOneOrChangeTheReason() {
        session.start(header)
        session.frame(ecg(1_000, 10))
        session.disconnected(disconnect(2_000))
        session.streamError(streamError(2_100, RawStreams.ECG))
        session.frame(ecg(9_000, 900))
        session.end(Stamp(10, 10_000), "user_stop")

        val gaps = written().filterIsInstance<GapRecord>()
        assertEquals(1, gaps.size)
        assertEquals(RawEvents.DISCONNECTED, gaps.single().reason)
    }

    @Test
    fun aStreamErrorWhileConnectedOpensAGapForThatStreamOnly() {
        session.start(header)
        session.frame(ecg(1_000, 10))
        session.frame(acc(1_100, 11))
        session.streamError(streamError(2_000, RawStreams.ACC))
        session.frame(ecg(2_500, 20))
        session.frame(acc(5_000, 500))
        session.end(Stamp(10, 10_000), "user_stop")

        assertEquals(
            listOf(GapRecord(RawStreams.ACC, RawEvents.STREAM_ERROR, fromMonoNs = 1_100, toMonoNs = 5_000, fromTNs = 11, toTNs = 500)),
            written().filterIsInstance<GapRecord>(),
        )
    }

    @Test
    fun aStreamThatNeverDeliveredGetsNoGap() {
        session.start(header)
        session.frame(ecg(1_000, 10))
        session.disconnected(disconnect(2_000))
        session.frame(acc(3_000, 30))
        session.end(Stamp(10, 10_000), "user_stop")

        val end = written().last() as EndRecord
        assertTrue(written().none { it is GapRecord && it.stream == RawStreams.ACC })
        // ECG never resumed, so its gap is still open at the end, with no "to" side.
        assertEquals(listOf(GapRecord(RawStreams.ECG, RawEvents.DISCONNECTED, fromMonoNs = 1_000, fromTNs = 10)), end.openGaps)
    }

    @Test
    fun nothingCanBeWrittenAfterTheEnd() {
        session.start(header)
        session.end(Stamp(1, 1), "user_stop")
        assertThrows(IllegalStateException::class.java) { session.frame(ecg(2, 2)) }
        assertEquals(2, file.readLines().size)
    }
}
