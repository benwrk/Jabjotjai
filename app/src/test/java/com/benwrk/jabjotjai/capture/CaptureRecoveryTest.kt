package com.benwrk.jabjotjai.capture

import com.benwrk.jabjotjai.capture.CaptureRecovery.Inspection
import com.benwrk.jabjotjai.raw.EndRecord
import com.benwrk.jabjotjai.raw.EndingNote
import com.benwrk.jabjotjai.raw.ProcessExit
import com.benwrk.jabjotjai.raw.RawCodec
import com.benwrk.jabjotjai.raw.RawRecord
import com.benwrk.jabjotjai.raw.Samples
import com.benwrk.jabjotjai.raw.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CaptureRecoveryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val session = Samples.header.session
    private val updated = ProcessExit(reason = "PACKAGE_UPDATED", reasonCode = 16, wallMs = 1795766600000)

    private fun raw(vararg records: RawRecord): File =
        File(folder.root, "$session.jsonl").apply { writeText(records.joinToString("") { RawCodec.encode(it) + "\n" }) }

    private fun note(file: File) = CaptureRecovery.ending(file)

    /** Inspects [file], recording the start time it asked the process exits about. */
    private fun inspect(file: File, exit: ProcessExit? = updated): Pair<Inspection, List<Long?>> {
        val asked = mutableListOf<Long?>()
        val found = CaptureRecovery.inspect(file, session, notedWallMs = 42) { asked += it; exit }
        return found to asked
    }

    @Test
    fun aFileWithItsEndRecordEndedAndGetsNoNote() {
        val file = raw(Samples.header, Samples.ecg, EndRecord(wallMs = 1, monoNs = 2, reason = "user_stop"))
        assertEquals(Inspection.Ended, inspect(file).first)
        assertFalse(note(file).exists())
    }

    @Test
    fun aFileWithoutAnEndIsNotedWithItsLastLineAndHowTheProcessEnded() {
        val file = raw(Samples.header, Samples.ecg)
        val (found, asked) = inspect(file)
        val expected = EndingNote(session = session, notedWallMs = 42, lastLine = Stamp(Samples.ecg.wallMs, Samples.ecg.monoNs), exit = updated)
        assertEquals(Inspection.Unclean(expected), found)
        // Only exits after the capture began are asked about.
        assertEquals(listOf<Long?>(Samples.header.start.wallMs), asked)
        assertEquals(expected, RawCodec.decodeEnding(note(file).readText().trim()))
    }

    @Test
    fun aNoteAlreadyThereIsUsedAndNotWrittenOver() {
        val file = raw(Samples.header, Samples.ecg)
        val earlier = EndingNote(session = session, notedWallMs = 7, exit = null)
        val text = RawCodec.encode(earlier) + "\n"
        note(file).writeText(text)
        assertEquals(Inspection.Unclean(earlier), inspect(file).first)
        assertEquals(text, note(file).readText())
    }

    @Test
    fun aNoteThatCannotBeReadIsAFailureAndIsLeftAsItIs() {
        val file = raw(Samples.header, Samples.ecg)
        note(file).writeText("not a note\n")
        assertTrue(inspect(file).first is Inspection.Failed)
        assertEquals("not a note\n", note(file).readText())
    }

    @Test
    fun aHeaderThatCannotBeReadIsAFailureAndNothingIsNoted() {
        val file = File(folder.root, "$session.jsonl").apply { writeText("not a header\n" + RawCodec.encode(Samples.ecg) + "\n") }
        assertTrue(inspect(file).first is Inspection.Failed)
        assertFalse(note(file).exists())
    }

    @Test
    fun aFileWithNothingWrittenYetIsNotedWithNoLastLine() {
        val file = File(folder.root, "$session.jsonl").apply { writeText("") }
        val (found, asked) = inspect(file, exit = null)
        assertEquals(Inspection.Unclean(EndingNote(session = session, notedWallMs = 42)), found)
        assertEquals(listOf<Long?>(null), asked)
    }
}
