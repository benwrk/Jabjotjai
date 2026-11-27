package com.benwrk.jabjotjai.raw

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Follows a raw file as it grows. The live monitor is one of these readers, so what it shows is
 * what is stored (`AGENTS.md`, "Live is a reader tailing the raw file").
 */
class RawTail(
    private val file: File,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Where reading starts. */
    sealed interface Start {
        data object Beginning : Start

        /**
         * About [bytes] before the current end of the file, moved forward to the start of the
         * next complete line.
         */
        data class NearEnd(val bytes: Long) : Start

        /**
         * At [offset], a line's start as [linesWithOffsets] reported it. An offset that is not a
         * line's start is moved forward to the start of the next complete line.
         */
        data class At(val offset: Long) : Start
    }

    /** A line, and the byte offset in the file where it starts. */
    data class OffsetLine(val offset: Long, val line: RawLine)

    /**
     * Waits for the file's header, chooses a decoder from the schema it declares, then emits each
     * complete line from [from] onwards and keeps waiting for more until an [EndRecord] has been
     * emitted. A line whose newline has not arrived yet is held back until it does, which also
     * leaves a crash's torn last line alone. The file must already exist.
     *
     * @throws UnreadableRawFileException if the file does not start with a header, or its schema
     *   major is one this build cannot read
     * @throws IOException if the file gets shorter, which an append-only file never does
     */
    fun lines(from: Start = Start.Beginning): Flow<RawLine> = linesWithOffsets(from).map { it.line }

    /** As [lines], with each line's byte offset, so a reader can later start again at that line. */
    fun linesWithOffsets(from: Start = Start.Beginning): Flow<OffsetLine> = flow {
        val decode = RawDecoders.forHeaderLine(awaitFirstLine(), file)
        RandomAccessFile(file, "r").use { raf ->
            var position = when (from) {
                Start.Beginning -> 0L
                is Start.NearEnd -> (raf.length() - from.bytes).coerceAtLeast(0L)
                is Start.At -> from.offset.coerceIn(0L, raf.length())
            }
            var skippingPartialLine = position > 0L && raf.byteAt(position - 1) != NEWLINE
            var lineStart = position
            val pending = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val length = raf.length()
                if (length < position) {
                    throw IOException("raw file shrank from $position to $length bytes: $file")
                }
                if (length == position) {
                    delay(pollIntervalMs)
                    continue
                }
                raf.seek(position)
                val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), length - position).toInt())
                if (read <= 0) {
                    delay(pollIntervalMs)
                    continue
                }
                val readStart = position
                position += read
                for (i in 0 until read) {
                    val byte = buffer[i]
                    if (skippingPartialLine) {
                        if (byte == NEWLINE) {
                            skippingPartialLine = false
                            lineStart = readStart + i + 1
                        }
                    } else if (byte != NEWLINE) {
                        pending.write(byte.toInt())
                    } else {
                        val line = decode(String(pending.toByteArray(), Charsets.UTF_8))
                        pending.reset()
                        emit(OffsetLine(lineStart, line))
                        lineStart = readStart + i + 1
                        if (line is RawLine.Record && line.record is EndRecord) return@flow
                    }
                }
            }
        }
    }.flowOn(dispatcher)

    /** The file's first line, once it is complete. A new file is empty until its header lands. */
    private suspend fun awaitFirstLine(): String {
        while (true) {
            firstLineText(file)?.let { return it }
            delay(pollIntervalMs)
        }
    }

    private fun RandomAccessFile.byteAt(offset: Long): Byte {
        seek(offset)
        return read().toByte()
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 20L
        private const val BUFFER_BYTES = 64 * 1024
        private const val NEWLINE = '\n'.code.toByte()

        /**
         * The header of [file], decoded with the decoder its own schema calls for, or null if the
         * file has no complete line yet.
         *
         * @throws UnreadableRawFileException if the first line is not a header this build can read
         */
        fun readHeader(file: File): HeaderRecord? {
            val text = firstLineText(file) ?: return null
            val line = RawDecoders.forHeaderLine(text, file)(text)
            return ((line as? RawLine.Record)?.record as? HeaderRecord)
                ?: throw UnreadableRawFileException("the first line of $file does not decode as a header: $line")
        }

        /** The first line of [file] without its newline, or null if no line is complete yet. */
        private fun firstLineText(file: File): String? {
            val bytes = file.inputStream().use { input ->
                val firstLine = ByteArrayOutputStream()
                while (true) {
                    val byte = input.read()
                    if (byte == -1) return null
                    if (byte.toByte() == NEWLINE) break
                    firstLine.write(byte)
                }
                firstLine.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        }
    }
}
