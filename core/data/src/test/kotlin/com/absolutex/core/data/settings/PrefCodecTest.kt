package com.absolutex.core.data.settings

import com.absolutex.model.FitMode
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
        assertEquals(NightMode.SYSTEM, app.nightMode)
        assertTrue(app.dynamicColour)
        assertFalse(app.trueBlack)
        assertEquals(AppPrefs.DEFAULT_CACHE_MIB, app.cacheSizeMiB)
        assertFalse(app.showHiddenFolders)
        assertFalse(app.openGenericArchives)
        assertTrue(app.openImageFolders)
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
        assertEquals(
            setOf(
                PrefCodec.KEY_NIGHT_MODE,
                PrefCodec.KEY_DYNAMIC_COLOUR,
                PrefCodec.KEY_TRUE_BLACK,
                PrefCodec.KEY_CACHE_MIB,
                PrefCodec.KEY_SHOW_HIDDEN,
                PrefCodec.KEY_GENERIC_ARCHIVES,
                PrefCodec.KEY_IMAGE_FOLDERS,
                PrefCodec.KEY_READING_FLOW,
                PrefCodec.KEY_FIT_MODE,
                PrefCodec.KEY_VOLUME_KEYS,
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
        assertEquals("the broken field defaults", NightMode.SYSTEM, app.nightMode)
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
        assertEquals("reading_flow", PrefCodec.KEY_READING_FLOW)
        assertEquals("fit_mode", PrefCodec.KEY_FIT_MODE)
        assertEquals("volume_keys_turn_pages", PrefCodec.KEY_VOLUME_KEYS)
    }

    @Test
    fun `volume keys are off until the reader asks for them, and the choice round-trips`() {
        assertEquals(false, PrefCodec.decodeReader(MapPrefBag()).volumeKeysTurnPages)
        val bag = MapPrefBag()
        PrefCodec.encodeReader(ReaderPrefs(volumeKeysTurnPages = true), bag)
        assertEquals(true, PrefCodec.decodeReader(bag).volumeKeysTurnPages)
    }
}
