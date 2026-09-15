package com.absolutex.feature.settings

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/** Route the lead attaches to the app graph; MainActivity stays lead-owned. */
const val SETTINGS_ROUTE = "settings"

/**
 * Settings destination for the app-level NavHost.
 *
 * Handoff for the lead: attach from the app graph with
 * `settingsDestination()` inside the NavHost builder — no arguments, no result contract. The
 * screen reads its own ViewModel via Hilt and previews the theme it configures, so there is
 * nothing to pass in. Back navigation is the host's concern: this destination pushes nothing
 * and pops nothing itself.
 */
fun NavGraphBuilder.settingsDestination() {
    composable(SETTINGS_ROUTE) { SettingsScreen() }
}
