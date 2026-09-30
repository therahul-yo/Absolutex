package com.absolutex.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The header every top-level page shares: an optional back button, the [title] (an [AsciiTitle]),
 * and [actions] at the end.
 *
 * A plain row rather than a TopAppBar so a row beneath it can share its grid exactly: a 16 dp
 * gutter each side and 8 dp between 48 dp buttons, so buttons stack in columns. Its height is a
 * TopAppBar's, so a bar that replaces it (the library's selection bar) swaps in without a jump.
 * The status-bar inset is the caller's to add.
 */
@Composable
fun PageHeader(
    modifier: Modifier = Modifier,
    navigation: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    title: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigation()
        // A gap before the buttons: the longest title fills the width otherwise.
        Box(Modifier.weight(1f).padding(end = 12.dp), contentAlignment = Alignment.CenterStart) { title() }
        actions()
    }
}

private val HEADER_HEIGHT = 64.dp
