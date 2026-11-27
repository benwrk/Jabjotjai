package com.benwrk.jabjotjai.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoScaleTest {

    private val second = 1_000_000_000L

    /**
     * An ECG-like trace: [seconds] seconds at 100 Hz of [baseline], with one peak of [peak] and one
     * trough of [trough] in each second, ending at sensor time [seconds] s.
     */
    private fun beats(seconds: Int, baseline: Int = 0, peak: Int, trough: Int): TraceSnapshot {
        val n = seconds * 100
        val t = LongArray(n) { (it + 1) * (second / 100) }
        val v = IntArray(n) {
            when (it % 100) {
                30 -> peak
                33 -> trough
                else -> baseline
            }
        }
        return TraceSnapshot(t, v)
    }

    @Test
    fun theRangeSetsAsideTheTwoMostExtremeSecondsAtEachEnd() {
        val trace = beats(8, peak = 2_000, trough = -1_000)
        // Two artifact seconds above and two below must not widen the range; a third would.
        trace.values[530] = 7_500
        trace.values[630] = 6_000
        trace.values[233] = -4_000
        trace.values[133] = -3_000
        assertEquals(-1_000..2_000, AutoScale.robustRange(listOf(trace)))
        trace.values[730] = 5_000
        assertEquals(-1_000..5_000, AutoScale.robustRange(listOf(trace)))
    }

    @Test
    fun theRangeOfSeveralTracesIsTheirUnion() {
        val x = beats(8, baseline = -200, peak = -100, trough = -300)
        val z = beats(8, baseline = 1_000, peak = 1_100, trough = 900)
        assertEquals(-300..1_100, AutoScale.robustRange(listOf(x, z)))
    }

    @Test
    fun noSamplesMeansNoRange() {
        assertNull(AutoScale.robustRange(listOf(TraceSnapshot.EMPTY)))
    }

    @Test
    fun stepsDownAtOnceWhenTheTraceWouldNotFit() {
        // R +2.3 mV, S -1.2 mV: 3.5 mV. In a 25 mm band, 80 % is 20 mm, so 5 mm/mV (17.5 mm) is
        // the largest step that fits; 10 mm/mV would need 35 mm.
        val auto = AutoScale(ScaleLadder.ECG)
        val scale = auto.update(listOf(beats(8, peak = 2_300, trough = -1_200)), bandMm = 25f, nowNs = 0)
        assertEquals(5f, ScaleLadder.ECG.mmPerUnit(scale.index), 0f)
    }

    @Test
    fun recentresAnAsymmetricTraceSoItFits() {
        // Centred on 0 at 5 mm/mV, +2.3 mV would reach 11.5 mm above the middle of a 25 mm band,
        // past its 10 mm of usable half height; centred on its own middle it spans ±8.75 mm.
        val auto = AutoScale(ScaleLadder.ECG)
        val scale = auto.update(listOf(beats(8, peak = 2_300, trough = -1_200)), bandMm = 25f, nowNs = 0)
        assertEquals(550, scale.centre)
    }

    @Test
    fun stepsUpOnlyAfterTheLargerStepHasFittedForTheWholeHoldTime() {
        val auto = AutoScale(ScaleLadder.ECG, initial = BandScale(index = 0, centre = 0))
        val small = listOf(beats(8, peak = 400, trough = -400))
        // 0.8 mV fits 20 mm/mV (16 mm) in a 25 mm band, but not at once.
        assertEquals(0, auto.update(small, 25f, nowNs = 0).index)
        assertEquals(0, auto.update(small, 25f, nowNs = AutoScale.RAISE_AFTER_NS - 1).index)
        assertEquals(20f, ScaleLadder.ECG.mmPerUnit(auto.update(small, 25f, nowNs = AutoScale.RAISE_AFTER_NS).index), 0f)
    }

    @Test
    fun aMomentWhenTheLargerStepNoLongerFitsRestartsTheHold() {
        val auto = AutoScale(ScaleLadder.ECG, initial = BandScale(index = 1, centre = 0))
        val small = listOf(beats(8, peak = 400, trough = -400))
        val fitsCurrent = listOf(beats(8, peak = 1_500, trough = -1_500))
        auto.update(small, 25f, nowNs = 0)
        auto.update(fitsCurrent, 25f, nowNs = 5 * second)
        // The hold restarts from here, so 8 s after the first look is not enough.
        auto.update(small, 25f, nowNs = 6 * second)
        assertEquals(1, auto.update(small, 25f, nowNs = AutoScale.RAISE_AFTER_NS).index)
        assertEquals(3, auto.update(small, 25f, nowNs = 6 * second + AutoScale.RAISE_AFTER_NS).index)
    }

    @Test
    fun aSmallDriftDoesNotMoveTheBand() {
        val auto = AutoScale(ScaleLadder.ECG, initial = BandScale(index = 1, centre = 550))
        // Middle 600, 50 µV from the centre: 0.25 mm at 5 mm/mV, under 10 % of the band.
        val scale = auto.update(listOf(beats(8, peak = 2_350, trough = -1_150)), bandMm = 25f, nowNs = 0)
        assertEquals(BandScale(index = 1, centre = 550), scale)
    }

    @Test
    fun aFlatTraceIsNotMagnifiedPastTheLaddersMinimumSpan() {
        // A span of zero counts as 0.5 mV: 20 mm at 40 mm/mV, within 80 % of a 26 mm band but not
        // of a 24 mm one.
        val auto = AutoScale(ScaleLadder.ECG, initial = BandScale(index = 4, centre = 0))
        val flat = TraceSnapshot(LongArray(800) { it * (second / 100) }, IntArray(800))
        assertEquals(4, auto.update(listOf(flat), bandMm = 26f, nowNs = 0).index)
        assertEquals(3, auto.update(listOf(flat), bandMm = 24f, nowNs = 0).index)
    }

    @Test
    fun whenNothingFitsItUsesTheSmallestStep() {
        val auto = AutoScale(ScaleLadder.ACC)
        val violent = listOf(beats(8, peak = 8_000, trough = -8_000))
        assertEquals(0, auto.update(violent, bandMm = 10f, nowNs = 0).index)
    }

    @Test
    fun withoutSamplesOrABandTheScaleStaysAsItWas() {
        val initial = BandScale(index = 3, centre = 120)
        val auto = AutoScale(ScaleLadder.ECG, initial)
        assertEquals(initial, auto.update(listOf(TraceSnapshot.EMPTY), bandMm = 25f, nowNs = 0))
        assertEquals(initial, auto.update(listOf(beats(8, peak = 5_000, trough = -5_000)), bandMm = 0f, nowNs = 0))
    }
}
