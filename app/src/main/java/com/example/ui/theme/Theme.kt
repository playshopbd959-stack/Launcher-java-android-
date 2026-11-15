package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val NoxerDarkColorScheme = darkColorScheme(
    primary = NoxerNeonRed,
    onPrimary = NoxerTextPrimary,
    primaryContainer = NoxerCrimsonContainer,
    onPrimaryContainer = NoxerCrimsonGlow,
    secondary = NoxerCyan,
    onSecondary = NoxerBlack,
    secondaryContainer = NoxerSurfaceHigh,
    onSecondaryContainer = NoxerTextPrimary,
    tertiary = NoxerEmerald,
    onTertiary = NoxerBlack,
    background = NoxerBlack,
    onBackground = NoxerTextPrimary,
    surface = NoxerObsidian,
    onSurface = NoxerTextPrimary,
    surfaceVariant = NoxerSurface,
    onSurfaceVariant = NoxerTextSecondary,
    surfaceContainerLowest = NoxerBlack,
    surfaceContainerLow = NoxerObsidian,
    surfaceContainer = NoxerSurface,
    surfaceContainerHigh = NoxerSurfaceElevated,
    surfaceContainerHighest = NoxerSurfaceHigh,
    outline = NoxerBorder,
    outlineVariant = NoxerBorderAccent,
    error = NoxerError,
    onError = NoxerTextPrimary,
)

val NoxerShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun NoxerLauncherTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = NoxerDarkColorScheme,
        typography = NoxerTypography,
        shapes = NoxerShapes,
        content = content,
    )
}
