package com.absolutex.feature.settings

import com.absolutex.core.data.settings.AppPrefs
import com.absolutex.core.data.settings.InMemorySettings
import com.absolutex.core.data.settings.NightMode
import com.absolutex.core.data.settings.ReaderPrefs
import com.absolutex.core.data.settings.RenderingPrefs
import com.absolutex.core.data.settings.SettingsWriter
import com.absolutex.core.gpu.ColourParams
import com.absolutex.model.FitMode
import com.absolutex.model.FitModeMemory
import com.absolutex.model.PageLayout
import com.absolutex.model.ReadingFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Records what the VM asked to store, so tests assert the transform rather than the store.
private class FakeSettingsWriter(
    var app: AppPrefs = AppPrefs(),
    var reader: ReaderPrefs = ReaderPrefs(),
    var rendering: RenderingPrefs = RenderingPrefs(),
) : SettingsWriter {
    val appWrites = mutableListOf<AppPrefs>()
    val readerWrites = mutableListOf<ReaderPrefs>()
    val renderingWrites = mutableListOf<RenderingPrefs>()

    override suspend fun updateApp(transform: (AppPrefs) -> AppPrefs) {
        app = transform(app)
        appWrites += app
    }

    override suspend fun updateReader(transform: (ReaderPrefs) -> ReaderPrefs) {
        reader = transform(reader)
        readerWrites += reader
    }

    override suspend fun updateRendering(transform: (RenderingPrefs) -> RenderingPrefs) {
        rendering = transform(rendering)
        renderingWrites += rendering
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(fake: FakeSettingsWriter): SettingsViewModel {
        // InMemorySettings is the real read side; only the write side is faked.
        val backing = InMemorySettings()
        return SettingsViewModel(backing, backing, backing, fake)
    }

    @Test
    fun `the exposed flows start on the contract defaults`() = runTest {
        val backing = InMemorySettings()
        val vm = SettingsViewModel(backing, backing, backing, FakeSettingsWriter())
        assertEquals(AppPrefs(), vm.appPrefs.value)
        assertEquals(ReaderPrefs(), vm.readerPrefs.value)
        assertEquals(RenderingPrefs(), vm.renderingPrefs.value)
    }

    @Test
    fun `setNightMode writes the mode through`() = runTest {
        val fake = FakeSettingsWriter()
        viewModel(fake).setNightMode(NightMode.ON)
        advanceUntilIdle()
        assertEquals(AppPrefs().copy(nightMode = NightMode.ON), fake.app)
    }

    @Test
    fun `theme toggles write through independently`() = runTest {
        val fake = FakeSettingsWriter()
        val vm = viewModel(fake)
        vm.setDynamicColour(false)
        vm.setTrueBlack(true)
        advanceUntilIdle()
        assertEquals(AppPrefs().copy(dynamicColour = false, trueBlack = true), fake.app)
    }

    @Test
    fun `setShowHiddenFolders writes through`() = runTest {
        val fake = FakeSettingsWriter()
        viewModel(fake).setShowHiddenFolders(true)
        advanceUntilIdle()
        assertEquals(AppPrefs().copy(showHiddenFolders = true), fake.app)
    }

    @Test
    fun `library toggles write through independently`() = runTest {
        val fake = FakeSettingsWriter()
        val vm = viewModel(fake)
        vm.setOpenGenericArchives(true)
        vm.setOpenImageFolders(false)
        advanceUntilIdle()
        assertEquals(
            AppPrefs().copy(openGenericArchives = true, openImageFolders = false),
            fake.app,
        )
    }

    @Test
    fun `reader settings write to the reader prefs, not the app prefs`() = runTest {
        val fake = FakeSettingsWriter()
        val vm = viewModel(fake)
        vm.setReadingFlow(ReadingFlow.RTL)
        vm.setFitMode(FitMode.FIT_WIDTH)
        advanceUntilIdle()
        assertEquals(
            ReaderPrefs().copy(
                readingFlow = ReadingFlow.RTL,
                fitMode = FitMode.FIT_WIDTH,
                fitMemory = FitModeMemory.everywhere(FitMode.FIT_WIDTH),
            ),
            fake.reader,
        )
        assertEquals(AppPrefs(), fake.app)
    }

    @Test
    fun `a reader field edit writes only that field`() = runTest {
        val fake = FakeSettingsWriter()
        val vm = viewModel(fake)
        vm.setReadingFlow(ReadingFlow.RTL)
        vm.updateReader { it.copy(pageLayout = PageLayout.DOUBLE_WITH_COVER) }
        vm.updateReader { it.copy(keepScreenOn = false) }
        advanceUntilIdle()
        assertEquals(
            ReaderPrefs(readingFlow = ReadingFlow.RTL, pageLayout = PageLayout.DOUBLE_WITH_COVER, keepScreenOn = false),
            fake.reader,
        )
        assertEquals(AppPrefs(), fake.app)
    }

    @Test
    fun `an oversize cache self-heals to the maximum on write`() = runTest {
        val fake = FakeSettingsWriter()
        viewModel(fake).setCacheSize(Int.MAX_VALUE)
        advanceUntilIdle()
        assertEquals(AppPrefs().copy(cacheSizeMiB = AppPrefs.MAX_CACHE_MIB), fake.app)
    }

    @Test
    fun `an undersize cache self-heals to the minimum on write`() = runTest {
        val fake = FakeSettingsWriter()
        viewModel(fake).setCacheSize(1)
        advanceUntilIdle()
        assertEquals(AppPrefs().copy(cacheSizeMiB = AppPrefs.MIN_CACHE_MIB), fake.app)
    }

    @Test
    fun `an edit preserves fields set by earlier edits`() = runTest {
        val fake = FakeSettingsWriter(app = AppPrefs().copy(trueBlack = true))
        viewModel(fake).setDynamicColour(false)
        advanceUntilIdle()
        assertEquals(AppPrefs().copy(trueBlack = true, dynamicColour = false), fake.app)
    }

    @Test
    fun `every write is recorded exactly once`() = runTest {
        val fake = FakeSettingsWriter()
        val vm = viewModel(fake)
        vm.setNightMode(NightMode.OFF)
        vm.setFitMode(FitMode.FIT_HEIGHT)
        advanceUntilIdle()
        assertEquals(1, fake.appWrites.size)
        assertEquals(1, fake.readerWrites.size)
    }

    @Test
    fun `setCacheSize writes exactly once per committed value`() = runTest {
        // CacheSizeRow now calls this only from onValueChangeFinished, so one commit must
        // still mean one write here, not one per drag frame the row used to forward.
        val fake = FakeSettingsWriter()
        val vm = viewModel(fake)
        vm.setCacheSize(256)
        advanceUntilIdle()
        assertEquals(1, fake.appWrites.size)
        assertEquals(256, fake.app.cacheSizeMiB)

        vm.setCacheSize(512)
        advanceUntilIdle()
        assertEquals(2, fake.appWrites.size)
        assertEquals(512, fake.app.cacheSizeMiB)
    }

    @Test
    fun `updateRendering forwards the transform to the writer`() = runTest {
        val fake = FakeSettingsWriter()
        viewModel(fake).updateRendering { it.withColour(ColourParams(temperature = 0.4f)) }
        advanceUntilIdle()
        assertEquals(0.4f, fake.rendering.colour.temperature)
        assertEquals(1, fake.renderingWrites.size)
    }
}
