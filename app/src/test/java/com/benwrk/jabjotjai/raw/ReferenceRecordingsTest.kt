package com.benwrk.jabjotjai.raw

import com.benwrk.jabjotjai.monitor.EarlierChain
import com.benwrk.jabjotjai.monitor.MonitorModel
import com.benwrk.jabjotjai.monitor.TrendSnapshot
import com.benwrk.jabjotjai.monitor.earlierCaptures
import com.benwrk.jabjotjai.monitor.readEarlierTrends
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reads the real recordings in `src/test/resources/raw-reference` (see its README): every schema
 * the app has written must still read, line for line, as long as raw files are kept.
 */
class ReferenceRecordingsTest {

    private val root = File(requireNotNull(javaClass.classLoader?.getResource("raw-reference")) { "reference recordings missing" }.toURI())

    private val recordings = root.walk().filter { it.name.endsWith(".jsonl") }.sortedBy { it.name }.toList()

    @Test
    fun everyLineOfEveryRecordingReadsAsARecordOfTheSchemaItsFolderNames() {
        assertEquals(5, recordings.size)
        for (file in recordings) {
            val lines = file.readLines().filter { it.isNotEmpty() }
            val decode = RawDecoders.forHeaderLine(lines.first(), file)
            val decoded = lines.map(decode)
            assertEquals("${file.name}: lines that did not read as records", emptyList<RawLine>(), decoded.filterNot { it is RawLine.Record })
            val header = (decoded.first() as RawLine.Record).record as HeaderRecord
            assertEquals(file.name, file.parentFile!!.name, header.schema)
            assertEquals(file.name, file.nameWithoutExtension, header.session)
        }
    }

    @Test
    fun everyEndingNoteReadsAndNamesItsCapture() {
        val notes = root.walk().filter { it.name.endsWith(".ending.json") }.toList()
        assertEquals(3, notes.size)
        for (file in notes) {
            val note = requireNotNull(RawCodec.decodeEnding(file.readText().trim())) { "${file.name} did not read" }
            assertEquals(file.name, file.name.removeSuffix(".ending.json"), note.session)
        }
    }

    @Test
    fun aResumedCapturesTrendsReachBackThroughTheFileItContinues() {
        val dir = File(root, "1.2")
        val current = requireNotNull(RawTail.readHeader(File(dir, "20261127T131715Z-5794.jsonl")))
        assertEquals(listOf("20261127T131351Z-3af1.jsonl"), earlierCaptures(dir, current).files.map { it.first.name })

        val model = MonitorModel()
        readEarlierTrends(model, dir, current)
        val trend = model.snapshot().heartRateTrend
        val values = trend.values.take(trend.size)
        // Heart rates from the earlier file, then the break that keeps them from this file's own.
        assertTrue("earlier heart rates read: ${values.size}", values.count { it != TrendSnapshot.BREAK } > 10)
        assertEquals(TrendSnapshot.BREAK, values.last())
    }

    @Test
    fun aCaptureWhoseEarlierFileIsNotKeptHasNothingToReachBackTo() {
        val dir = File(root, "1.2")
        val current = requireNotNull(RawTail.readHeader(File(dir, "20261127T152036Z-31fd.jsonl")))
        // The file it continues is not among the reference recordings, so the chain breaks there.
        assertEquals(EarlierChain(files = emptyList(), brokenAt = current.continues), earlierCaptures(dir, current))
    }
}
