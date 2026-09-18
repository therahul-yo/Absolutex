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
 * Remote servers destination for the app-level NavHost.
 *
 * TODO(agent3): attach from the app graph with `remoteDestination()` inside the NavHost
 * builder, next to `settingsDestination()`. The list navigates to the form through
 * [onOpenForm] (null id means a new server) and the form reports completion through
 * [onFormDone] — wire both to the host NavController; back navigation otherwise stays the
 * host's concern. Do not edit MainActivity or the NavHost from this lane.
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
