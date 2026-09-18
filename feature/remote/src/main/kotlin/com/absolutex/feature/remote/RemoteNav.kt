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
 * builder in MainActivity, next to `settingsDestination()`. Pass `onOpenForm = { id ->
 * nav.navigate(if (id == null) "remote/form" else "remote/form?serverId=$id") }` and
 * `onFormDone = { nav.popBackStack() }`; back navigation otherwise stays the host's
 * concern. Also add `implementation(project(":feature:remote"))` to app/build.gradle.kts —
 * that dependency is what ships the transports, so report the APK delta and re-arm the
 * baseline with the user when you do. Do not edit MainActivity or the NavHost from this lane.
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
