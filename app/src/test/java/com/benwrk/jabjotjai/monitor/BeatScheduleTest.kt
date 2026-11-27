package com.benwrk.jabjotjai.monitor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BeatScheduleTest {

    private val ms = 1_000_000L

    @Test
    fun theFirstRecordPlacesItsNewestBeatAtArrivalAndTheOthersBackFromIt() {
        val beats = BeatSchedule()
        beats.accept(10_000 * ms, listOf(800 * ms, 900 * ms))
        assertArrayEquals(longArrayOf(9_100 * ms, 10_000 * ms), beats.recent())
    }

    @Test
    fun laterBeatsFollowOneIntervalAfterAnotherWhateverTheArrivalTimes() {
        val beats = BeatSchedule()
        beats.accept(10_000 * ms, listOf(1_000 * ms))
        beats.accept(10_950 * ms, listOf(1_000 * ms))
        beats.accept(11_990 * ms, listOf(1_000 * ms, 1_000 * ms))
        assertArrayEquals(longArrayOf(10_000 * ms, 11_000 * ms, 12_000 * ms, 13_000 * ms), beats.recent())
    }

    @Test
    fun aBeatReportedAfterItsSlotMovesToItsArrivalAndLaterBeatsFollowFromThere() {
        val beats = BeatSchedule()
        beats.accept(0, listOf(1_000 * ms))
        beats.accept(1_500 * ms, listOf(1_000 * ms))
        beats.accept(2_400 * ms, listOf(1_000 * ms))
        assertArrayEquals(longArrayOf(0, 1_500 * ms, 2_500 * ms), beats.recent())
    }

    @Test
    fun aSilenceLongerThanTheRestartTimeStartsANewSequence() {
        val beats = BeatSchedule()
        beats.accept(0, listOf(1_000 * ms))
        beats.accept(3_500 * ms, listOf(800 * ms, 900 * ms))
        // Continuing the old sequence would have given 3500 and 4400.
        assertArrayEquals(longArrayOf(0, 2_600 * ms, 3_500 * ms), beats.recent())
    }

    @Test
    fun aRestartStartsANewSequenceAtTheNextRecord() {
        val beats = BeatSchedule()
        beats.accept(0, listOf(1_000 * ms))
        beats.restart()
        beats.accept(1_200 * ms, listOf(800 * ms, 900 * ms))
        // Continuing would have given 1200 and 2100.
        assertArrayEquals(longArrayOf(0, 300 * ms, 1_200 * ms), beats.recent())
    }

    @Test
    fun aNewSequenceDoesNotRepeatBeatsAlreadyDue() {
        val beats = BeatSchedule()
        beats.accept(0, listOf(1_000 * ms))
        beats.accept(500 * ms, listOf(1_000 * ms))
        beats.restart()
        // Both new beats fall before the 1000 ms beat already placed, so neither is added.
        beats.accept(900 * ms, listOf(500 * ms, 300 * ms))
        beats.accept(1_800 * ms, listOf(900 * ms))
        assertArrayEquals(longArrayOf(0, 1_000 * ms, 1_800 * ms), beats.recent())
    }

    @Test
    fun aSequenceThatHasDriftedTooLateStartsAgainFromTheArrival() {
        val beats = BeatSchedule()
        beats.accept(0, listOf(1_000 * ms))
        // 0 + 3000 is more than 2 s after this record's arrival at 100.
        beats.accept(100 * ms, listOf(3_000 * ms))
        assertArrayEquals(longArrayOf(0, 100 * ms), beats.recent())
    }

    @Test
    fun aRecordWithoutIntervalsAddsNoBeat() {
        val beats = BeatSchedule()
        beats.accept(0, emptyList())
        assertEquals(0, beats.recent().size)
    }

    @Test
    fun onlyTheLastTenSecondsAreReturnedHoweverManyBeatsWerePlaced() {
        val beats = BeatSchedule()
        beats.accept(0, listOf(1_000 * ms))
        for (second in 1..40) beats.accept(second * 1_000 * ms, listOf(1_000 * ms))
        val recent = beats.recent()
        assertEquals(30_000 * ms, recent.first())
        assertEquals(40_000 * ms, recent.last())
        assertEquals(11, recent.size)
    }
}
