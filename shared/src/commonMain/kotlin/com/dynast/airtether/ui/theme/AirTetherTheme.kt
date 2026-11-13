package com.dynast.airtether.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val StudioBackground = Color(0xFF0B0E14)
val StudioSurface = Color(0xFF131822)
val StudioSurfaceVariant = Color(0xFF1C2331)
val StudioBorder = Color(0xFF2A3447)
val StudioPrimary = Color(0xFF38BDF8)
val StudioSecondary = Color(0xFF10B981)
val StudioWarning = Color(0xFFF59E0B)
val StudioDanger = Color(0xFFF43F5E)
val StudioTextPrimary = Color(0xFFF1F5F9)
val StudioTextSecondary = Color(0xFF94A3B8)

private val AirTetherDarkColorScheme = darkColorScheme(
    primary = StudioPrimary,
    onPrimary = Color(0xFF06131F),
    secondary = StudioSecondary,
    onSecondary = Color(0xFF041E14),
    tertiary = StudioWarning,
    background = StudioBackground,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = StudioTextSecondary,
    outline = StudioBorder,
    error = StudioDanger
)

@Composable
fun AirTetherTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = AirTetherDarkColorScheme,
        content = content
    )
}
