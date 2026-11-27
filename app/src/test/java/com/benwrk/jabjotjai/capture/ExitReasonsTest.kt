package com.benwrk.jabjotjai.capture

import com.benwrk.jabjotjai.raw.ProcessExit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitReasonsTest {

    private fun exit(code: Int) = ProcessExit(ExitReasons.name(code), code, wallMs = 0)

    @Test
    fun reasonsAreNamedAsAndroidNamesThemAndAnUnknownCodeKeepsItsNumber() {
        // The ApplicationExitInfo.REASON_* values in the SDK 37 source.
        assertEquals("SIGNALED", ExitReasons.name(2))
        assertEquals("LOW_MEMORY", ExitReasons.name(3))
        assertEquals("CRASH", ExitReasons.name(4))
        assertEquals("USER_REQUESTED", ExitReasons.name(10))
        assertEquals("USER_STOPPED", ExitReasons.name(11))
        assertEquals("PACKAGE_UPDATED", ExitReasons.name(16))
        assertEquals("UNKNOWN_17", ExitReasons.name(17))
    }

    @Test
    fun theSystemStoppingTheAppOrAnUpdateResumesByItself() {
        for (code in listOf(2, 3, 9, 14, 16)) assertTrue(ExitReasons.name(code), ExitReasons.resumesByItself(exit(code)))
    }

    @Test
    fun aStopSomeoneChoseACrashOrNoRecordIsOfferedInstead() {
        // Force stop, the task manager, crashes, a changed permission, and anything unknown.
        for (code in listOf(0, 1, 4, 5, 6, 8, 10, 11, 13, 15, 17)) assertFalse(ExitReasons.name(code), ExitReasons.resumesByItself(exit(code)))
        assertFalse(ExitReasons.resumesByItself(null))
    }
}
