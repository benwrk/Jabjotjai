package com.benwrk.jabjotjai.ui.monitor

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class CutoutTest {

    // A camera hole at the left edge of a landscape screen, halfway down.
    private val camera = listOf(Rect(left = 0f, top = 500f, right = 100f, bottom = 580f))

    @Test
    fun somethingUnderTheCameraMovesClearOfItWithTheGapToSpare() {
        val label = Rect(left = 20f, top = 520f, right = 220f, bottom = 540f)
        assertEquals(84f, clearanceRight(label, camera, gap = 4f))
    }

    @Test
    fun somethingBesideTheCameraStaysWhereItIs() {
        // Above the hole, as the top band's label is.
        assertEquals(0f, clearanceRight(Rect(20f, 20f, 220f, 40f), camera, gap = 4f))
        // Already to its right.
        assertEquals(0f, clearanceRight(Rect(110f, 520f, 300f, 540f), camera, gap = 4f))
        // No cut-out at all.
        assertEquals(0f, clearanceRight(Rect(20f, 520f, 220f, 540f), emptyList(), gap = 4f))
    }

    @Test
    fun somethingAtTheRightEdgeMovesLeftOfACameraThere() {
        val right = listOf(Rect(left = 2240f, top = 500f, right = 2340f, bottom = 580f))
        val scaleLabel = Rect(left = 2300f, top = 510f, right = 2330f, bottom = 530f)
        assertEquals(94f, clearanceLeft(scaleLabel, right, gap = 4f))
        assertEquals(0f, clearanceLeft(Rect(2300f, 10f, 2330f, 30f), right, gap = 4f))
    }
}
