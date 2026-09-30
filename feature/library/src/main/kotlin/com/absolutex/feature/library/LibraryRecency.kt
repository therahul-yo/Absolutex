package com.absolutex.feature.library

/** The same ongoing comic selection for every library layout, including page-one openings. */
internal fun List<LibraryBookUi>.continueReadingBooks(): List<LibraryBookUi> =
    filter { !it.isBook && it.readState == ReadState.IN_PROGRESS }
        .sortedByDescending { it.lastReadAt ?: 0L }
        .take(CONTINUE_LIMIT)
