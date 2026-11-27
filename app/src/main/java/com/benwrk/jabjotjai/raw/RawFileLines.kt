package com.benwrk.jabjotjai.raw

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * The lines of a raw file read to its current end, as text, each with the byte offset where it
 * starts; [RawTail] is the reader that follows a file as it grows. Offsets count bytes, as
 * [RawTail]'s do, so a place one reader reports is a place the other can start from: a line holding
 * a character outside ASCII takes more bytes than characters. A last line without its newline, as
 * a crash can leave, is not returned. Close it, or read it with `use`, so the file is closed however
 * reading stops.
 *
 * @param from where to start: a line's start, or else the start of the next complete line
 */
class RawFileLines(file: File, from: Long = 0L, bufferBytes: Int = BUFFER_BYTES) : Iterator<RawFileLines.Line>, Closeable {

    data class Line(val offset: Long, val text: String)

    private val input = FileInputStream(file)
    private val buffer = ByteArray(bufferBytes)
    private val carry = ByteArrayOutputStream()

    /** The file offset of [buffer]'s first byte, how many bytes it holds, and the next to look at. */
    private var bufferStart: Long
    private var length = 0
    private var index = 0

    /** Where the line being read starts, and whether reading is still passing over a line begun before [from]. */
    private var lineStart: Long
    private var skipping: Boolean

    private var next: Line? = null
    private var finished = false

    init {
        try {
            val channel = input.channel
            val start = from.coerceIn(0L, channel.size())
            skipping = start > 0L && ByteBuffer.allocate(1).let { before ->
                channel.read(before, start - 1)
                before.get(0) != NEWLINE
            }
            channel.position(start)
            bufferStart = start
            lineStart = start
        } catch (e: Exception) {
            input.close()
            throw e
        }
    }

    override fun hasNext(): Boolean {
        if (next == null && !finished) {
            next = advance()
            if (next == null) finished = true
        }
        return next != null
    }

    override fun next(): Line {
        if (!hasNext()) throw NoSuchElementException()
        return next!!.also { next = null }
    }

    override fun close() = input.close()

    private fun advance(): Line? {
        while (true) {
            if (index >= length && !fill()) return null
            var newline = index
            while (newline < length && buffer[newline] != NEWLINE) newline++
            if (newline == length) {
                if (!skipping) carry.write(buffer, index, length - index)
                index = length
                continue
            }
            val line = if (skipping) {
                skipping = false
                null
            } else if (carry.size() == 0) {
                Line(lineStart, String(buffer, index, newline - index, Charsets.UTF_8))
            } else {
                carry.write(buffer, index, newline - index)
                Line(lineStart, String(carry.toByteArray(), Charsets.UTF_8)).also { carry.reset() }
            }
            index = newline + 1
            lineStart = bufferStart + index
            if (line != null) return line
        }
    }

    private fun fill(): Boolean {
        bufferStart += length
        length = input.read(buffer).coerceAtLeast(0)
        index = 0
        return length > 0
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val NEWLINE = '\n'.code.toByte()
    }
}
