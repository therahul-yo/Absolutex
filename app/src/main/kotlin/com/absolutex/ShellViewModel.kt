package com.absolutex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.LastBookStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val lastBookStore: LastBookStore,
) : ViewModel() {

    suspend fun lastBook(): String? = lastBookStore.get()

    fun rememberBook(uri: String) {
        viewModelScope.launch { lastBookStore.set(uri) }
    }

    fun clearLastBook() {
        viewModelScope.launch { lastBookStore.clear() }
    }
}
