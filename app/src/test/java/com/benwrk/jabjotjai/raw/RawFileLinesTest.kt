package com.benwrk.jabjotjai.raw

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RawFileLinesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(text: String): File = folder.newFile().apply { writeBytes(text.toByteArray(Charsets.UTF_8)) }

    private fun read(file: File, from: Long = 0L, bufferBytes: Int = 4) =
        RawFileLines(file, from, bufferBytes).use { it.asSequence().toList() }

    @Test
    fun offsetsCountBytesSoALineAfterOneOutsideAsciiStartsWhereItIs() {
        // "é" is one character and two bytes.
        val text = "{\"model\":\"é\"}\n{\"b\":2}\n"
        val lines = read(file(text))
        assertEquals(listOf("{\"model\":\"é\"}", "{\"b\":2}"), lines.map { it.text })
        val second = lines[1].offset
        assertEquals(15L, second)
        // Counting characters, as a reader of text would, gives 14: one short, inside the first line.
        assertEquals(14, "{\"model\":\"é\"}\n".length)
        // Starting at the reported offset reads exactly that line.
        assertEquals(listOf(RawFileLines.Line(15L, "{\"b\":2}")), read(file(text), from = second))
    }

    @Test
    fun linesLongerThanTheBufferAreReadWhole() {
        val text = "abcdefghij\nkl\n"
        assertEquals(
            listOf(RawFileLines.Line(0L, "abcdefghij"), RawFileLines.Line(11L, "kl")),
            read(file(text), bufferBytes = 3),
        )
    }

    @Test
    fun startingPartWayThroughALineMovesOnToTheNextWholeOne() {
        val text = "first line\nsecond\n"
        assertEquals(listOf(RawFileLines.Line(11L, "second")), read(file(text), from = 3))
        // Right after a newline is a line's start, so nothing is passed over.
        assertEquals(listOf(RawFileLines.Line(11L, "second")), read(file(text), from = 11))
    }

    @Test
    fun aLastLineWithoutItsNewlineIsLeftOut() {
        assertEquals(listOf("whole"), read(file("whole\n{\"type\":\"ecg\",\"cut")).map { it.text })
    }

    @Test
    fun anEmptyFileAndAStartAtTheEndHaveNoLines() {
        assertEquals(emptyList<RawFileLines.Line>(), read(file("")))
        assertEquals(emptyList<RawFileLines.Line>(), read(file("a\n"), from = 2))
        assertEquals(emptyList<RawFileLines.Line>(), read(file("a\n"), from = 99))
    }
}
