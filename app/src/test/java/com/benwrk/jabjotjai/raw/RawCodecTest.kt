package com.benwrk.jabjotjai.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoded text is the file format, so these tests assert on exact strings: a renamed field or
 * a changed unit has to fail here, not in a reader months later.
 */
class RawCodecTest {

    @Test
    fun headerIsWrittenWithTypeFirstAndTheDocumentedFieldNames() {
        assertEquals(
            """{"type":"header","schema":"1.0","session":"20261127T091530Z-3f9a","app":"0.1.0 (1000)",""" +
                """"sdk":"9.0.0","phone":{"make":"samsung","model":"SM-S931B","android":"16","api":36},""" +
                """"start":{"wall_ms":1795766523219,"mono_ns":81234567890123},""" +
                """"sensor":{"id":"A1B2C3D4","name":"Polar H10 A1B2C3D4"},"sensor_epoch":"2000-01-01"}""",
            RawCodec.encode(Samples.header),
        )
    }

    @Test
    fun ecgKeepsNanosecondTimestampsThatADoubleCannotHold() {
        // 849043934117459201 is odd and above 2^59, so a reader that goes through a double rounds
        // it to a multiple of 128. The file and this codec must keep it exactly.
        val text = RawCodec.encode(Samples.ecg)
        assertEquals(
            """{"type":"ecg","wall_ms":1795766523300,"mono_ns":81234647890123,""" +
                """"t_ns":[849043934117459201,849043934125151508],"uV":[-120,1543]}""",
            text,
        )
        assertEquals(RawLine.Record(Samples.ecg), RawCodec.decode(text))
    }

    @Test
    fun accWritesEachAxisInMilliG() {
        assertEquals(
            """{"type":"acc","wall_ms":1,"mono_ns":2,"t_ns":[10,15],""" +
                """"x_mG":[-12,-10],"y_mG":[998,1001],"z_mG":[40,38]}""",
            RawCodec.encode(Samples.acc),
        )
    }

    @Test
    fun hrKeepsBothTheRawAndTheConvertedIntervals() {
        assertEquals(
            """{"type":"hr","wall_ms":1,"mono_ns":2,"bpm":62,"rr_1024":[991,1003],"rr_ms":[968,979],""" +
                """"rr_available":true,"contact":true,"contact_supported":true,"corrected_bpm":0,"ppg_quality":0}""",
            RawCodec.encode(Samples.hr),
        )
    }

    @Test
    fun eventLeavesOutTheFieldsThatDoNotApply() {
        assertEquals(
            """{"type":"event","wall_ms":1,"mono_ns":2,"event":"disconnected",""" +
                """"reason":"CONNECTION_LOST","recovery":"RETRY_CONNECTION","gatt_status":8}""",
            RawCodec.encode(Samples.disconnected),
        )
    }

    @Test
    fun settingsEventListsWhatWasOfferedAndWhatWasRequested() {
        assertEquals(
            """{"type":"event","wall_ms":1,"mono_ns":2,"event":"settings","stream":"acc",""" +
                """"available":{"rate_hz":[25,50,100,200],"range_g":[2,4,8]},"selected":{"rate_hz":200,"range_g":8}}""",
            RawCodec.encode(Samples.settings),
        )
    }

    @Test
    fun gapWithoutSensorTimeLeavesItOut() {
        assertEquals(
            """{"type":"gap","stream":"hr","reason":"disconnected","from_mono_ns":100,"to_mono_ns":900}""",
            RawCodec.encode(GapRecord(RawStreams.HR, RawEvents.DISCONNECTED, fromMonoNs = 100, toMonoNs = 900)),
        )
    }

    @Test
    fun endWritesAnEmptyGapListRatherThanLeavingItOut() {
        assertEquals(
            """{"type":"end","wall_ms":1,"mono_ns":2,"reason":"user_stop","open_gaps":[]}""",
            RawCodec.encode(EndRecord(wallMs = 1, monoNs = 2, reason = "user_stop")),
        )
    }

    @Test
    fun everyRecordTypeSurvivesARoundTrip() {
        for (record in Samples.oneOfEach) {
            assertEquals(RawLine.Record(record), RawCodec.decode(RawCodec.encode(record)))
        }
    }

