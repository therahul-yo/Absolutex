package com.absolutex.feature.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.remote.sync.RemoteServers
import com.absolutex.remote.sync.RemoteServer
import com.absolutex.remote.sync.ServerAdmin
import com.absolutex.remote.sync.SyncController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Server-list states: loading, content (possibly empty), or a store failure. */
sealed interface ServerListState {
    data object Loading : ServerListState
    data class Ready(val servers: List<RemoteServer>) : ServerListState
    data object Failed : ServerListState
}

/**
 * Server list for the remote UI. Reads [RemoteServers], deletes through [ServerAdmin] (so
 * secrets die with the record), and refreshes through [SyncController.onManualSync] — the
 * pull-to-refresh gesture syncs, not just re-reads the list.
 */
@HiltViewModel
class ServerListViewModel @Inject constructor(
    servers: RemoteServers,
    private val admin: ServerAdmin,
    private val sync: SyncController,
) : ViewModel() {

    private val reloadTick = MutableStateFlow(0)

    val state: StateFlow<ServerListState> = reloadTick
        .flatMapLatest {
            servers.servers.map<_, ServerListState> { ServerListState.Ready(it) }
        }
        .catch { emit(ServerListState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerListState.Loading)

    private val _refreshing = MutableStateFlow(false)

    /** Pull-to-refresh indicator; true only while a sync pass runs. */
    val refreshing: StateFlow<Boolean> = _refreshing

    fun deleteServer(id: String): Job = viewModelScope.launch { admin.removeServer(id) }

    /** Re-reads the store after a load failure. */
    fun retry() {
        reloadTick.value += 1
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                sync.onManualSync()
            } finally {
                _refreshing.value = false
            }
        }
    }
}
