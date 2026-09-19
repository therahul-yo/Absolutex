package com.absolutex.feature.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.absolutex.remote.sync.FtpServer
import com.absolutex.remote.sync.KavitaServer
import com.absolutex.remote.sync.KomgaServer
import com.absolutex.remote.sync.RemoteKind
import com.absolutex.remote.sync.RemoteServer
import com.absolutex.remote.sync.SmbServer

/**
 * Server list: loading, empty, error and content states, pull-to-refresh that syncs,
 * and delete with confirmation (secrets die with the record). Add/edit navigate out
 * through the callbacks the app graph supplies.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    viewModel: ServerListViewModel = hiltViewModel(),
    onAddServer: () -> Unit = {},
    onEditServer: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<RemoteServer?>(null) }
    val refreshLabel = stringResource(R.string.remote_refresh)
    val addLabel = stringResource(R.string.remote_add_server)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.remote_title)) },
                actions = {
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !refreshing,
                        modifier = Modifier.semantics { contentDescription = refreshLabel },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddServer,
                modifier = Modifier.semantics { contentDescription = addLabel },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
    ) { padding ->
        RefreshableContent(
            refreshing = refreshing,
            state = state,
            onRefresh = viewModel::refresh,
            onRetry = viewModel::retry,
            onEditServer = onEditServer,
            onDelete = { pendingDelete = it },
            modifier = Modifier.padding(padding),
        )
    }

    pendingDelete?.let { server ->
        DeleteConfirmDialog(
            name = serverLabel(server),
            onConfirm = {
                viewModel.deleteServer(server.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun RefreshableContent(
    refreshing: Boolean,
    state: ServerListState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onEditServer: (String) -> Unit,
    onDelete: (RemoteServer) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier.fillMaxSize(),
    ) {
        when (state) {
            ServerListState.Loading -> LoadingPane()
            ServerListState.Failed -> FailedPane(onRetry = onRetry)
            is ServerListState.Ready -> {
                if (state.servers.isEmpty()) {
                    EmptyPane()
                } else {
                    ServerRows(
                        servers = state.servers,
                        onEdit = onEditServer,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingPane() {
    val label = stringResource(R.string.remote_loading)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun EmptyPane() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.remote_empty),
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun FailedPane(onRetry: () -> Unit) {
    val label = stringResource(R.string.remote_retry)
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.remote_load_failed))
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Text(stringResource(R.string.remote_retry))
        }
    }
}

@Composable
private fun ServerRows(
    servers: List<RemoteServer>,
    onEdit: (String) -> Unit,
    onDelete: (RemoteServer) -> Unit,
) {
    val deleteLabel = stringResource(R.string.remote_delete)
    LazyColumn(Modifier.fillMaxSize()) {
        items(servers, key = { it.id }) { server ->
            val label = serverLabel(server)
            val subtitle = serverSubtitle(server)
            ListItem(
                headlineContent = { Text(label) },
                supportingContent = { Text(subtitle) },
                trailingContent = {
                    IconButton(
                        onClick = { onDelete(server) },
                        modifier = Modifier.semantics { contentDescription = deleteLabel },
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null)
                    }
                },
                modifier = Modifier
                    .semantics { contentDescription = label }
                    .clickable { onEdit(server.id) },
            )
        }
    }
}

@Composable
private fun DeleteConfirmDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.remote_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_cancel)) }
        },
        text = { Text(stringResource(R.string.remote_delete_confirm, name)) },
    )
}

@Composable
private fun serverLabel(server: RemoteServer): String = when (server) {
    is SmbServer -> "${server.host}/${server.share}"
    is FtpServer -> server.host
    is KomgaServer -> server.baseUrl
    is KavitaServer -> server.baseUrl
}

@Composable
private fun serverSubtitle(server: RemoteServer): String {
    val kind = when (server.kind) {
        RemoteKind.SMB -> stringResource(R.string.remote_kind_smb)
        RemoteKind.FTP -> stringResource(R.string.remote_kind_ftp)
        RemoteKind.KOMGA -> stringResource(R.string.remote_kind_komga)
        RemoteKind.KAVITA -> stringResource(R.string.remote_kind_kavita)
    }
    val user = when (server) {
        is SmbServer -> server.username
        is FtpServer -> server.username
        is KomgaServer -> server.username
        is KavitaServer -> server.username
    }
    return if (user.isNullOrBlank()) kind else "$kind · $user"
}
