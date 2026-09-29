package com.absolutex.core.data.backup

/** Names and sizes only: never export a fallback URI, even if its last segment resembles a size. */
internal fun validBackupIdentity(value: String): Boolean =
    value.length <= MAX_TEXT && value.substringBeforeLast(':', "").isNotBlank() &&
        "://" !in value && value.none { it.isISOControl() } &&
        value.substringAfterLast(':', "").toLongOrNull()?.let { it >= 0 } == true
