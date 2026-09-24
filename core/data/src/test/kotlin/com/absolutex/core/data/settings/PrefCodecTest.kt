package com.absolutex.core.data.settings

import com.absolutex.core.data.NextBookOrder
import com.absolutex.core.data.NextBookScope
import com.absolutex.core.gpu.ColourParams
import com.absolutex.core.gpu.Upscaler
import com.absolutex.model.FitContext
import com.absolutex.model.FitMode
import com.absolutex.model.FitModeMemory
import com.absolutex.model.PageOrientation
import com.absolutex.model.ScreenOrientation
import com.absolutex.model.PageLayout
import com.absolutex.model.ReadingFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefCodecTest {

    // ---------------------------------------------------------------- defaults / empty store

    @Test
    fun `an empty store decodes to the documented defaults`() {
        val app = PrefCodec.decodeApp(MapPrefBag())
        // Black out of the box: dark, the curated Ink palette rather than wallpaper colours, and
        // true black. A reader who wants otherwise changes three switches; nobody should have to
        // change three switches to get the look the app is designed around.
        assertEquals(NightMode.ON, app.nightMode)
        assertFalse(app.dynamicColour)
        assertTrue(app.trueBlack)
        assertEquals(AppPrefs.DEFAULT_CACHE_MIB, app.cacheSizeMiB)
        assertFalse(app.showHiddenFolders)
        assertFalse(app.openGenericArchives)
        assertTrue(app.openImageFolders)
        assertFalse(app.useOriginalFilename)
    }

    @Test
    fun `an empty store decodes reader prefs to the documented defaults`() {
        val reader = PrefCodec.decodeReader(MapPrefBag())
        assertEquals(ReadingFlow.LTR, reader.readingFlow)
        assertEquals(FitMode.FIT_SCREEN, reader.fitMode)
    }

    @Test
    fun `decoding an empty store equals the model defaults exactly`() {
        // Migration from empty is just this: a store with nothing in it must produce the same
        // record as constructing the model with no arguments.
        assertEquals(AppPrefs(), PrefCodec.decodeApp(MapPrefBag()))
        assertEquals(ReaderPrefs(), PrefCodec.decodeReader(MapPrefBag()))
    }

    // ---------------------------------------------------------------- persistence round-trip

    @Test
    fun `the original filename switch survives a round-trip, both ways`() {
        val on = AppPrefs(useOriginalFilename = true)
        val bag = MapPrefBag()
        PrefCodec.encodeApp(on, bag)
        assertTrue(PrefCodec.decodeApp(bag).useOriginalFilename)
        // Default off writes the key explicitly so the stored intent is visible in the file.
        PrefCodec.encodeApp(AppPrefs(), bag)
        assertFalse(PrefCodec.decodeApp(bag).useOriginalFilename)
    }

    @Test
    fun `a wrongly typed original filename value falls back to its default`() {
        val bag = MapPrefBag(mapOf(LibraryPrefKeys.USE_ORIGINAL_FILENAME to "yes"))
        assertFalse(PrefCodec.decodeApp(bag).useOriginalFilename)
    }

    @Test
    fun `app prefs survive an encode and decode unchanged`() {
        val original = AppPrefs(
            nightMode = NightMode.ON,
            dynamicColour = false,
            trueBlack = true,
            cacheSizeMiB = 1024,
            showHiddenFolders = true,
            openGenericArchives = true,
            openImageFolders = false,
        )
        val bag = MapPrefBag()
        PrefCodec.encodeApp(original, bag)
        assertEquals(original, PrefCodec.decodeApp(bag))
    }

    @Test
    fun `every reader pref combination survives a round-trip`() {
        for (flow in ReadingFlow.entries) {
            for (fit in FitMode.entries) {
                val original = ReaderPrefs(readingFlow = flow, fitMode = fit)
                val bag = MapPrefBag()
                PrefCodec.encodeReader(original, bag)
                assertEquals("failed on $flow/$fit", original, PrefCodec.decodeReader(bag))
            }
        }
    }

    @Test
    fun `library locations survive a round-trip, and none writes no key`() {
        val locations = setOf("content://tree/a", "content://tree/b")
        val bag = MapPrefBag()
        PrefCodec.encodeApp(AppPrefs(locations = locations), bag)
        assertEquals(locations, PrefCodec.decodeApp(bag).locations)

        val empty = MapPrefBag()
        PrefCodec.encodeApp(AppPrefs(), empty)
        assertTrue(PrefCodec.KEY_LOCATIONS !in empty.snapshot().keys)
    }

    @Test
    fun `removing the last location clears the stored key, not just the decoded value`() {
        // The bug this guards: encodeApp used to skip the write for an empty set rather than
        // clearing it, so a bag that already held a location from an earlier encode kept it
        // forever — every later "remove the last one" silently did nothing.
        val bag = MapPrefBag()
        PrefCodec.encodeApp(AppPrefs(locations = setOf("content://tree/a")), bag)
        assertEquals(setOf("content://tree/a"), bag.snapshot()[PrefCodec.KEY_LOCATIONS])

        PrefCodec.encodeApp(AppPrefs(locations = emptySet()), bag)
        assertTrue("the key must be gone, not merely empty", PrefCodec.KEY_LOCATIONS !in bag.snapshot().keys)
        assertEquals(emptySet<String>(), PrefCodec.decodeApp(bag).locations)
    }

    @Test
    fun `every page layout survives a round-trip`() {
        for (layout in PageLayout.entries) {
            val original = ReaderPrefs(pageLayout = layout)
            val bag = MapPrefBag()
            PrefCodec.encodeReader(original, bag)
            assertEquals(original, PrefCodec.decodeReader(bag))
        }
    }

    @Test
    fun `a shape keeps the fit chosen for it, and the others keep their defaults`() {
        val landscapeScreen = FitContext(ScreenOrientation.LANDSCAPE, PageOrientation.PORTRAIT)
        val spreadOnPhone = FitContext(ScreenOrientation.PORTRAIT, PageOrientation.LANDSCAPE)
        val original = ReaderPrefs(fitMemory = FitModeMemory().with(landscapeScreen, FitMode.FIT_HEIGHT))
        val bag = MapPrefBag()
        PrefCodec.encodeReader(original, bag)
        val decoded = PrefCodec.decodeReader(bag)
        assertEquals(original, decoded)
        assertEquals(FitMode.FIT_HEIGHT, decoded.fitFor(landscapeScreen))
        // Untouched, so still the shape's own default rather than the one just chosen elsewhere.
        assertEquals(FitMode.FIT_WIDTH, decoded.fitFor(spreadOnPhone))
    }

    @Test
    fun `nothing chosen writes no key at all`() {
        // Writing all four up front would freeze every shape at today's default the first time any
        // one of them is edited.
        val bag = MapPrefBag()
        PrefCodec.encodeReader(ReaderPrefs(), bag)
        assertTrue(PrefCodec.KEY_FIT_BY_CONTEXT !in bag.snapshot().keys)
    }

    @Test
    fun `an unreadable stored fit is dropped, and its shape keeps its default`() {
        val bag = MapPrefBag(mapOf(PrefCodec.KEY_FIT_BY_CONTEXT to setOf("LANDSCAPE:PORTRAIT=SIDEWAYS", "nonsense")))
        val decoded = PrefCodec.decodeReader(bag)
        assertEquals(FitModeMemory(), decoded.fitMemory)
        assertEquals(
            FitMode.FIT_SCREEN,
            decoded.fitFor(FitContext(ScreenOrientation.LANDSCAPE, PageOrientation.PORTRAIT)),
        )
    }

    @Test
    fun `an animation setting out of range is clamped, not discarded`() {
        val bag = MapPrefBag(
            mapOf(PrefCodec.KEY_PAGE_TURN_MS to 5_000, PrefCodec.KEY_SCROLL_STEP to 5),
        )
        val decoded = PrefCodec.decodeReader(bag)
        assertEquals(MAX_PAGE_TURN_MS, decoded.pageTurnMs)
        assertEquals(MIN_SCROLL_STEP_PERCENT, decoded.scrollStepPercent)
    }

    @Test
    fun `every night mode survives a round-trip`() {
        for (mode in NightMode.entries) {
            val bag = MapPrefBag()
            PrefCodec.encodeApp(AppPrefs(nightMode = mode), bag)
            assertEquals(mode, PrefCodec.decodeApp(bag).nightMode)
        }
    }

    @Test
    fun `enums are stored by name so reordering the enum cannot re-point them`() {
        // An ordinal would silently change meaning the moment a constant is inserted mid-enum.
        val bag = MapPrefBag()
        PrefCodec.encodeReader(ReaderPrefs(readingFlow = ReadingFlow.RTL), bag)
        assertEquals("RTL", bag.snapshot()[PrefCodec.KEY_READING_FLOW])
    }

    @Test
    fun `encoding writes one key per setting and nothing else`() {
        val bag = MapPrefBag()
        PrefCodec.encodeApp(AppPrefs(), bag)
        PrefCodec.encodeReader(ReaderPrefs(), bag)
        PrefCodec.encodeRendering(RenderingPrefs(), bag)
        assertEquals(
            setOf(
                PrefCodec.KEY_NIGHT_MODE,
                PrefCodec.KEY_DYNAMIC_COLOUR,
                PrefCodec.KEY_TRUE_BLACK,
                PrefCodec.KEY_CACHE_MIB,
                PrefCodec.KEY_SHOW_HIDDEN,
                PrefCodec.KEY_GENERIC_ARCHIVES,
                PrefCodec.KEY_IMAGE_FOLDERS,
                LibraryPrefKeys.USE_ORIGINAL_FILENAME,
                PrefCodec.KEY_READING_FLOW,
                PrefCodec.KEY_FIT_MODE,
                PrefCodec.KEY_VOLUME_KEYS,
                PrefCodec.KEY_KEEP_SCREEN_ON,
                PrefCodec.KEY_ROTATION_LOCK,
                PrefCodec.KEY_USE_CUTOUT,
                PrefCodec.KEY_PAGE_LAYOUT,
                PrefCodec.KEY_THUMBNAIL_STRIP,
                PrefCodec.KEY_TRANSITION,
                PrefCodec.KEY_PAGE_TURN_MS,
                PrefCodec.KEY_SCROLL_STEP,
                PrefCodec.KEY_AUTO_ADVANCE,
                PrefCodec.KEY_NEXT_BOOK_SCOPE,
                PrefCodec.KEY_NEXT_BOOK_ORDER,
                PrefCodec.KEY_COLOUR_BRIGHTNESS,
                PrefCodec.KEY_COLOUR_CONTRAST,
                PrefCodec.KEY_COLOUR_SATURATION,
                PrefCodec.KEY_COLOUR_TEMPERATURE,
                PrefCodec.KEY_COLOUR_AGGRESSION,
                PrefCodec.KEY_COLOUR_VIBRANCE,
                PrefCodec.KEY_COLOUR_GAMMA,
                PrefCodec.KEY_COLOUR_GAMMA_R,
                PrefCodec.KEY_COLOUR_GAMMA_G,
                PrefCodec.KEY_COLOUR_GAMMA_B,
                PrefCodec.KEY_UPSCALER,
                PrefCodec.KEY_AUTO_BACKGROUND,
            ),
            bag.snapshot().keys,
        )
    }

    // ---------------------------------------------------------------- corrupt store

    @Test
    fun `a wrongly typed value falls back to its default`() {
        // What a store written by an older build looks like: right keys, wrong types. DataStore
        // would throw ClassCastException on each of these.
        val bag = MapPrefBag(
            mapOf(
                PrefCodec.KEY_NIGHT_MODE to 2,
                PrefCodec.KEY_DYNAMIC_COLOUR to "yes",
                PrefCodec.KEY_TRUE_BLACK to 1,
                PrefCodec.KEY_CACHE_MIB to "1024",
                PrefCodec.KEY_SHOW_HIDDEN to listOf("nonsense"),
            )
        )
        assertEquals(AppPrefs(), PrefCodec.decodeApp(bag))
    }

    @Test
    fun `an unrecognised enum name falls back to its default`() {
        val bag = MapPrefBag(
            mapOf(
                PrefCodec.KEY_READING_FLOW to "SIDEWAYS",
                PrefCodec.KEY_FIT_MODE to "",
            )
        )
        assertEquals(ReaderPrefs(), PrefCodec.decodeReader(bag))
    }

    @Test
    fun `enum names are matched exactly, not case-insensitively`() {
        // Anything we wrote ourselves is upper-case; a lower-case value did not come from us and
        // guessing at it would be inventing intent.
        val bag = MapPrefBag(mapOf(PrefCodec.KEY_READING_FLOW to "rtl"))
        assertEquals(ReadingFlow.LTR, PrefCodec.decodeReader(bag).readingFlow)
    }

    @Test
    fun `one broken field does not cost the fields beside it`() {
        val bag = MapPrefBag(
            mapOf(
                PrefCodec.KEY_NIGHT_MODE to "NOT_A_MODE",
                PrefCodec.KEY_TRUE_BLACK to true,
                PrefCodec.KEY_CACHE_MIB to 256,
            )
        )
        val app = PrefCodec.decodeApp(bag)
        assertEquals("the broken field defaults", AppPrefs().nightMode, app.nightMode)
        assertTrue("its neighbours survive", app.trueBlack)
        assertEquals(256, app.cacheSizeMiB)
    }

    @Test
    fun `a heterogeneous collection is rejected whole rather than half-kept`() {
        val bag = MapPrefBag(mapOf("extensions" to listOf("cbz", 7, "cbr")))
        assertNull(bag.stringSet("extensions"))
    }

    @Test
    fun `a homogeneous collection reads back as a string set`() {
        val bag = MapPrefBag(mapOf("extensions" to listOf("cbz", "cbr")))
        assertEquals(setOf("cbz", "cbr"), bag.stringSet("extensions"))
    }

    // ---------------------------------------------------------------- range handling

    @Test
    fun `a cache size out of range is clamped, not discarded`() {
        val tooSmall = MapPrefBag(mapOf(PrefCodec.KEY_CACHE_MIB to 8))
        assertEquals(AppPrefs.MIN_CACHE_MIB, PrefCodec.decodeApp(tooSmall).cacheSizeMiB)

        val tooLarge = MapPrefBag(mapOf(PrefCodec.KEY_CACHE_MIB to 1_048_576))
        assertEquals(AppPrefs.MAX_CACHE_MIB, PrefCodec.decodeApp(tooLarge).cacheSizeMiB)

        val negative = MapPrefBag(mapOf(PrefCodec.KEY_CACHE_MIB to -1))
        assertEquals(AppPrefs.MIN_CACHE_MIB, PrefCodec.decodeApp(negative).cacheSizeMiB)
    }

    @Test
    fun `a cache size inside the range is kept verbatim`() {
        val bag = MapPrefBag(mapOf(PrefCodec.KEY_CACHE_MIB to 768))
        assertEquals(768, PrefCodec.decodeApp(bag).cacheSizeMiB)
    }

    @Test
    fun `encoding clamps an out-of-range value so the store never holds one`() {
        val bag = MapPrefBag()
        PrefCodec.encodeApp(AppPrefs(cacheSizeMiB = Int.MAX_VALUE), bag)
        assertEquals(AppPrefs.MAX_CACHE_MIB, bag.snapshot()[PrefCodec.KEY_CACHE_MIB])
    }

    @Test
    fun `the range bounds are themselves legal values`() {
        for (size in listOf(AppPrefs.MIN_CACHE_MIB, AppPrefs.MAX_CACHE_MIB)) {
            val bag = MapPrefBag(mapOf(PrefCodec.KEY_CACHE_MIB to size))
            assertEquals(size, PrefCodec.decodeApp(bag).cacheSizeMiB)
        }
    }

    // ---------------------------------------------------------------- key stability

    @Test
    fun `stored key names are frozen`() {
        // These names are on disk in every install. This test exists so that renaming one is a
        // deliberate act with a visible cost, not a quiet reset of that setting for everybody.
        assertEquals("night_mode", PrefCodec.KEY_NIGHT_MODE)
        assertEquals("dynamic_colour", PrefCodec.KEY_DYNAMIC_COLOUR)
        assertEquals("true_black", PrefCodec.KEY_TRUE_BLACK)
        assertEquals("cache_size_mib", PrefCodec.KEY_CACHE_MIB)
        assertEquals("show_hidden_folders", PrefCodec.KEY_SHOW_HIDDEN)
        assertEquals("open_generic_archives", PrefCodec.KEY_GENERIC_ARCHIVES)
        assertEquals("open_image_folders", PrefCodec.KEY_IMAGE_FOLDERS)
        // Lane keys live in their own codec file, but they are on disk just the same.
        assertEquals("use_original_filename", LibraryPrefKeys.USE_ORIGINAL_FILENAME)
        assertEquals("reading_flow", PrefCodec.KEY_READING_FLOW)
        assertEquals("fit_mode", PrefCodec.KEY_FIT_MODE)
        assertEquals("volume_keys_turn_pages", PrefCodec.KEY_VOLUME_KEYS)
        assertEquals("keep_screen_on", PrefCodec.KEY_KEEP_SCREEN_ON)
        assertEquals("rotation_lock", PrefCodec.KEY_ROTATION_LOCK)
        assertEquals("use_cutout", PrefCodec.KEY_USE_CUTOUT)
        assertEquals("page_layout", PrefCodec.KEY_PAGE_LAYOUT)
        assertEquals("thumbnail_strip", PrefCodec.KEY_THUMBNAIL_STRIP)
        assertEquals("page_transition", PrefCodec.KEY_TRANSITION)
        assertEquals("page_turn_ms", PrefCodec.KEY_PAGE_TURN_MS)
        assertEquals("scroll_step_percent", PrefCodec.KEY_SCROLL_STEP)
        assertEquals("auto_advance", PrefCodec.KEY_AUTO_ADVANCE)
        assertEquals("next_book_scope", PrefCodec.KEY_NEXT_BOOK_SCOPE)
        assertEquals("next_book_order", PrefCodec.KEY_NEXT_BOOK_ORDER)
    }

    @Test
    fun `window behaviour defaults suit reading and every choice round-trips`() {
        val defaults = PrefCodec.decodeReader(MapPrefBag())
        assertEquals(true, defaults.keepScreenOn)
        assertEquals(RotationLock.SYSTEM, defaults.rotationLock)
        assertEquals(true, defaults.useCutout)
        RotationLock.entries.forEach { lock ->
            val bag = MapPrefBag()
            val prefs = ReaderPrefs(keepScreenOn = false, rotationLock = lock, useCutout = false)
            PrefCodec.encodeReader(prefs, bag)
            assertEquals(prefs, PrefCodec.decodeReader(bag))
        }
    }

    @Test
    fun `an unknown rotation lock name falls back to following the system`() {
        val bag = MapPrefBag(mapOf(PrefCodec.KEY_ROTATION_LOCK to "UPSIDE_DOWN"))
        assertEquals(RotationLock.SYSTEM, PrefCodec.decodeReader(bag).rotationLock)
    }

    @Test
    fun `volume keys are off until the reader asks for them, and the choice round-trips`() {
        assertEquals(false, PrefCodec.decodeReader(MapPrefBag()).volumeKeysTurnPages)
        val bag = MapPrefBag()
        PrefCodec.encodeReader(ReaderPrefs(volumeKeysTurnPages = true), bag)
        assertEquals(true, PrefCodec.decodeReader(bag).volumeKeysTurnPages)
    }

    // ---------------------------------------------------------------- auto-advance (§5.2)

    @Test
    fun `auto-advance is on by default, and the choice round-trips`() {
        assertEquals(true, PrefCodec.decodeReader(MapPrefBag()).autoAdvance)
        val bag = MapPrefBag()
        PrefCodec.encodeReader(ReaderPrefs(autoAdvance = false), bag)
        assertEquals(false, PrefCodec.decodeReader(bag).autoAdvance)
    }

    @Test
    fun `every next-book scope and order combination survives a round-trip`() {
        for (scope in NextBookScope.entries) {
            for (order in NextBookOrder.entries) {
                val original = ReaderPrefs(nextBookScope = scope, nextBookOrder = order)
                val bag = MapPrefBag()
                PrefCodec.encodeReader(original, bag)
                assertEquals("failed on $scope/$order", original, PrefCodec.decodeReader(bag))
            }
        }
    }

    @Test
    fun `an unrecognised next-book scope or order falls back to its default`() {
        val bag = MapPrefBag(
            mapOf(
                PrefCodec.KEY_NEXT_BOOK_SCOPE to "EVERYWHERE",
                PrefCodec.KEY_NEXT_BOOK_ORDER to "ALPHABETICAL",
            ),
        )
        val decoded = PrefCodec.decodeReader(bag)
        assertEquals(NextBookScope.WHOLE_LIBRARY, decoded.nextBookScope)
        assertEquals(NextBookOrder.PARSED_NUMBER, decoded.nextBookOrder)
    }

    // ---------------------------------------------------------------- rendering (§4 colour)

    @Test
    fun `an empty store decodes rendering prefs to neutral`() {
        assertEquals(RenderingPrefs(), PrefCodec.decodeRendering(MapPrefBag()))
        assertTrue(PrefCodec.decodeRendering(MapPrefBag()).colour.isNeutral)
    }

    @Test
    fun `every rendering field survives a round-trip`() {
        val full = RenderingPrefs(
            colour = ColourParams(
                brightness = 0.15f,
                contrast = 1.1f,
                saturation = 1.25f,
                temperature = -0.4f,
                wbAggression = 0.8f,
                vibrance = 0.6f,
                gamma = 1.1f,
                gammaR = 0.9f,
                gammaG = 1f,
                gammaB = 1.2f,
            ),
        )
        val bag = MapPrefBag()
        PrefCodec.encodeRendering(full, bag)
        assertEquals(full, PrefCodec.decodeRendering(bag))
    }

    @Test
    fun `each rendering field round-trips on its own`() {
        val base = ColourParams()
        val variants = listOf(
            base.copy(brightness = 0.5f),
            base.copy(contrast = 1.5f),
            base.copy(saturation = 0.5f),
            base.copy(temperature = 0.5f),
            base.copy(wbAggression = 0.5f),
            base.copy(vibrance = 0.5f),
            base.copy(gamma = 1.5f),
            base.copy(gammaR = 1.5f),
            base.copy(gammaG = 1.5f),
            base.copy(gammaB = 1.5f),
        )
        for (colour in variants) {
            val bag = MapPrefBag()
            PrefCodec.encodeRendering(RenderingPrefs(colour), bag)
            assertEquals("failed on $colour", RenderingPrefs(colour), PrefCodec.decodeRendering(bag))
        }
    }

    @Test
    fun `a wrongly typed rendering value falls back to its default`() {
        // What a store written by an older build looks like: right keys, wrong types. DataStore
        // would throw ClassCastException on each of these. Every ColourParams field is covered.
        val corrupt: Map<String, Any> = mapOf(
            PrefCodec.KEY_COLOUR_BRIGHTNESS to "bright",
            PrefCodec.KEY_COLOUR_CONTRAST to true,
            PrefCodec.KEY_COLOUR_SATURATION to listOf(1f),
            PrefCodec.KEY_COLOUR_TEMPERATURE to 1,
            PrefCodec.KEY_COLOUR_AGGRESSION to "strong",
            PrefCodec.KEY_COLOUR_VIBRANCE to "some",
            PrefCodec.KEY_COLOUR_GAMMA to listOf("1.0"),
            PrefCodec.KEY_COLOUR_GAMMA_R to false,
            PrefCodec.KEY_COLOUR_GAMMA_G to "high",
            PrefCodec.KEY_COLOUR_GAMMA_B to listOf(2f),
        )
        val bag = MapPrefBag(corrupt)
        assertEquals(RenderingPrefs(), PrefCodec.decodeRendering(bag))
    }

    @Test
    fun `one broken rendering field does not cost the fields beside it`() {
        val bag = MapPrefBag(
            mapOf(
                PrefCodec.KEY_COLOUR_BRIGHTNESS to "bright",
                PrefCodec.KEY_COLOUR_CONTRAST to 1.5f,
            )
        )
        val colour = PrefCodec.decodeRendering(bag).colour
        assertEquals(0f, colour.brightness)
        assertEquals(1.5f, colour.contrast)
    }

    @Test
    fun `an out-of-range rendering value is clamped, not discarded`() {
        val bag = MapPrefBag(
            mapOf(
                PrefCodec.KEY_COLOUR_BRIGHTNESS to 5f,
                PrefCodec.KEY_COLOUR_CONTRAST to -1f,
                PrefCodec.KEY_COLOUR_GAMMA to 9f,
                PrefCodec.KEY_COLOUR_SATURATION to 1.25f,
            )
        )
        val colour = PrefCodec.decodeRendering(bag).colour
        assertEquals(1f, colour.brightness)
        assertEquals(0f, colour.contrast)
        assertEquals(2.5f, colour.gamma)
        assertEquals(1.25f, colour.saturation)
    }

    @Test
    fun `rendering key names are frozen`() {
        assertEquals("colour_brightness", PrefCodec.KEY_COLOUR_BRIGHTNESS)
        assertEquals("colour_contrast", PrefCodec.KEY_COLOUR_CONTRAST)
        assertEquals("colour_saturation", PrefCodec.KEY_COLOUR_SATURATION)
        assertEquals("colour_temperature", PrefCodec.KEY_COLOUR_TEMPERATURE)
        assertEquals("colour_wb_aggression", PrefCodec.KEY_COLOUR_AGGRESSION)
        assertEquals("colour_vibrance", PrefCodec.KEY_COLOUR_VIBRANCE)
        assertEquals("colour_gamma", PrefCodec.KEY_COLOUR_GAMMA)
        assertEquals("colour_gamma_r", PrefCodec.KEY_COLOUR_GAMMA_R)
        assertEquals("colour_gamma_g", PrefCodec.KEY_COLOUR_GAMMA_G)
        assertEquals("colour_gamma_b", PrefCodec.KEY_COLOUR_GAMMA_B)
        assertEquals("upscaler", PrefCodec.KEY_UPSCALER)
        assertEquals("auto_background", PrefCodec.KEY_AUTO_BACKGROUND)
    }

    @Test
    fun `every upscaler survives a round-trip`() {
        Upscaler.entries.forEach { upscaler ->
            val bag = MapPrefBag()
            PrefCodec.encodeRendering(RenderingPrefs(upscaler = upscaler), bag)
            assertEquals(upscaler, PrefCodec.decodeRendering(bag).upscaler)
        }
    }

    @Test
    fun `an upscaler stored by name decodes, anything else falls back to platform`() {
        val named = MapPrefBag(mapOf(PrefCodec.KEY_UPSCALER to "MITCHELL"))
        assertEquals(Upscaler.MITCHELL, PrefCodec.decodeRendering(named).upscaler)

        val wrongType = MapPrefBag(mapOf(PrefCodec.KEY_UPSCALER to 1))
        assertEquals(Upscaler.PLATFORM, PrefCodec.decodeRendering(wrongType).upscaler)

        val unknown = MapPrefBag(mapOf(PrefCodec.KEY_UPSCALER to "BILINEAR"))
        assertEquals(Upscaler.PLATFORM, PrefCodec.decodeRendering(unknown).upscaler)
    }

    @Test
    fun `auto background defaults off and round-trips both ways`() {
        // Off: a comic reads against black. A page with white margins would otherwise tint the
        // whole screen white. The literal key is pinned below so a rename cannot flip it silently.
        assertFalse(PrefCodec.decodeRendering(MapPrefBag()).autoBackground)

        val on = MapPrefBag()
        PrefCodec.encodeRendering(RenderingPrefs(autoBackground = true), on)
        assertTrue(PrefCodec.decodeRendering(on).autoBackground)

        val off = MapPrefBag()
        PrefCodec.encodeRendering(RenderingPrefs(autoBackground = false), off)
        assertFalse(PrefCodec.decodeRendering(off).autoBackground)
    }

    @Test
    fun `a wrongly typed auto background value falls back to its default`() {
        val bag = MapPrefBag(mapOf(PrefCodec.KEY_AUTO_BACKGROUND to "yes"))
        assertFalse(PrefCodec.decodeRendering(bag).autoBackground)
    }

    @Test
    fun `auto background is stored under the literal key auto_background`() {
        val on = MapPrefBag()
        PrefCodec.encodeRendering(RenderingPrefs(autoBackground = true), on)
        assertTrue(PrefCodec.decodeRendering(MapPrefBag(mapOf("auto_background" to true))).autoBackground)
    }
}
