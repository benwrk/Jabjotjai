package com.benwrk.jabjotjai.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle

/**
 * Material's type scale with every style set in [MonitorMono], the font shipped with the app, so
 * the whole app, its start screens, sheets and dialogs included, has the monitor's look on every
 * phone. Sizes, weights and spacing are Material's defaults.
 */
val Typography = Typography().let { material ->
    Typography(
        displayLarge = material.displayLarge.mono(),
        displayMedium = material.displayMedium.mono(),
        displaySmall = material.displaySmall.mono(),
        headlineLarge = material.headlineLarge.mono(),
        headlineMedium = material.headlineMedium.mono(),
        headlineSmall = material.headlineSmall.mono(),
        titleLarge = material.titleLarge.mono(),
        titleMedium = material.titleMedium.mono(),
        titleSmall = material.titleSmall.mono(),
        bodyLarge = material.bodyLarge.mono(),
        bodyMedium = material.bodyMedium.mono(),
        bodySmall = material.bodySmall.mono(),
        labelLarge = material.labelLarge.mono(),
        labelMedium = material.labelMedium.mono(),
        labelSmall = material.labelSmall.mono(),
    )
}

private fun TextStyle.mono() = copy(fontFamily = MonitorMono)
