package com.benwrk.jabjotjai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The bedside-monitor palette: saturated traces on black, one colour per channel, and the same
 * colour for a channel's waveform and its numerics.
 */
object MonitorColors {
    val Background = Color(0xFF000000)
    val Ecg = Color(0xFF00F060)
    val AccX = Color(0xFFFFD400)
    val AccY = Color(0xFF00E0FF)
    val AccZ = Color(0xFFFF6BFF)
    val Text = Color(0xFFEDEDED)
    val Secondary = Color(0xFF9A9A9A)
    val Divider = Color(0xFF262626)
    val Warning = Color(0xFFFFB020)
    val Alarm = Color(0xFFFF3B30)
}
