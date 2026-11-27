package com.benwrk.jabjotjai.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RawWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun newFile(): File = File(temp.root, "raw/session.jsonl")

    @Test
    fun writesEachRecordAsOneLineInOrder() {
        val file = newFile()
        RawWriter.create(file, monoNs = { 0L }).use { writer ->
            writer.write(Samples.header)
            writer.write(Samples.ecg)
            // Visible before close: nothing is buffered in the app.
            assertEquals(
                RawCodec.encode(Samples.header) + "\n" + RawCodec.encode(Samples.ecg) + "\n",
                file.readText(),
            )
        }
    }

    @Test
    fun refusesToReopenAnExistingFileAndLeavesItUntouched() {
        val file = newFile().apply {
            parentFile!!.mkdirs()
            writeText("written earlier\n")
        }
        assertThrows(IOException::class.java) { RawWriter.create(file, monoNs = { 0L }) }
        assertEquals("written earlier\n", file.readText())
    }

    @Test
    fun syncsOnlyOnceTheIntervalHasPassed() {
        var now = 0L
        var syncs = 0
        val writer = RawWriter.createWithSync(newFile(), { now }, syncIntervalNs = 1_000) { syncs++ }

        writer.write(Samples.ecg)
        now = 999
        writer.write(Samples.ecg)
        assertEquals("not due before the interval", 0, syncs)

        now = 1_000
        writer.write(Samples.ecg)
        assertEquals("due at the interval", 1, syncs)

        now = 5_000
        writer.syncIfDue()
        assertEquals("nothing new to sync", 1, syncs)

        writer.close()
        assertEquals("nothing unsynced at close", 1, syncs)
    }

    @Test
    fun aTimerCallSyncsTheRecordsBeforeAQuietStretch() {
        var now = 0L
        var syncs = 0
        val writer = RawWriter.createWithSync(newFile(), { now }, syncIntervalNs = 1_000) { syncs++ }

        writer.write(Samples.ecg)
        now = 1_000
        writer.syncIfDue()
        assertEquals(1, syncs)
        writer.close()
    }

    @Test
    fun closeSyncsWhatIsStillUnsynced() {
        var syncs = 0
        val writer = RawWriter.createWithSync(newFile(), { 0L }, syncIntervalNs = 1_000) { syncs++ }
        writer.write(Samples.ecg)
        assertEquals(0, syncs)
        writer.close()
        assertEquals(1, syncs)
    }
}
