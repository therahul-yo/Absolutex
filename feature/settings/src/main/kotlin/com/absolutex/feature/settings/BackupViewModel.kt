package com.absolutex.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.backup.BackupRepository
import com.absolutex.core.data.backup.FutureBackupVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class BackupState(
    val busy: Boolean = false, val message: Int? = null, val books: Int = 0,
    val arguments: List<Any> = emptyList(), val pendingDropped: Int = 0,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: BackupRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupState())
    val state = mutableState.asStateFlow()

    fun export(uri: Uri) = runOperation {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        val result = backup.export(version)
        (context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()).use { it.write(result.bytes) }
        result.toBackupState()
    }

    fun import(uri: Uri) = runOperation {
        val result = (context.contentResolver.openInputStream(uri) ?: throw IOException()).use { backup.restore(it) }
        result.toBackupState()
    }

    private fun runOperation(action: suspend () -> BackupState) {
        if (mutableState.value.busy) return
        mutableState.value = BackupState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: FutureBackupVersion) {
                BackupState(message = R.string.backup_future_version)
            } catch (_: Exception) {
                BackupState(message = R.string.backup_failed)
            }
        }
    }
}
