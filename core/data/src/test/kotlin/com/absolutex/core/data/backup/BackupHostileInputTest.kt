package com.absolutex.core.data.backup

import com.absolutex.core.data.ReadingProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupHostileInputTest : BackupFixture() {
    private suspend fun reject(bytes: ByteArray) {
        val before = repository.export("test").bytes
        assertThrows(InvalidBackup::class.java) {
            kotlinx.coroutines.runBlocking { repository.restore(bytes.inputStream()) }
        }
        assertArrayEquals(before, repository.export("test").bytes)
    }

    @Test fun `truncated wrong types duplicate keys and trailing content change nothing`() = runTest {
        db.progressDao().upsert(ReadingProgress("kept:1", 0, 1, 1))
        listOf(
            """{"schemaVersion":1,"appVersion":"test","progress":[""",
            """{"schemaVersion":"1","appVersion":"test"}""",
            """{"schemaVersion":1,"appVersion":"test","future":1e999999999}""",
            """{"schemaVersion":1,"appVersion":"test","preferences":null}""",
            """{"schemaVersion":1,"appVersion":"test","favourites":[":1"]}""",
            """{"schemaVersion":1,"appVersion":"test","progress":{}}""",
            """{"schemaVersion":1,"appVersion":"test","preferences":{"true_black":"false"}}""",
            """{"schemaVersion":1,"schemaVersion":1,"appVersion":"test"}""",
            """{"schemaVersion":1,"appVersion":"test"}{}""",
            """{"schemaVersion":1,"appVersion":"test","progress":[
                {"bookId":"a:1","pageIndex":1,"pageCount":1,"updatedAt":0}]}""",
            """{"schemaVersion":1,"appVersion":"test","bookmarks":[{"bookId":"a:1","pageIndex":0.5,"createdAt":0}]}""",
        ).forEach { reject(it.toByteArray()) }
    }

    @Test fun `unknown future version is distinguishable and unknown fields are ignored`() = runTest {
        assertThrows(FutureBackupVersion::class.java) {
            kotlinx.coroutines.runBlocking {
                repository.restore("""{"schemaVersion":2,"appVersion":"future"}""".byteInputStream())
            }
        }
        assertEquals(RestoreResult.Complete(0), repository.restore(
            """{"schemaVersion":1,"appVersion":"test","future":{"nested":[1,true]},
                "preferences":{"future":1}}""".byteInputStream()))
    }

    @Test fun `oversized deeply nested and invalid UTF8 input fails before writes`() = runTest {
        reject(ByteArray(MAX_BACKUP_BYTES + 1) { ' '.code.toByte() })
        val nested = """{"schemaVersion":1,"appVersion":"test","future":""" +
            "[".repeat(30) + "0" + "]".repeat(30) + "}"
        reject(nested.toByteArray())
        reject(byteArrayOf(0xC3.toByte(), 0x28))
        reject("""{"schemaVersion":1,"appVersion":"${"a".repeat(MAX_TEXT + 1)}"}""".toByteArray())
    }

    @Test fun `book and history limits are hard caps`() = runTest {
        val ids = (0..MAX_BOOKS).joinToString(",") { "\"a$it:1\"" }
        reject("""{"schemaVersion":1,"appVersion":"test","favourites":[$ids]}""".toByteArray())
        val views = (0..MAX_ENTRIES).joinToString(",") { """{"bookId":"a:1","pageIndex":0,"atEpochMs":$it}""" }
        reject("""{"schemaVersion":1,"appVersion":"test","history":[$views]}""".toByteArray())
    }

    @Test fun `path-like identity is opaque and cannot write files or grant access`() = runTest {
        val path = tmp.root.resolve("escaped.cbz")
        val id = "../../${path.name}:1"
        val data = BackupData("test", listOf(ReadingProgress(id, 1, 2, 1)), favourites = setOf(id))
        repository.restore(BackupWriter.write(data).inputStream())
        assertFalse(path.exists())
        assertTrue(settings.currentAppPrefs().locations.isEmpty())
        assertEquals(id, db.progressDao().get(id)?.bookId)
        reject("""{"schemaVersion":1,"appVersion":"test","favourites":["../../no-size"]}""".toByteArray())
    }
    @Test fun `BOM and a file at both byte and value caps parse and one extra value fails`() = runTest {
        val values = List(MAX_JSON_VALUES - 4) { "0" }.joinToString(",")
        val json = """{"schemaVersion":1,"appVersion":"test","future":[$values]}"""
        val bytes = ("\uFEFF" + json).toByteArray()
        val capped = bytes + ByteArray(MAX_BACKUP_BYTES - bytes.size) { ' '.code.toByte() }
        assertEquals(RestoreResult.Complete(0), repository.restore(capped.inputStream()))
        reject(json.replace("]}", ",0]}").toByteArray())
    }

    @Test fun `duplicate progress identities are specifically invalid`() = runTest {
        val row = """{"bookId":"a:1","pageIndex":0,"pageCount":1,"updatedAt":1}"""
        reject("""{"schemaVersion":1,"appVersion":"test","progress":[$row,$row]}""".toByteArray())
    }

    @Test fun `unknown per book enums become global defaults`() = runTest {
        val data = BackupData("test", bookPrefs = listOf(com.absolutex.core.data.BookPrefs("a:1", "FUTURE", "FUTURE")))
        repository.restore(BackupWriter.write(data).inputStream())
        assertEquals(com.absolutex.core.data.BookPrefs("a:1"), db.bookPrefsDao().get("a:1"))
    }

}
