package com.absolutex.core.data.settings

/** Toggle against the current value inside the writer's edit, never a stale UI snapshot. */
suspend fun SettingsWriter.toggleEnhance() {
    updateRendering { it.copy(enhanceEnabled = !it.enhanceEnabled) }
}
