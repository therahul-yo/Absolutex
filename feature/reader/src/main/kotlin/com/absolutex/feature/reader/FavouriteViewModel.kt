package com.absolutex.feature.reader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.absolutex.core.data.BookFactsDao
import com.absolutex.core.ui.Motion
import com.absolutex.core.ui.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A book's favourite flag, read and written by the identity the reader already has. */
@HiltViewModel
class FavouriteViewModel @Inject constructor(private val facts: BookFactsDao) : ViewModel() {
    fun favourite(bookId: String): Flow<Boolean?> = facts.observeFavourite(bookId)

    fun set(bookId: String, favourite: Boolean) {
        viewModelScope.launch { facts.setFavourite(bookId, favourite) }
    }
}

/**
 * The heart in the reader's top bar: favourite the book without leaving it. Absent for a book the
 * library does not hold, which has no shelf to be a favourite on. The heart pops as it fills, with
 * a confirming haptic.
 */
@Composable
internal fun FavouriteButton(bookId: String, vm: FavouriteViewModel = hiltViewModel()) {
    if (bookId.isEmpty()) return
    val favourite by remember(bookId) { vm.favourite(bookId) }.collectAsStateWithLifecycle(null)
    val current = favourite ?: return
    val haptics = rememberHaptics()
    IconButton(onClick = {
        haptics.confirm()
        vm.set(bookId, !current)
    }) {
        AnimatedContent(
            current,
            transitionSpec = { scaleIn(Motion.press(), initialScale = POP_FROM) + fadeIn() togetherWith fadeOut() },
            label = "favourite",
        ) { on ->
            Icon(
                if (on) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = stringResource(if (on) R.string.reader_unfavourite else R.string.reader_favourite),
            )
        }
    }
}

private const val POP_FROM = 0.6f
