package com.benwrk.jabjotjai.monitor

/** The sweep speeds a monitor conventionally offers, in millimetres of screen per second. */
enum class SweepSpeed(val mmPerSecond: Float) {
    MM_6_25(6.25f),
    MM_12_5(12.5f),
    MM_25(25f),
    MM_50(50f),
}

/**
 * The scale steps one band offers, smallest first, each in millimetres of screen per display unit
 * (a millivolt or a g). Samples arrive in thousandths of that unit: microvolts or milli-g.
 *
 * @param standardIndex the step a fixed scale starts at
 * @param minSpanSamples the smallest stretch auto scaling will fill the band with, so a flat
 *   trace is not blown up until its noise fills the screen
 */
class ScaleLadder(
    private val mmPerUnit: FloatArray,
    val standardIndex: Int,
    val minSpanSamples: Int,
) {
    val size: Int get() = mmPerUnit.size

    fun mmPerUnit(index: Int): Float = mmPerUnit[index]

    fun mmPerSample(index: Int): Float = mmPerUnit[index] / SAMPLES_PER_UNIT

    /** The largest step at which [spanSamples] takes at most [limitMm], or the smallest step if none does. */
    fun largestFitting(spanSamples: Int, limitMm: Float): Int {
        for (i in mmPerUnit.indices.reversed()) {
            if (spanSamples * mmPerSample(i) <= limitMm) return i
        }
        return 0
    }

    companion object {
        /** Microvolts per millivolt, and milli-g per g. */
        const val SAMPLES_PER_UNIT: Float = 1_000f

        /** ECG gain from ×0.25 to ×4 of the conventional 10 mm/mV. Samples are microvolts. */
        val ECG = ScaleLadder(floatArrayOf(2.5f, 5f, 10f, 20f, 40f), standardIndex = 2, minSpanSamples = 500)

        /**
         * Accelerometer scale, in millimetres per g. The smallest step fits the strap's whole
         * ±8 g range in a band about 20 mm tall. Samples are milli-g.
         */
        val ACC = ScaleLadder(floatArrayOf(1.25f, 2.5f, 5f, 10f, 20f, 40f), standardIndex = 2, minSpanSamples = 100)
    }
}

/**
 * How the monitor draws and sounds, as the user set it. A fixed scale draws a band's zero in its
 * middle; an automatic one also moves the band to follow the trace (see [AutoScale]).
 *
 * @param ecgGainIndex the ECG step, in [ScaleLadder.ECG], used while [ecgAuto] is off
 * @param accScaleIndex the accelerometer step, in [ScaleLadder.ACC], used while [accAuto] is off
 * @param beep a beep at each beat while the monitor is on screen: off is muted
 * @param beepVolume the beep's own level, from 0 to 1, under the media volume; see [beepGain]
 * @param beepInBackground with [beep], keep beeping while the monitor is not on screen as well
 * @param timeFromStart the timeline's time counts from the capture's start rather than back from live
 * @param rows the chart's rows, top to bottom; see [chartGraphs]
 * @param chartUnderCutout the chart reaches under the camera cut-out, its labels kept clear of the camera
 */
data class DisplaySettings(
    val sweep: SweepSpeed = SweepSpeed.MM_25,
    val ecgAuto: Boolean = true,
    val ecgGainIndex: Int = ScaleLadder.ECG.standardIndex,
    val accAuto: Boolean = true,
    val accScaleIndex: Int = ScaleLadder.ACC.standardIndex,
    val calibrationPulse: Boolean = true,
    val beep: Boolean = false,
    val beepInBackground: Boolean = false,
    val beepVolume: Float = 1f,
    val timeFromStart: Boolean = false,
    val rows: List<ChartRow> = DEFAULT_CHART_ROWS,
    val chartUnderCutout: Boolean = false,
)

/** Where a band's middle line sits, in sample units, and which step of its ladder it draws at. */
data class BandScale(val index: Int, val centre: Int)

/**
 * The gain the beep is played at: the square of [DisplaySettings.beepVolume], so the slider's lower
 * half is not nearly as loud as its top, as a straight line from 0 to 1 would be. The phone's media
 * volume applies on top.
 */
val DisplaySettings.beepGain: Float get() = beepVolume * beepVolume
