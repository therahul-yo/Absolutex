package com.absolutex.feature.settings

import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.model.FitMode
import com.absolutex.model.ReadingFlow
import org.junit.Assert.assertEquals
import org.junit.Test

// Expected values are always derived from the contract defaults via copy: a literal copy of a
// default here would rot the moment the contract changes without this file failing loudly.
class SettingsReductionsTest {

    @Test
    fun `a cache below the minimum clamps to MIN_CACHE_MIB`() {
        assertEquals(AppPrefs.MIN_CACHE_MIB, clampCacheSizeMiB(AppPrefs.MIN_CACHE_MIB - 1))
    }

    @Test
    fun `a cache above the maximum clamps to MAX_CACHE_MIB`() {
        assertEquals(AppPrefs.MAX_CACHE_MIB, clampCacheSizeMiB(AppPrefs.MAX_CACHE_MIB + 1))
    }

    @Test
    fun `a cache within range passes through`() {
        assertEquals(768, clampCacheSizeMiB(768))
    }

    @Test
    fun `a cache exactly on the bounds passes through`() {
        assertEquals(AppPrefs.MIN_CACHE_MIB, clampCacheSizeMiB(AppPrefs.MIN_CACHE_MIB))
        assertEquals(AppPrefs.MAX_CACHE_MIB, clampCacheSizeMiB(AppPrefs.MAX_CACHE_MIB))
    }

    @Test
    fun `a negative cache clamps to the minimum rather than wrapping`() {
        assertEquals(AppPrefs.MIN_CACHE_MIB, clampCacheSizeMiB(-512))
    }

    @Test
    fun `int extremes clamp instead of overflowing`() {
        assertEquals(AppPrefs.MIN_CACHE_MIB, clampCacheSizeMiB(Int.MIN_VALUE))
        assertEquals(AppPrefs.MAX_CACHE_MIB, clampCacheSizeMiB(Int.MAX_VALUE))
    }

    @Test
    fun `withCacheSize clamps through the data class`() {
        assertEquals(
            AppPrefs().copy(cacheSizeMiB = AppPrefs.MAX_CACHE_MIB),
            AppPrefs().withCacheSize(Int.MAX_VALUE),
        )
    }

    @Test
    fun `withNightMode sets the mode and preserves everything else`() {
        NightMode.entries.forEach { mode ->
            assertEquals(AppPrefs().copy(nightMode = mode), AppPrefs().withNightMode(mode))
        }
    }

    @Test
    fun `withDynamicColour flips only its own field`() {
        assertEquals(AppPrefs().copy(dynamicColour = false), AppPrefs().withDynamicColour(false))
        assertEquals(
            AppPrefs().copy(dynamicColour = false).withDynamicColour(true),
            AppPrefs(),
        )
    }

    @Test
    fun `withTrueBlack flips only its own field`() {
        assertEquals(AppPrefs().copy(trueBlack = true), AppPrefs().withTrueBlack(true))
    }

    @Test
    fun `withShowHiddenFolders flips only its own field`() {
        assertEquals(
            AppPrefs().copy(showHiddenFolders = true),
            AppPrefs().withShowHiddenFolders(true),
        )
    }

    @Test
    fun `library reducers flip only their own fields`() {
        assertEquals(
            AppPrefs().copy(openGenericArchives = true),
            AppPrefs().withOpenGenericArchives(true),
        )
        assertEquals(
            AppPrefs().copy(openImageFolders = false),
            AppPrefs().withOpenImageFolders(false),
        )
    }

    @Test
    fun `withReadingFlow covers every enum entry`() {
        ReadingFlow.entries.forEach { flow ->
            assertEquals(ReaderPrefs().copy(readingFlow = flow), ReaderPrefs().withReadingFlow(flow))
        }
    }

    @Test
    fun `withFitMode covers every enum entry`() {
        FitMode.entries.forEach { mode ->
            assertEquals(ReaderPrefs().copy(fitMode = mode), ReaderPrefs().withFitMode(mode))
        }
    }
}
