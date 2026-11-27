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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** These run against real files and real time, with a short poll interval. */
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

    @Test
    fun emitsRecordsAppendedAfterItStarted() = runBlocking {
        val file = newFile()
        RawWriter.create(file, monoNs = { 0L }).use { writer ->
            val (received, job) = follow(RawTail(file, pollIntervalMs = 5))
            writer.write(Samples.header)
            assertEquals(RawLine.Record(Samples.header), withTimeout(timeoutMs) { received.receive() })
            writer.write(Samples.ecg)
            assertEquals(RawLine.Record(Samples.ecg), withTimeout(timeoutMs) { received.receive() })
            job.cancel()
        }
    }

    @Test
    fun holdsATornLineBackUntilItsNewlineArrives() = runBlocking {
        val file = newFile().apply { writeText("") }
        val text = line(Samples.ecg)
        val (received, job) = follow(RawTail(file, pollIntervalMs = 5))

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
        val file = newFile().apply { writeText("not json\n" + line(Samples.end)) }
        val lines = withTimeout(timeoutMs) { RawTail(file, pollIntervalMs = 5).lines().toList() }
        assertEquals(2, lines.size)
        assertTrue(lines[0] is RawLine.Malformed)
        assertEquals(RawLine.Record(Samples.end), lines[1])
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
        assertTrue(withTimeout(timeoutMs) { failure.receive() } is IOException)
        job.cancel()
    }

    @Test
    fun readFirstLineReturnsTheHeaderOrNullWhileItIsIncomplete() {
        val file = newFile().apply { writeText(RawCodec.encode(Samples.header).substring(0, 10)) }
        assertNull(RawTail.readFirstLine(file))
        file.writeText(line(Samples.header) + line(Samples.ecg))
        assertEquals(RawLine.Record(Samples.header), RawTail.readFirstLine(file))
    }
}
