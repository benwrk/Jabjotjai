package com.benwrk.jabjotjai.raw

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Appends records to a new raw file, one line each. Each record is written through to the OS
 * with one write call and no buffer in the app, so a reader tailing the file sees it at once. A
 * sync to storage follows at most [syncIntervalNs] later, which bounds what a power cut can lose.
 *
 * Not thread-safe: the owner serialises calls.
 */
class RawWriter private constructor(
    private val out: FileOutputStream,
    private val syncIntervalNs: Long,
    private val monoNs: () -> Long,
    private val sync: (FileOutputStream) -> Unit,
) : Closeable {

    private var lastSyncNs = monoNs()
    private var unsynced = false

    fun write(record: RawRecord) {
        out.write((RawCodec.encode(record) + "\n").encodeToByteArray())
        unsynced = true
        syncIfDue()
    }

    /**
     * Syncs to storage if anything is unsynced and [syncIntervalNs] has passed since the last
     * sync. Call it on a timer as well, so the records before a quiet stretch get synced too.
     */
    fun syncIfDue() {
        if (unsynced && monoNs() - lastSyncNs >= syncIntervalNs) syncNow()
    }

    override fun close() {
        try {
            if (unsynced) syncNow()
        } finally {
            out.close()
        }
    }

    private fun syncNow() {
        sync(out)
        lastSyncNs = monoNs()
        unsynced = false
    }

    companion object {
        /** One second: the most a power cut may lose. */
        const val DEFAULT_SYNC_INTERVAL_NS: Long = 1_000_000_000L

        /**
         * Creates [file] and opens it for appending. Refuses a file that already exists, because a
         * raw file that has been written is never reopened.
         *
         * @param monoNs the phone's monotonic clock, `SystemClock::elapsedRealtimeNanos` on a phone
         */
        fun create(
            file: File,
            monoNs: () -> Long,
            syncIntervalNs: Long = DEFAULT_SYNC_INTERVAL_NS,
        ): RawWriter = createWithSync(file, monoNs, syncIntervalNs) { it.fd.sync() }

        /** [create] with the sync replaced, so tests can observe when it happens. */
        internal fun createWithSync(
            file: File,
            monoNs: () -> Long,
            syncIntervalNs: Long,
            sync: (FileOutputStream) -> Unit,
        ): RawWriter {
            val directory = file.absoluteFile.parentFile
            if (directory != null && !directory.isDirectory && !directory.mkdirs()) {
                throw IOException("cannot create directory $directory")
            }
            if (!file.createNewFile()) {
                throw IOException("refusing to reopen an existing raw file: $file")
            }
            return RawWriter(FileOutputStream(file, true), syncIntervalNs, monoNs, sync)
        }
    }
}
