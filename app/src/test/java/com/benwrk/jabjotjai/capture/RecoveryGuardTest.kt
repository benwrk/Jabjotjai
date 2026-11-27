package com.benwrk.jabjotjai.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RecoveryGuardTest {

    @Test
    fun aLookRunsWhenNoCaptureIsHeld() {
        assertEquals("found", RecoveryGuard().unlessHeld { "found" })
    }

    @Test
    fun whileACaptureIsHeldALookFindsNothingAndDoesNotRun() {
        val guard = RecoveryGuard()
        guard.hold()
        var looked = false
        assertNull(guard.unlessHeld { looked = true; "found" })
        assertEquals(false, looked)
    }

    @Test
    fun releasingForgetsFirstThenLetsALookRunAgain() {
        val guard = RecoveryGuard()
        guard.hold()
        val events = mutableListOf<String>()
        guard.release { events += "forgotten" }
        assertEquals("found", guard.unlessHeld { events += "looked"; "found" })
        assertEquals(listOf("forgotten", "looked"), events)
    }

    /**
     * The case the guard exists for: a look that began before a capture started must not read the
     * remembered capture after the start has replaced it, so holding waits for the look to finish.
     */
    @Test
    fun holdingWaitsForALookAlreadyUnderWay() {
        val guard = RecoveryGuard()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val looking = CountDownLatch(1)
        val mayFinish = CountDownLatch(1)
        val look = thread {
            guard.unlessHeld {
                looking.countDown()
                mayFinish.await()
                events += "look finished"
                "found"
            }
        }
        assertTrue("the look started", looking.await(5, TimeUnit.SECONDS))
        val start = thread {
            guard.hold()
            events += "held"
        }
        // The start is waiting on the guard's lock while the look runs.
        val waitingUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (start.state != Thread.State.BLOCKED && System.nanoTime() < waitingUntil) Thread.sleep(1)
        assertEquals(Thread.State.BLOCKED, start.state)
        assertEquals(emptyList<String>(), events.toList())
        mayFinish.countDown()
        look.join(5_000)
        start.join(5_000)
        assertEquals(listOf("look finished", "held"), events.toList())
    }
}
