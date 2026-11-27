package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.EventRecord
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.Phone
import com.benwrk.jabjotjai.raw.RAW_SCHEMA
import com.benwrk.jabjotjai.raw.RawCodec
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.RawEvents
import com.benwrk.jabjotjai.raw.RawRecord
import com.benwrk.jabjotjai.raw.Sensor
import com.benwrk.jabjotjai.raw.Stamp
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EarlierTrendsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val s = 1_000_000_000L

    private fun header(session: String, wallMs: Long, monoNs: Long, continues: String? = null) = HeaderRecord(
        schema = RAW_SCHEMA,
        session = session,
        app = "0.6.0 (6000)",
        sdk = "9.0.0",
        phone = Phone(make = "samsung", model = "SM-S931B", android = "17", api = 37),
        start = Stamp(wallMs = wallMs, monoNs = monoNs),
        sensor = Sensor(id = "A1B2C3D4", name = "Polar H10 A1B2C3D4", address = "00:11:22:33:44:55"),
        sensorEpoch = "2000-01-01",
        continues = continues,
    )

    private fun hr(monoNs: Long, bpm: Int) =
        HrRecord(0, monoNs, bpm, listOf(1024), listOf(1000), rrAvailable = true, contact = true, contactSupported = true, correctedBpm = 0, ppgQuality = 0)

    private fun write(header: HeaderRecord, vararg records: RawRecord): File =
        File(folder.root, "${header.session}.jsonl").apply { writeText((listOf(header) + records).joinToString("\n", postfix = "\n") { RawCodec.encode(it) }) }

    @Test
    fun withinOneRunOfThePhoneTheClocksAgree() {
        val earlier = header("a", wallMs = 1_000_000, monoNs = 100 * s)
        val current = header("b", wallMs = 1_300_000, monoNs = 400 * s)
        assertEquals(0L, clockShiftNs(earlier, current))
    }

    @Test
    fun afterARestartTheEarlierTimesAreMovedByTheWallClock() {
        // Started at 1000 s on the wall clock and 100 s of uptime; after a restart, 2000 s and 5 s.
        val earlier = header("a", wallMs = 1_000_000, monoNs = 100 * s)
        val current = header("b", wallMs = 2_000_000, monoNs = 5 * s)
        // 100 s of uptime then was 1000 s on the wall clock, which is 5 - 1000 = -995 s of uptime now,
        // so the earlier times move by -995 - 100 = -1095 s.
        assertEquals(-1095 * s, clockShiftNs(earlier, current))
    }

    @Test
    fun theEarlierFilesHeartRateComesFirstWithABreakBeforeThisFilesOwn() {
        val a = header("a", wallMs = 1_000_000, monoNs = 100 * s)
        write(a, hr(101 * s, 61), EcgRecord(0, 102 * s, listOf(1), listOf(5)), hr(103 * s, 63))
        val b = header("b", wallMs = 1_010_000, monoNs = 110 * s, continues = "a")
        write(b)

        val model = MonitorModel()
        readEarlierTrends(model, folder.root, b)
        model.accept(RawLine.Record(hr(111 * s, 70)))

        val trend = model.snapshot().heartRateTrend
        assertEquals(listOf(61, 63, TrendSnapshot.BREAK, 70), trend.values.take(trend.size))
        assertEquals(listOf(101 * s, 103 * s, 103 * s, 111 * s), trend.tNs.take(trend.size))
        // Nothing else of the earlier file is shown.
        assertEquals(0, model.snapshot().ecg.size)
    }

    @Test
    fun aMissingEarlierFileBreaksTheChainAndIsCountedAsNotRead() {
        val b = header("b", wallMs = 1_010_000, monoNs = 110 * s, continues = "gone")
        assertEquals(EarlierChain(files = emptyList(), brokenAt = "gone"), earlierCaptures(folder.root, b))
        // Counted, so the trend rows say a recording could not be read rather than showing a
        // capture that continues nothing.
        val read = readEarlierTrends(MonitorModel(), folder.root, b)
        assertEquals(EarlierTrendsRead(files = 1, bytes = 1, unreadable = 1, finished = true), read)
    }

    @Test
    fun anEarlierFileWhoseHeaderCannotBeReadBreaksTheChainThere() {
        val a = header("a", wallMs = 1_000_000, monoNs = 100 * s)
        write(a, hr(101 * s, 61))
        File(folder.root, "b.jsonl").writeText("not a header\n")
        val c = header("c", wallMs = 1_020_000, monoNs = 120 * s, continues = "b")
        assertEquals(EarlierChain(files = emptyList(), brokenAt = "b"), earlierCaptures(folder.root, c))
    }

    @Test
    fun progressOnlyRisesAndEndsAtOne() {
        val a = header("a", wallMs = 1_000_000, monoNs = 100 * s)
        write(a, *Array(2_000) { hr(101 * s + it * 1_000_000L, 60) })
        val b = header("b", wallMs = 1_010_000, monoNs = 110 * s, continues = "a")
        val progress = mutableListOf<Float>()
        val read = readEarlierTrends(MonitorModel(), folder.root, b, onProgress = { progress += it })
        assertEquals(EarlierTrendsRead(files = 1, bytes = File(folder.root, "a.jsonl").length(), unreadable = 0, finished = true), read.copy(read = emptyList()))
        assertEquals(listOf("a.jsonl"), read.read.map { it.file.name })
        assertTrue("several steps reported: $progress", progress.size > 2)
        assertEquals(progress.sorted(), progress)
        assertEquals(1f, progress.last())
    }

    @Test
    fun aLineThatCannotBeReadIsPassedOverAndTheRestKept() {
        val a = header("a", wallMs = 1_000_000, monoNs = 100 * s)
        val file = write(a, hr(101 * s, 61))
        // A heart-rate line cut off part-way, then a whole one after it.
        file.appendText("{\"type\":\"hr\",\"cut off\n" + RawCodec.encode(hr(103 * s, 63)) + "\n")
        val b = header("b", wallMs = 1_010_000, monoNs = 110 * s, continues = "a")
        val model = MonitorModel()
        val read = readEarlierTrends(model, folder.root, b)
        assertEquals(0, read.unreadable)
        val trend = model.snapshot().heartRateTrend
        assertEquals(listOf(61, 63, TrendSnapshot.BREAK), trend.values.take(trend.size))
    }

    @Test
    fun readingStopsUnfinishedWhenTold() {
        val a = header("a", wallMs = 1_000_000, monoNs = 100 * s)
        write(a, hr(101 * s, 61))
        val b = header("b", wallMs = 1_010_000, monoNs = 110 * s, continues = "a")
        val read = readEarlierTrends(MonitorModel(), folder.root, b, keepGoing = { false })
        assertEquals(false, read.finished)
    }

    @Test
    fun eachEarlierFileIsMappedForThePastReader() {
        val a = header("a", wallMs = 1_000_000, monoNs = 100 * s)
        val file = write(a, EventRecord(0, 100 * s + 1, RawEvents.CONNECTED), hr(101 * s, 61), hr(103 * s, 63))
        val b = header("b", wallMs = 1_010_000, monoNs = 110 * s, continues = "a")
        val mapped = readEarlierTrends(MonitorModel(), folder.root, b).read.single()
        assertEquals(100 * s, mapped.startNs)
        assertEquals(103 * s, mapped.endNs)
        // The line at 103 s starts after the header, the event and the first heart rate.
        val offsetOf103 = file.readLines().take(3).sumOf { it.length + 1 }.toLong()
        assertEquals(offsetOf103, mapped.timeline.markAtOrBefore(103 * s)?.offset)
        assertEquals(RawEvents.CONNECTED, mapped.timeline.stateAt(102 * s).connection)
    }

    @Test
    fun aLinesTimeIsReadFromItsMonoField() {
        assertEquals(42L, monoNsOf("{\"type\":\"ecg\",\"wall_ms\":1,\"mono_ns\":42,\"t_ns\":[]}"))
        assertNull(monoNsOf("{\"type\":\"gap\",\"from_mono_ns\":5}"))
    }

    @Test
    fun aRecordIsMovedOnlyOnThePhonesClockAndNotCopiedWhenThereIsNothingToMove() {
        val record = hr(101 * s, 61)
        assertTrue(record.shiftedBy(0) === record)
        assertEquals(record.copy(monoNs = 91 * s), record.shiftedBy(-10 * s))
        val ecg = EcgRecord(0, 102 * s, listOf(7), listOf(5))
        // The strap's own sample times stay as it sent them.
        assertEquals(EcgRecord(0, 92 * s, listOf(7), listOf(5)), ecg.shiftedBy(-10 * s))
    }

    @Test
    fun anEarlierFileIsReadFromAPlaceInItWithItsTimesMoved() {
        val a = header("a", wallMs = 1_000_000, monoNs = 100 * s)
        val file = write(a, hr(101 * s, 61), hr(103 * s, 63))
        val offsetOfSecond = file.readLines().take(2).sumOf { it.length + 1 }.toLong()
        val lines = runBlocking { earlierLines(file, offsetOfSecond, shiftNs = -100 * s).toList() }
        assertEquals(listOf(hr(3 * s, 63)), lines.map { (it as RawLine.Record).record })
    }

    /**
     * The JSON encoder writes characters outside ASCII as they are, so one in the header takes more
     * bytes than characters, and every place mapped after it must still be where its line starts.
     */
    @Test
    fun placesAreMappedInBytesSoACharacterOutsideAsciiMovesNothing() {
        val a = header("a", wallMs = 1_000_000, monoNs = 100 * s).let { it.copy(phone = it.phone.copy(model = "Téléphone")) }
        val file = write(a, hr(101 * s, 61), hr(103 * s, 63))
        val headerLine = file.readLines().first()
        assertTrue("the header holds more bytes than characters", headerLine.toByteArray().size > headerLine.length)
        val b = header("b", wallMs = 1_010_000, monoNs = 110 * s, continues = "a")
        val mapped = readEarlierTrends(MonitorModel(), folder.root, b).read.single()
        val mark = requireNotNull(mapped.timeline.markAtOrBefore(103 * s))
        // The line at 103 s starts after the header and the first heart rate, counted in bytes;
        // counted in characters it would be two short, inside the line before.
        assertEquals(file.readLines().take(2).sumOf { it.toByteArray().size + 1 }.toLong(), mark.offset)
        // Read from the place mapped for 103 s, the first line is the heart rate at 103 s itself.
        val lines = runBlocking { earlierLines(file, mark.offset, shiftNs = 0).toList() }
        assertEquals(hr(103 * s, 63), (lines.first() as RawLine.Record).record)
    }

    @Test
    fun anEarlierFileReadFromPartWayThroughALineStartsAtTheNextAndLeavesOutATornLastLine() {
        val a = header("a", wallMs = 1_000_000, monoNs = 100 * s)
        val file = write(a, hr(101 * s, 61), hr(103 * s, 63))
        file.appendText("{\"type\":\"hr\",\"cut off")
        val partWayThroughTheFirstHeartRate = file.readLines().first().length + 5L
        val lines = runBlocking { earlierLines(file, partWayThroughTheFirstHeartRate, shiftNs = 0).toList() }
        assertEquals(listOf(hr(103 * s, 63)), lines.map { (it as RawLine.Record).record })
    }
}
