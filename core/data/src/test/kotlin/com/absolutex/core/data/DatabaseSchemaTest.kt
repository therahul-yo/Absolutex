package com.absolutex.core.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the exported schemas rather than the migration's runtime behaviour.
 *
 * Adding a table is the migration Room generates and validates itself at compile time, so the
 * real risk here is not the SQL — it is someone bumping the version without exporting a schema,
 * or dropping reading_progress while adding the library. Both are caught here, on the JVM.
 *
 * A runtime v1 -> v2 test still wants MigrationTestHelper on a device; noted as a gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatabaseSchemaTest {

    private fun schema(version: Int): JSONObject {
        val file = File("schemas/com.absolutex.core.data.AbsolutexDatabase/$version.json")
        assertTrue("schema $version was not exported: $file", file.isFile)
        return JSONObject(file.readText()).getJSONObject("database")
    }

    private fun tables(version: Int): List<String> {
        val entities = schema(version).getJSONArray("entities")
        return (0 until entities.length())
            .map { entities.getJSONObject(it).getString("tableName") }
            .sorted()
    }

    @Test fun `version 1 held only reading progress`() {
        assertEquals(listOf("reading_progress"), tables(1))
    }

    @Test fun `version 2 adds the library and keeps reading progress`() {
        // Losing reading_progress here would mean the user's position is dropped on upgrade —
        // the one thing in this database that rescanning cannot regenerate.
        assertEquals(listOf("library_book", "reading_progress"), tables(2))
    }

    @Test fun `the library table is indexed for the queries the library actually runs`() {
        val entities = schema(2).getJSONArray("entities")
        val library = (0 until entities.length())
            .map { entities.getJSONObject(it) }
            .single { it.getString("tableName") == "library_book" }
        val indices = library.optJSONArray("indices")
        val indexed = (0 until (indices?.length() ?: 0))
            .flatMap { i ->
                val cols = indices!!.getJSONObject(i).getJSONArray("columnNames")
                (0 until cols.length()).map { cols.getString(it) }
            }
        assertTrue("series should be indexed: shelves group by it", "series" in indexed)
        assertTrue("contentKey should be indexed: dedup looks up by it", "contentKey" in indexed)
    }
}
