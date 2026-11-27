package com.benwrk.jabjotjai.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTest {

    private val s = 1_000_000_000L
    private val start = 100 * s

    @Test
    fun liveIsAlwaysNow() {
        assertEquals(500 * s, Playback.Live.positionNs(500 * s))
    }

    @Test
    fun thePastMovesAtItsSpeedAndStandsStillWhilePaused() {
        val past = Playback.Past(anchorNs = 200 * s, anchorNowNs = 300 * s, speed = 2f, paused = false)
        assertEquals(210 * s, past.positionNs(305 * s))
        assertEquals(200 * s, past.copy(paused = true).positionNs(305 * s))
        assertEquals(202_500_000_000L, past.copy(speed = 0.5f).positionNs(305 * s))
    }

    @Test
    fun seekingKeepsSpeedAndPauseAndIsClampedToTheStart() {
        val past = Playback.Past(anchorNs = 200 * s, anchorNowNs = 300 * s, speed = 4f, paused = true)
        assertEquals(Playback.Past(150 * s, 310 * s, 4f, true), past.seek(150 * s, start, nowNs = 310 * s))
        assertEquals(Playback.Past(start, 310 * s, 4f, true), past.seek(50 * s, start, nowNs = 310 * s))
        assertEquals(Playback.Past(150 * s, 310 * s, 1f, false), Playback.Live.seek(150 * s, start, nowNs = 310 * s))
    }

    @Test
    fun seekingToWithinASecondOfNowGoesLive() {
        val past = Playback.Past(anchorNs = 200 * s, anchorNowNs = 300 * s, speed = 1f, paused = false)
        assertEquals(Playback.Live, past.seek(310 * s - Playback.LIVE_WITHIN_NS, start, nowNs = 310 * s))
        assertEquals(Playback.Past(309 * s - 1, 310 * s, 1f, false), past.seek(309 * s - 1, start, nowNs = 310 * s))
    }

    @Test
    fun aSpeedChangeCarriesOnFromWhereItIs() {
        val past = Playback.Past(anchorNs = 200 * s, anchorNowNs = 300 * s, speed = 2f, paused = false)
        assertEquals(Playback.Past(210 * s, 305 * s, 8f, false), past.withSpeed(8f, nowNs = 305 * s))
    }

    @Test
    fun atLiveOnlyASlowerSpeedLeavesLive() {
        assertEquals(Playback.Live, Playback.Live.withSpeed(4f, nowNs = 300 * s))
        assertEquals(Playback.Past(300 * s, 300 * s, 0.5f, false), Playback.Live.withSpeed(0.5f, nowNs = 300 * s))
    }

    @Test
    fun pausingLiveHoldsTheMomentAndPlayingOnStartsFromIt() {
        val paused = Playback.Live.togglePause(nowNs = 300 * s)
        assertEquals(Playback.Past(300 * s, 300 * s, 1f, true), paused)
        assertEquals(Playback.Past(300 * s, 320 * s, 1f, false), paused.togglePause(nowNs = 320 * s))
    }

    @Test
    fun fasterPlaybackBecomesLiveOnceItHasCaughtUpAndNotBefore() {
        // 20 s behind at 2×: it gains a second per second, and is within a second of live after 19 s.
        val past = Playback.Past(anchorNs = 280 * s, anchorNowNs = 300 * s, speed = 2f, paused = false)
        assertEquals(past, past.caughtUp(nowNs = 318 * s))
        assertEquals(Playback.Live, past.caughtUp(nowNs = 319 * s))
        // Paused or at 1×, it never catches up.
        assertEquals(past.copy(paused = true), past.copy(paused = true).caughtUp(nowNs = 1_000 * s))
        val atOne = past.copy(speed = 1f)
        assertEquals(atOne, atOne.caughtUp(nowNs = 1_000 * s))
    }

    @Test
    fun replayedBeatsMoveOntoTheRealClockAtTheSpeedAndFallSilentWhenTheyShould() {
        val beats = listOf(201 * s, 202 * s)
        val past = Playback.Past(anchorNs = 200 * s, anchorNowNs = 300 * s, speed = 2f, paused = false)
        assertEquals(listOf(300_500_000_000L, 301 * s), replayBeatsOnRealClock(beats, past))
        assertEquals(emptyList<Long>(), replayBeatsOnRealClock(beats, past.copy(paused = true)))
        assertEquals(emptyList<Long>(), replayBeatsOnRealClock(beats, past.copy(speed = 4f)))
        assertNull(replayBeatsOnRealClock(beats, Playback.Live))
    }
}
