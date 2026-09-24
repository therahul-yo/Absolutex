package com.absolutex.feature.remote

import android.net.Uri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument

/** Server list entry point the lead attaches to the app graph; MainActivity stays lead-owned. */
const val REMOTE_LIST_ROUTE = "remote"

/** Add-and-edit form; absent `serverId` means a new server. */
const val REMOTE_FORM_ROUTE = "remote/form?serverId={serverId}"

/** Folder browser for one file server, with its start folder as an optional argument. */
const val REMOTE_BROWSE_BASE = "remote/browse"
const val REMOTE_BROWSE_ROUTE = "remote/browse/{serverId}?path={path}"

/**
 * Browse route for a server, optionally starting below its root. The path rides
 * [Uri.encode]d so hostile names (`+`, encoded slashes, non-ASCII) survive as one
 * argument; the [BrowseViewModel] decodes it exactly once from the SavedStateHandle.
 */
fun browseRoute(serverId: String, path: String? = null): String {
    val base = "$REMOTE_BROWSE_BASE/$serverId"
    return if (path == null) base else "$base?path=${Uri.encode(path)}"
}

/**
 * Remote servers destination for the app-level NavHost. Wired in MainActivity next to
 * `settingsDestination()`, which carries the "Remote servers" row that navigates here — the
 * host supplies that route because only the host knows the graph. The list and form
 * destinations below own everything past that navigation; back stays the host's concern.
 *
 * Browse opens a book by navigating the host to `"reader/" + Uri.encode(remoteUri)` —
 * pass an `onOpenBook` that does exactly that navigate and nothing else.
 */
fun NavGraphBuilder.remoteDestination(
    onOpenForm: (String?) -> Unit = {},
    onFormDone: () -> Unit = {},
    onOpenBrowse: (String) -> Unit = {},
    onOpenBook: (String) -> Unit = {},
    onBrowseBack: () -> Unit = {},
) {
    composable(REMOTE_LIST_ROUTE) {
        ServerListScreen(
            onAddServer = { onOpenForm(null) },
            onEditServer = { onOpenForm(it) },
            onOpenServer = onOpenBrowse,
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
    composable(
        REMOTE_BROWSE_ROUTE,
        arguments = listOf(
            navArgument("serverId") { type = NavType.StringType },
            navArgument("path") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) {
        BrowseScreen(
            onOpenBook = onOpenBook,
            onBack = onBrowseBack,
        )
    }
}
