package com.absolutex.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val InkDark: ColorScheme = darkColorScheme(
    primary = Ink.Accent,
    onPrimary = Ink.Black,
    primaryContainer = Ink.AccentDeep,
    onPrimaryContainer = Ink.Accent,
    secondary = Ink.Paper,
    onSecondary = Ink.Black,
    secondaryContainer = Ink.PanelHigh,
    onSecondaryContainer = Ink.Paper,
    tertiary = Ink.Accent,
    onTertiary = Ink.Black,
    background = Ink.Black,
    onBackground = Ink.Paper,
    surface = Ink.Black,
    onSurface = Ink.Paper,
    surfaceVariant = Ink.PanelRaised,
    onSurfaceVariant = Ink.PaperDim,
    surfaceContainerLowest = Ink.Black,
    surfaceContainerLow = Ink.Panel,
    surfaceContainer = Ink.Panel,
    surfaceContainerHigh = Ink.PanelRaised,
    surfaceContainerHighest = Ink.PanelHigh,
    outline = Ink.Rule,
    outlineVariant = Ink.PanelHigh,
    error = Ink.Alarm,
    onError = Ink.Black,
    inverseSurface = Ink.Paper,
    inverseOnSurface = Ink.Black,
    inversePrimary = Ink.AccentDeep,
    scrim = Ink.Black,
)

/** The same identity on paper, for whoever chooses the light theme. */
private val InkLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Ink.Accent,
    primaryContainer = Color(0xFFE0E0E0),
    onPrimaryContainer = Ink.Black,
    secondary = Color(0xFF3A3A3A),
    background = Ink.Paper,
    onBackground = Ink.Black,
    surface = Ink.Paper,
    onSurface = Ink.Black,
    surfaceVariant = Color(0xFFE6E2DA),
    onSurfaceVariant = Color(0xFF4A4740),
    surfaceContainer = Color(0xFFEAE6DE),
    surfaceContainerHigh = Color(0xFFE2DDD4),
    outline = Color(0xFFB7B2A8),
    error = Color(0xFFB3261E),
)

/** Roboto throughout: the system face, so the type costs nothing in the APK. */
private val Body = FontFamily.SansSerif

private fun type(family: FontFamily, weight: FontWeight, size: Int, line: Int, tracking: Double = 0.0) =
    TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.em,
    )

private val InkTypography = Typography(
    displayLarge = type(Body, FontWeight.Normal, 57, 64, -0.004),
    displayMedium = type(Body, FontWeight.Normal, 45, 52),
    displaySmall = type(Body, FontWeight.Normal, 36, 44),
    headlineLarge = type(Body, FontWeight.Medium, 32, 40),
    headlineMedium = type(Body, FontWeight.Medium, 28, 36),
    headlineSmall = type(Body, FontWeight.Medium, 24, 32),
    titleLarge = type(Body, FontWeight.Medium, 22, 28),
    titleMedium = type(Body, FontWeight.Medium, 16, 24, 0.009),
    titleSmall = type(Body, FontWeight.Medium, 14, 20, 0.007),
    bodyLarge = type(Body, FontWeight.Normal, 16, 24, 0.03),
    bodyMedium = type(Body, FontWeight.Normal, 14, 20, 0.018),
    bodySmall = type(Body, FontWeight.Normal, 12, 16, 0.033),
    labelLarge = type(Body, FontWeight.Medium, 14, 20, 0.007),
    labelMedium = type(Body, FontWeight.Medium, 12, 16, 0.042),
    labelSmall = type(Body, FontWeight.Medium, 11, 16, 0.045),
)

/** Soft, even radii: Material 3 proportions, a step rounder. */
private val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AbsolutexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    trueBlack: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // minSdk 33 means dynamic colour is always available — no version branch (§1).
    var scheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> InkDark
        else -> InkLight
    }
    // The Ink scheme is already true black; this is for wallpaper colours, which are not.
    if (darkTheme && trueBlack) {
        scheme = scheme.copy(background = Ink.Black, surface = Ink.Black, surfaceContainerLowest = Ink.Black)
    }
    MaterialTheme(colorScheme = scheme, typography = InkTypography, shapes = InkShapes, content = content)
}
