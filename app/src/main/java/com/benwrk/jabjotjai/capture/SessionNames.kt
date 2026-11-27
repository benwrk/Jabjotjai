package com.benwrk.jabjotjai.capture

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** Session ids and the raw file each one is written to (`docs/raw-format.md`). */
object SessionNames {

    private val START = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** The UTC start time plus a random suffix, so ids sort by time and two starts in one second differ. */
    fun newId(startWallMs: Long, random: Random = Random.Default): String =
        START.format(Instant.ofEpochMilli(startWallMs)) + "-" + "%04x".format(random.nextInt(0x10000))

    fun rawFile(rawDir: File, sessionId: String): File = File(rawDir, "$sessionId.jsonl")
}
