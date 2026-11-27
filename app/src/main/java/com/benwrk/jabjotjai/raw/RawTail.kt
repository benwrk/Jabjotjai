package com.benwrk.jabjotjai.raw

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
    }

    /**
     * Emits each complete line from [from] onwards, decoded, and keeps waiting for more until an
     * [EndRecord] has been emitted. A line whose newline has not arrived yet is held back until it
     * does, which also leaves a crash's torn last line alone. The file must already exist.
     *
     * @throws IOException if the file gets shorter, which an append-only file never does
     */
    fun lines(from: Start = Start.Beginning): Flow<RawLine> = flow {
        RandomAccessFile(file, "r").use { raf ->
            var position = when (from) {
                Start.Beginning -> 0L
                is Start.NearEnd -> (raf.length() - from.bytes).coerceAtLeast(0L)
            }
            var skippingPartialLine = position > 0L && raf.byteAt(position - 1) != NEWLINE
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
                position += read
                for (i in 0 until read) {
                    val byte = buffer[i]
                    if (skippingPartialLine) {
                        if (byte == NEWLINE) skippingPartialLine = false
                    } else if (byte != NEWLINE) {
                        pending.write(byte.toInt())
                    } else {
                        val line = RawCodec.decode(String(pending.toByteArray(), Charsets.UTF_8))
                        pending.reset()
                        emit(line)
                        if (line is RawLine.Record && line.record is EndRecord) return@flow
                    }
                }
            }
        }
    }.flowOn(dispatcher)

    private fun RandomAccessFile.byteAt(offset: Long): Byte {
        seek(offset)
        return read().toByte()
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 20L
        private const val BUFFER_BYTES = 64 * 1024
        private const val NEWLINE = '\n'.code.toByte()

        /**
         * The first line of [file], decoded, or null if the file has no complete line yet. Every
         * raw file starts with its [HeaderRecord].
         */
        fun readFirstLine(file: File): RawLine? {
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
            return RawCodec.decode(String(bytes, Charsets.UTF_8))
        }
    }
}