    @Test
    fun knownTypesAreExactlyTheRecordSubtypes() {
        val subtypes = RawRecord.serializer().descriptor.getElementDescriptor(1)
        val names = (0 until subtypes.elementsCount).map(subtypes::getElementName).toSet()
        assertEquals(names, RawCodec.knownTypes)
        assertEquals(setOf("header", "event", "ecg", "acc", "hr", "gap", "end"), names)
        assertEquals(names, Samples.oneOfEach.map { typeOf(RawCodec.encode(it)) }.toSet())
    }

    @Test
    fun aTypeFromANewerSchemaIsReportedAsUnknown() {
        assertEquals(RawLine.Unknown("annotation"), RawCodec.decode("""{"type":"annotation","text":"x"}"""))
    }

    @Test
    fun aFieldFromANewerSchemaIsSkipped() {
        assertEquals(
            RawLine.Record(EndRecord(wallMs = 1, monoNs = 2, reason = "user_stop")),
            RawCodec.decode("""{"type":"end","wall_ms":1,"mono_ns":2,"reason":"user_stop","open_gaps":[],"added_later":true}"""),
        )
    }

    @Test
    fun brokenLinesAreReportedAsMalformedNotDropped() {
        val broken = listOf(
            "",
            """{"type":"ecg",""",
            "[1,2]",
            """{"wall_ms":1}""",
            """{"type":7}""",
            """{"type":"hr","wall_ms":1}""",
            // Two timestamps but one sample: refused by the record's own check.
            """{"type":"ecg","wall_ms":1,"mono_ns":2,"t_ns":[1,2],"uV":[5]}""",
        )
        for (line in broken) {
            val decoded = RawCodec.decode(line)
            assertTrue("expected Malformed for <$line> but got $decoded", decoded is RawLine.Malformed)
            assertEquals(line, (decoded as RawLine.Malformed).text)
        }
    }

    private fun typeOf(line: String): String = Regex("^\\{\"type\":\"([a-z]+)\"").find(line)!!.groupValues[1]
}

/** One realistic instance of each record type, shared by the raw tests. */
internal object Samples {
    val header = HeaderRecord(
        schema = RAW_SCHEMA,
        session = "20261127T091530Z-3f9a",
        app = "0.1.0 (1000)",
        sdk = "9.0.0",
        phone = Phone(make = "samsung", model = "SM-S931B", android = "16", api = 36),
        start = Stamp(wallMs = 1795766523219, monoNs = 81234567890123),
        sensor = Sensor(id = "A1B2C3D4", name = "Polar H10 A1B2C3D4"),
        sensorEpoch = "2000-01-01",
    )
    val ecg = EcgRecord(
        wallMs = 1795766523300,
        monoNs = 81234647890123,
        tNs = listOf(849043934117459201, 849043934125151508),
        uV = listOf(-120, 1543),
    )
    val acc = AccRecord(
        wallMs = 1,
        monoNs = 2,
        tNs = listOf(10, 15),
        xMg = listOf(-12, -10),
        yMg = listOf(998, 1001),
        zMg = listOf(40, 38),
    )
    val hr = HrRecord(
        wallMs = 1,
        monoNs = 2,
        bpm = 62,
        rr1024 = listOf(991, 1003),
        rrMs = listOf(968, 979),
        rrAvailable = true,
        contact = true,
        contactSupported = true,
        correctedBpm = 0,
        ppgQuality = 0,
    )
    val disconnected = EventRecord(
        wallMs = 1,
        monoNs = 2,
        event = RawEvents.DISCONNECTED,
        reason = "CONNECTION_LOST",
        recovery = "RETRY_CONNECTION",
        gattStatus = 8,
    )
    val settings = EventRecord(
        wallMs = 1,
        monoNs = 2,
        event = RawEvents.SETTINGS,
        stream = RawStreams.ACC,
        available = linkedMapOf("rate_hz" to listOf(25, 50, 100, 200), "range_g" to listOf(2, 4, 8)),
        selected = linkedMapOf("rate_hz" to 200, "range_g" to 8),
    )
    val gap = GapRecord(
        stream = RawStreams.ECG,
        reason = RawEvents.DISCONNECTED,
        fromMonoNs = 100,
        toMonoNs = 900,
        fromTNs = 849043934117459201,
        toTNs = 849043934917459201,
    )
    val end = EndRecord(
        wallMs = 3,
        monoNs = 4,
        reason = "user_stop",
        openGaps = listOf(GapRecord(RawStreams.HR, RawEvents.DISCONNECTED, fromMonoNs = 100)),
    )
    val oneOfEach: List<RawRecord> = listOf(header, disconnected, ecg, acc, hr, gap, end)
}
