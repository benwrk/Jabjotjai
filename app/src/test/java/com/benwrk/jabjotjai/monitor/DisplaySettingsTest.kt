package com.benwrk.jabjotjai.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySettingsTest {

    /**
     * What a fresh install has and what "Restore defaults" brings back, each as `docs/spec.md` sets
     * it, so that changing one is a decision with a test to change rather than a slip.
     */
    @Test
    fun theDefaultsAreTheOnesTheSpecSets() {
        val defaults = DisplaySettings()
        assertEquals(SweepSpeed.MM_25, defaults.sweep)
        assertEquals(true, defaults.ecgAuto)
        assertEquals(ScaleLadder.ECG.standardIndex, defaults.ecgGainIndex)
        assertEquals(true, defaults.accAuto)
        assertEquals(ScaleLadder.ACC.standardIndex, defaults.accScaleIndex)
        assertEquals(true, defaults.calibrationPulse)
        assertEquals(false, defaults.beep)
        assertEquals(false, defaults.beepInBackground)
        assertEquals(1f, defaults.beepVolume)
        assertEquals(false, defaults.timeFromStart)
        assertEquals(DEFAULT_CHART_ROWS, defaults.rows)
        assertEquals(false, defaults.chartUnderCutout)
        assertEquals(true, defaults.trendRobustScale)
        assertEquals(true, defaults.trendHideNoContact)
        assertEquals(2_000, defaults.rrCeilingMs)
    }
}
