package com.benwrk.jabjotjai.raw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * These run against real files and real time, with a short poll interval. Every file starts with
 * a header, as every file the writer produces does.
 */
class RawTailTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val timeoutMs = 5_000L

    private fun newFile(): File = File(temp.root, "session.jsonl")

    private fun line(record: RawRecord): String = RawCodec.encode(record) + "\n"

    private fun CoroutineScope.follow(tail: RawTail, from: RawTail.Start = RawTail.Start.Beginning): Pair<Channel<RawLine>, Job> {
        val received = Channel<RawLine>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { tail.lines(from).collect { received.send(it) } }
        return received to job
    }

    private suspend fun failureOf(tail: RawTail): Throwable? {
        var failure: Throwable? = null
        withTimeout(timeoutMs) { tail.lines().catch { failure = it }.collect {} }
        return failure
    }

    @Test
    fun waitsForTheHeaderOfANewFileThenEmitsWhatIsAppended() = runBlocking {
        val file = newFile()
        RawWriter.create(file, monoNs = { 0L }).use { writer ->
            // The file exists but is empty: the tail has to wait for the header.
            val (received, job) = follow(RawTail(file, pollIntervalMs = 5))
            delay(50)
            writer.write(Samples.header)
            assertEquals(RawLine.Record(Samples.header), withTimeout(timeoutMs) { received.receive() })
            writer.write(Samples.ecg)
            assertEquals(RawLine.Record(Samples.ecg), withTimeout(timeoutMs) { received.receive() })
            job.cancel()
        }
    }

    @Test
    fun holdsATornLineBackUntilItsNewlineArrives() = runBlocking {
        val file = newFile().apply { writeText(line(Samples.header)) }
        val text = line(Samples.ecg)
        val (received, job) = follow(RawTail(file, pollIntervalMs = 5))
        assertEquals(RawLine.Record(Samples.header), withTimeout(timeoutMs) { received.receive() })

        file.appendText(text.substring(0, 25))
        delay(150)
        assertNull("a torn line must not be emitted", received.tryReceive().getOrNull())

        file.appendText(text.substring(25))
        // The positive half: the same tail does emit once the line is whole.
        assertEquals(RawLine.Record(Samples.ecg), withTimeout(timeoutMs) { received.receive() })
        job.cancel()
    }

    @Test
    fun startingNearTheEndSkipsToTheNextCompleteLine() = runBlocking {
        val file = newFile().apply { writeText(line(Samples.header) + line(Samples.hr) + line(Samples.ecg)) }
        // Lands inside the hr line, so the first line emitted is the ecg one.
        val from = RawTail.Start.NearEnd(bytes = line(Samples.ecg).length + 5L)
        val (received, job) = follow(RawTail(file, pollIntervalMs = 5), from)
        assertEquals(RawLine.Record(Samples.ecg), withTimeout(timeoutMs) { received.receive() })
        job.cancel()
    }

    @Test
    fun startingExactlyAtALineStartDoesNotDropThatLine() = runBlocking {
        val file = newFile().apply { writeText(line(Samples.header) + line(Samples.hr) + line(Samples.ecg)) }
        val from = RawTail.Start.NearEnd(bytes = line(Samples.ecg).length.toLong())
        val (received, job) = follow(RawTail(file, pollIntervalMs = 5), from)
        assertEquals(RawLine.Record(Samples.ecg), withTimeout(timeoutMs) { received.receive() })
        job.cancel()
    }

    @Test
    fun completesAfterTheEndRecord() = runBlocking {
        val file = newFile().apply { writeText(line(Samples.header) + line(Samples.end)) }
        val lines = withTimeout(timeoutMs) { RawTail(file, pollIntervalMs = 5).lines().toList() }
        assertEquals(listOf(RawLine.Record(Samples.header), RawLine.Record(Samples.end)), lines)
    }

    @Test
    fun reportsAMalformedLineInsteadOfSkippingIt() = runBlocking {
        val file = newFile().apply { writeText(line(Samples.header) + "not json\n" + line(Samples.end)) }
        val lines = withTimeout(timeoutMs) { RawTail(file, pollIntervalMs = 5).lines().toList() }
        assertEquals(3, lines.size)
        assertTrue(lines[1] is RawLine.Malformed)
        assertEquals(RawLine.Record(Samples.end), lines[2])
    }

    @Test
    fun readsANewerMinorSkippingWhatItDoesNotKnow() = runBlocking {
        val newerHeader = RawCodec.encode(Samples.header.copy(schema = "1.7"))
            .replace("\"sensor_epoch\"", "\"added_in_1_7\":true,\"sensor_epoch\"")
        val file = newFile().apply {
            writeText(newerHeader + "\n" + """{"type":"annotation","text":"x"}""" + "\n" + line(Samples.hr) + line(Samples.end))
        }
        val lines = withTimeout(timeoutMs) { RawTail(file, pollIntervalMs = 5).lines().toList() }
        assertEquals(
            listOf(
                RawLine.Record(Samples.header.copy(schema = "1.7")),
                RawLine.Unknown("annotation"),
                RawLine.Record(Samples.hr),
                RawLine.Record(Samples.end),
            ),
            lines,
        )
    }

    @Test
    fun refusesAFileWhoseSchemaMajorItCannotRead() = runBlocking {
        val file = newFile().apply { writeText(line(Samples.header.copy(schema = "2.0")) + line(Samples.end)) }
        val failure = failureOf(RawTail(file, pollIntervalMs = 5))
        assertTrue("expected UnreadableRawFileException, got $failure", failure is UnreadableRawFileException)
    }

    @Test
    fun refusesAFileThatDoesNotStartWithAHeader() = runBlocking {
        val file = newFile().apply { writeText(line(Samples.ecg) + line(Samples.end)) }
        val failure = failureOf(RawTail(file, pollIntervalMs = 5))
        assertTrue("expected UnreadableRawFileException, got $failure", failure is UnreadableRawFileException)
    }

    @Test
    fun failsIfTheFileGetsShorter() = runBlocking {
        val file = newFile().apply { writeText(line(Samples.header)) }
        val failure = Channel<Throwable>(1)
        val received = Channel<RawLine>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) {
            RawTail(file, pollIntervalMs = 5).lines()
                .catch { failure.send(it) }
                .collect { received.send(it) }
        }
        // Wait until the header has been read, so the shrink happens behind the read position.
        assertEquals(RawLine.Record(Samples.header), withTimeout(timeoutMs) { received.receive() })
        RandomAccessFile(file, "rw").use { it.setLength(0) }
        val thrown = withTimeout(timeoutMs) { failure.receive() }
        assertTrue(thrown is IOException && thrown !is UnreadableRawFileException)
        job.cancel()
    }

    @Test
    fun readHeaderReturnsTheHeaderOrNullWhileItIsIncomplete() {
        val file = newFile().apply { writeText(RawCodec.encode(Samples.header).substring(0, 10)) }
        assertNull(RawTail.readHeader(file))
        file.writeText(line(Samples.header) + line(Samples.ecg))
        assertEquals(Samples.header, RawTail.readHeader(file))
    }

    @Test
    fun readHeaderRefusesAnUnknownMajor() {
        val file = newFile().apply { writeText(line(Samples.header.copy(schema = "2.0"))) }
        assertThrows(UnreadableRawFileException::class.java) { RawTail.readHeader(file) }
    }

    @Test
    fun eachLineComesWithTheByteOffsetWhereItStarts() = runBlocking {
        // The settings event carries a non-ASCII value, so characters and bytes differ.
        val records = listOf(Samples.header, Samples.ecg, Samples.settings.copy(value = "µV ±"), Samples.hr, Samples.end)
        val texts = records.map(::line)
        val file = newFile().apply { writeText(texts.joinToString("")) }
        val expectedOffsets = texts.runningFold(0L) { offset, text -> offset + text.toByteArray(Charsets.UTF_8).size }.dropLast(1)

        val read = withTimeout(timeoutMs) { RawTail(file, pollIntervalMs = 5).linesWithOffsets().toList() }

        assertEquals(expectedOffsets, read.map { it.offset })
        assertEquals(records.map { RawLine.Record(it) }, read.map { it.line })
    }

    @Test
    fun startingAtAReportedOffsetResumesAtThatLine() = runBlocking {
        val records = listOf(Samples.header, Samples.ecg, Samples.settings.copy(value = "µV ±"), Samples.hr, Samples.acc, Samples.end)
        val file = newFile().apply { writeText(records.joinToString("") { line(it) }) }
        val all = withTimeout(timeoutMs) { RawTail(file, pollIntervalMs = 5).linesWithOffsets().toList() }

        for (startIndex in 1 until all.size) {
            val resumed = withTimeout(timeoutMs) {
                RawTail(file, pollIntervalMs = 5).linesWithOffsets(RawTail.Start.At(all[startIndex].offset)).toList()
            }
            assertEquals("from line $startIndex", all.drop(startIndex), resumed)
        }
    }

    @Test
    fun anOffsetInsideALineMovesOnToTheNextLine() = runBlocking {
        val records = listOf(Samples.header, Samples.ecg, Samples.hr, Samples.end)
        val file = newFile().apply { writeText(records.joinToString("") { line(it) }) }
        val all = withTimeout(timeoutMs) { RawTail(file, pollIntervalMs = 5).linesWithOffsets().toList() }

        val resumed = withTimeout(timeoutMs) {
            RawTail(file, pollIntervalMs = 5).linesWithOffsets(RawTail.Start.At(all[1].offset + 3)).toList()
        }
        assertEquals(all.drop(2), resumed)
    }
}
