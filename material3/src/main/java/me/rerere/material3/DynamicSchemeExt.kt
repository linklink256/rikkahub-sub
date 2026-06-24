package me.rerere.material3

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dynamiccolor.DynamicScheme

fun DynamicScheme.toColorScheme(): ColorScheme {
    val s = this
    // ponytail: dark & light branches differed only in the factory call
    // (darkColorScheme vs lightColorScheme); compute the 48 Color values once
    // and reuse for both (field order verified identical between branches).
    val primary = Color(s.primary)
    val onPrimary = Color(s.onPrimary)
    val primaryContainer = Color(s.primaryContainer)
    val onPrimaryContainer = Color(s.onPrimaryContainer)
    val inversePrimary = Color(s.inversePrimary)
    val secondary = Color(s.secondary)
    val onSecondary = Color(s.onSecondary)
    val secondaryContainer = Color(s.secondaryContainer)
    val onSecondaryContainer = Color(s.onSecondaryContainer)
    val tertiary = Color(s.tertiary)
    val onTertiary = Color(s.onTertiary)
    val tertiaryContainer = Color(s.tertiaryContainer)
    val onTertiaryContainer = Color(s.onTertiaryContainer)
    val background = Color(s.background)
    val onBackground = Color(s.onBackground)
    val surface = Color(s.surface)
    val onSurface = Color(s.onSurface)
    val surfaceVariant = Color(s.surfaceVariant)
    val onSurfaceVariant = Color(s.onSurfaceVariant)
    val surfaceTint = Color(s.surfaceTint)
    val inverseSurface = Color(s.inverseSurface)
    val inverseOnSurface = Color(s.inverseOnSurface)
    val error = Color(s.error)
    val onError = Color(s.onError)
    val errorContainer = Color(s.errorContainer)
    val onErrorContainer = Color(s.onErrorContainer)
    val outline = Color(s.outline)
    val outlineVariant = Color(s.outlineVariant)
    val scrim = Color(s.scrim)
    val surfaceBright = Color(s.surfaceBright)
    val surfaceDim = Color(s.surfaceDim)
    val surfaceContainer = Color(s.surfaceContainer)
    val surfaceContainerHigh = Color(s.surfaceContainerHigh)
    val surfaceContainerHighest = Color(s.surfaceContainerHighest)
    val surfaceContainerLow = Color(s.surfaceContainerLow)
    val surfaceContainerLowest = Color(s.surfaceContainerLowest)
    val primaryFixed = Color(s.primaryFixed)
    val primaryFixedDim = Color(s.primaryFixedDim)
    val onPrimaryFixed = Color(s.onPrimaryFixed)
    val onPrimaryFixedVariant = Color(s.onPrimaryFixedVariant)
    val secondaryFixed = Color(s.secondaryFixed)
    val secondaryFixedDim = Color(s.secondaryFixedDim)
    val onSecondaryFixed = Color(s.onSecondaryFixed)
    val onSecondaryFixedVariant = Color(s.onSecondaryFixedVariant)
    val tertiaryFixed = Color(s.tertiaryFixed)
    val tertiaryFixedDim = Color(s.tertiaryFixedDim)
    val onTertiaryFixed = Color(s.onTertiaryFixed)
    val onTertiaryFixedVariant = Color(s.onTertiaryFixedVariant)

    return if (isDark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = surfaceTint,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrim,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            primaryFixed = primaryFixed,
            primaryFixedDim = primaryFixedDim,
            onPrimaryFixed = onPrimaryFixed,
            onPrimaryFixedVariant = onPrimaryFixedVariant,
            secondaryFixed = secondaryFixed,
            secondaryFixedDim = secondaryFixedDim,
            onSecondaryFixed = onSecondaryFixed,
            onSecondaryFixedVariant = onSecondaryFixedVariant,
            tertiaryFixed = tertiaryFixed,
            tertiaryFixedDim = tertiaryFixedDim,
            onTertiaryFixed = onTertiaryFixed,
            onTertiaryFixedVariant = onTertiaryFixedVariant,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            surfaceTint = surfaceTint,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = scrim,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
            primaryFixed = primaryFixed,
            primaryFixedDim = primaryFixedDim,
            onPrimaryFixed = onPrimaryFixed,
            onPrimaryFixedVariant = onPrimaryFixedVariant,
            secondaryFixed = secondaryFixed,
            secondaryFixedDim = secondaryFixedDim,
            onSecondaryFixed = onSecondaryFixed,
            onSecondaryFixedVariant = onSecondaryFixedVariant,
            tertiaryFixed = tertiaryFixed,
            tertiaryFixedDim = tertiaryFixedDim,
            onTertiaryFixed = onTertiaryFixed,
            onTertiaryFixedVariant = onTertiaryFixedVariant,
        )
    }
}