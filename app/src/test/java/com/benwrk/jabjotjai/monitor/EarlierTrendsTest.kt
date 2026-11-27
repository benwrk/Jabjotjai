package com.benwrk.jabjotjai.monitor

import com.benwrk.jabjotjai.raw.EcgRecord
import com.benwrk.jabjotjai.raw.HeaderRecord
import com.benwrk.jabjotjai.raw.HrRecord
import com.benwrk.jabjotjai.raw.Phone
import com.benwrk.jabjotjai.raw.RAW_SCHEMA
import com.benwrk.jabjotjai.raw.RawCodec
import com.benwrk.jabjotjai.raw.RawLine
import com.benwrk.jabjotjai.raw.RawRecord
import com.benwrk.jabjotjai.raw.Sensor
import com.benwrk.jabjotjai.raw.Stamp
import org.junit.Assert.assertEquals
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
    fun aMissingEarlierFileEndsTheChain() {
        val b = header("b", wallMs = 1_010_000, monoNs = 110 * s, continues = "gone")
        assertEquals(emptyList<Pair<File, HeaderRecord>>(), earlierCaptures(folder.root, b))
    }
}
