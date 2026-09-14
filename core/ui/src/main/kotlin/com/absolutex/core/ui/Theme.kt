package com.absolutex.core.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Curated fallback for when dynamic colour is off (§7). */
private val FallbackDark = androidx.compose.material3.darkColorScheme()
private val FallbackLight = androidx.compose.material3.lightColorScheme()

@Composable
fun AbsolutexTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    trueBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // minSdk 33 means dynamic colour is always available — no version branch (§1).
    var scheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    if (darkTheme && trueBlack) {
        scheme = scheme.copy(background = Color.Black, surface = Color.Black)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
