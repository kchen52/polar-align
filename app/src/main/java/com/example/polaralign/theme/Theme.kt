package com.example.polaralign.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val NightColorScheme =
  darkColorScheme(
    primary = NightRed,
    onPrimary = NightBackground,
    secondary = NightRedSoft,
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnBackground,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightMuted,
    outline = NightOutline,
  )

private val DayColorScheme =
  lightColorScheme(
    primary = DayPrimary,
    onPrimary = DayOnPrimary,
    secondary = DayMuted,
    background = DayBackground,
    onBackground = DayOnBackground,
    surface = DaySurface,
    onSurface = DayOnBackground,
    surfaceVariant = DaySurfaceVariant,
    onSurfaceVariant = DayMuted,
    outline = DayOutline,
  )

@Composable
fun PolarAlignTheme(nightMode: Boolean = true, content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = if (nightMode) NightColorScheme else DayColorScheme,
    typography = Typography,
    content = content,
  )
}
