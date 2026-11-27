package com.benwrk.jabjotjai.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class SessionNamesTest {

    @Test
    fun theIdIsTheUtcStartTimeAndARandomSuffix() {
        // 1795766523219 ms after 1970-01-01T00:00:00Z is 2026-11-27T08:02:03.219Z.
        val id = SessionNames.newId(1_795_766_523_219, Random(7))
        assertTrue(id, Regex("^20261127T080203Z-[0-9a-f]{4}$").matches(id))
    }

    @Test
    fun twoStartsInTheSameSecondGetDifferentIds() {
        val random = Random(7)
        assertNotEquals(SessionNames.newId(1_795_766_523_000, random), SessionNames.newId(1_795_766_523_999, random))
    }

    @Test
    fun theRawFileIsNamedAfterTheSession() {
        assertEquals(File("raw", "20261127T080203Z-3f9a.jsonl"), SessionNames.rawFile(File("raw"), "20261127T080203Z-3f9a"))
    }
}
