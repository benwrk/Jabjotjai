package com.benwrk.jabjotjai.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.benwrk.jabjotjai.R

/**
 * The monitor's numbers and technical labels: JetBrains Mono, a monospaced face shipped inside the
 * app, so the monitor looks the same on every phone whatever fonts the phone has, and digits keep
 * their place as they change, as on a bedside monitor. The file is the variable-weight one from the
 * Google Fonts repository (ofl/jetbrainsmono), under the SIL Open Font License 1.1, whose text ships
 * with the app in `assets/licenses/`. Each weight is a setting of that one file; choosing one is
 * still marked experimental in Compose.
 */
@OptIn(ExperimentalTextApi::class)
val MonitorMono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Light, variationSettings = FontVariation.Settings(FontVariation.weight(300))),
    Font(R.font.jetbrains_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

/**
 * This text padded on the left to [cells] characters. In [MonitorMono] every character, a space
 * included, is as wide as any other, so the text then always takes the same width and what is
 * beside it does not move as its value changes; a longer value still shows whole.
 */
fun String.inCells(cells: Int): String = padStart(cells)
