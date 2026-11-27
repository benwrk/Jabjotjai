package com.benwrk.jabjotjai.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamSettingsTest {

    private val h10Accelerometer = mapOf(
        "rate" to setOf(25, 50, 100, 200),
        "range" to setOf(2, 4, 8),
        "bits" to setOf(16),
    )

    @Test
    fun takesThePreferredValueWhenItIsOffered() {
        assertEquals(
            mapOf("rate" to 200, "range" to 8, "bits" to 16),
            StreamSettings.choose(h10Accelerometer, preferred = mapOf("rate" to 200, "range" to 8)),
        )
    }

    @Test
    fun fallsBackToTheHighestOfferedValueWhenThePreferenceIsNotOffered() {
        assertEquals(
            mapOf("rate" to 200, "range" to 8, "bits" to 16),
            StreamSettings.choose(h10Accelerometer, preferred = mapOf("rate" to 400, "range" to 16)),
        )
    }

    @Test
    fun aPreferenceForASettingThatIsNotOfferedAddsNothing() {
        assertEquals(mapOf("rate" to 130), StreamSettings.choose(mapOf("rate" to setOf(130)), preferred = mapOf("range" to 8)))
    }

    @Test
    fun aSettingOfferedWithNoValuesIsAnError() {
        assertThrows(NoSuchElementException::class.java) { StreamSettings.choose(mapOf("rate" to emptySet()), emptyMap()) }
    }
}
