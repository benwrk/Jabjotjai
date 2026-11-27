package com.benwrk.jabjotjai.capture

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class StreamRestartsTest {

    /** A stream that fails each time it is collected, a fake clock, and a record of what was done. */
    private class Harness(var attemptsWanted: Int) {
        var nowMs = 0L
        var attempts = 0
        val errors = mutableListOf<String>()
        val pauses = mutableListOf<Long>()

        fun run(attempt: suspend () -> Unit = { throw IllegalStateException("boom") }) = runBlocking {
            keepStreaming(
                attempt = {
                    attempts++
                    attempt()
                },
                onError = { errors += it },
                wanted = { attempts < attemptsWanted },
                nowMs = { nowMs },
                pause = {
                    pauses += it
                    nowMs += it
                },
            )
        }
    }

    @Test
    fun aFailedStreamIsRecordedThenTriedAgainAfterPausesThatDouble() {
        val harness = Harness(attemptsWanted = 5)
        harness.run()
        assertEquals(5, harness.attempts)
        // The fifth failure is not recorded: by then the stream is no longer wanted.
        assertEquals(List(4) { IllegalStateException("boom").toString() }, harness.errors)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), harness.pauses)
    }

    @Test
    fun thePauseStopsGrowingAtTheLongest() {
        val harness = Harness(attemptsWanted = 10)
        harness.run()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), harness.pauses)
    }

    @Test
    fun aCollectionThatLastedAMinuteStartsThePausesAgainFromTheFirst() {
        val harness = Harness(attemptsWanted = 5)
        harness.run {
            // The third collection delivers for a minute before failing.
            if (harness.attempts == 3) harness.nowMs += SETTLED_MS
            throw IllegalStateException("boom")
        }
        assertEquals(listOf(1_000L, 2_000L, 1_000L, 2_000L), harness.pauses)
    }

    @Test
    fun aStreamThatEndsWithoutAnErrorIsRecordedAsEndingAndTriedAgain() {
        val harness = Harness(attemptsWanted = 2)
        harness.run { }
        assertEquals(listOf(ENDED_WITHOUT_ERROR), harness.errors)
        assertEquals(2, harness.attempts)
    }

    @Test
    fun aFailureToStoreEndsStreamingWithItsCauseInsteadOfBeingRecorded() {
        val harness = Harness(attemptsWanted = 5)
        val thrown = assertThrows(IOException::class.java) {
            harness.run { stored { throw IOException("no space left") } }
        }
        assertEquals("no space left", thrown.message)
        assertEquals(1, harness.attempts)
        assertEquals(emptyList<String>(), harness.errors)
    }

    @Test
    fun noLongerWantedDuringThePauseMeansNoFurtherAttempt() {
        var wanted = true
        var attempts = 0
        runBlocking {
            keepStreaming(
                attempt = {
                    attempts++
                    throw IllegalStateException("boom")
                },
                onError = {},
                wanted = { wanted },
                nowMs = { 0L },
                // The strap disconnects while the stream waits to be tried again.
                pause = { wanted = false },
            )
        }
        assertEquals(1, attempts)
    }
}
