package com.absolutex.feature.remote

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/** Server list entry point the lead attaches to the app graph; MainActivity stays lead-owned. */
const val REMOTE_LIST_ROUTE = "remote"

/** Add-and-edit form; absent `serverId` means a new server. */
const val REMOTE_FORM_ROUTE = "remote/form?serverId={serverId}"

/**
 * Remote servers destination for the app-level NavHost. Wired in MainActivity next to
 * `settingsDestination()`; the settings row that navigates to [REMOTE_LIST_ROUTE] is
 * Agent03's screen to add:
 *
 * TODO(agent3): add a "Remote servers" row to the settings screen navigating to
 * `REMOTE_LIST_ROUTE` ("remote"). The list and form destinations below own everything
 * past that navigation; back stays the host's concern.
 */
fun NavGraphBuilder.remoteDestination(
    onOpenForm: (String?) -> Unit = {},
    onFormDone: () -> Unit = {},
) {
    composable(REMOTE_LIST_ROUTE) {
        ServerListScreen(
            onAddServer = { onOpenForm(null) },
            onEditServer = { onOpenForm(it) },
        )
    }
    composable(
        REMOTE_FORM_ROUTE,
        arguments = listOf(navArgument("serverId") {
            type = NavType.StringType
            nullable = true
        }),
    ) {
        ServerFormScreen(onDone = onFormDone)
    }
}
