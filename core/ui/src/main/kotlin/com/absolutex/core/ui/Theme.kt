package com.absolutex.core.ui

import android.graphics.Typeface
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
    primary = Ink.Caption,
    onPrimary = Ink.Black,
    primaryContainer = Ink.CaptionDeep,
    onPrimaryContainer = Ink.Caption,
    secondary = Ink.Paper,
    onSecondary = Ink.Black,
    secondaryContainer = Ink.PanelHigh,
    onSecondaryContainer = Ink.Paper,
    tertiary = Ink.Caption,
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
    inversePrimary = Ink.CaptionDeep,
    scrim = Ink.Black,
)

/** The same identity on paper, for whoever chooses the light theme. */
private val InkLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Ink.Caption,
    primaryContainer = Ink.Caption,
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

/**
 * Roboto Condensed for titles, Roboto for reading. Both ship on every Android device, so the
 * masthead costs nothing in the APK — which has little headroom under its size ceiling.
 */
private val Condensed = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
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
    displayLarge = type(Condensed, FontWeight.Black, 52, 56, -0.02),
    displayMedium = type(Condensed, FontWeight.Black, 40, 44, -0.015),
    displaySmall = type(Condensed, FontWeight.ExtraBold, 32, 36, -0.01),
    headlineLarge = type(Condensed, FontWeight.ExtraBold, 30, 34, -0.01),
    headlineMedium = type(Condensed, FontWeight.Bold, 26, 30),
    headlineSmall = type(Condensed, FontWeight.Bold, 22, 26),
    titleLarge = type(Condensed, FontWeight.Bold, 22, 26),
    titleMedium = type(Body, FontWeight.SemiBold, 16, 22, 0.005),
    titleSmall = type(Body, FontWeight.SemiBold, 14, 20),
    bodyLarge = type(Body, FontWeight.Normal, 16, 24),
    bodyMedium = type(Body, FontWeight.Normal, 14, 20),
    bodySmall = type(Body, FontWeight.Normal, 12, 16, 0.01),
    labelLarge = type(Body, FontWeight.SemiBold, 14, 20, 0.01),
    labelMedium = type(Body, FontWeight.Medium, 12, 16, 0.02),
    labelSmall = type(Body, FontWeight.Medium, 11, 16, 0.02),
)

/** Radius follows rank: a chip is barely softened, a sheet is generously so. */
private val InkShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
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
