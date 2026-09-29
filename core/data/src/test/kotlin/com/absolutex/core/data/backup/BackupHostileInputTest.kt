package com.absolutex.core.data.backup

import com.absolutex.core.data.ReadingProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupHostileInputTest : BackupFixture() {
    private suspend fun reject(bytes: ByteArray) {
        val before = repository.export("test")
        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repository.restore(bytes.inputStream()) }
        }
        assertArrayEquals(before, repository.export("test"))
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
            """{"schemaVersion":1,"appVersion":"test","progress":[{"bookId":"a:1","pageIndex":1,"pageCount":1,"updatedAt":0}]}""",
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
            """{"schemaVersion":1,"appVersion":"test","future":{"nested":[1,true]},"preferences":{"future":1}}""".byteInputStream()))
    }

    @Test fun `oversized deeply nested and invalid UTF8 input fails before writes`() = runTest {
        reject(ByteArray(MAX_BACKUP_BYTES + 1) { ' '.code.toByte() })
        reject(("""{"schemaVersion":1,"appVersion":"test","future":""" + "[".repeat(30) + "0" + "]".repeat(30) + "}").toByteArray())
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
        val data = BackupData("test", listOf(ReadingProgress(id, 0, 1, 1)), favourites = setOf(id))
        repository.restore(BackupWriter.write(data).inputStream())
        assertFalse(path.exists())
        assertTrue(settings.currentAppPrefs().locations.isEmpty())
        assertEquals(id, db.progressDao().get(id)?.bookId)
        reject("""{"schemaVersion":1,"appVersion":"test","favourites":["../../no-size"]}""".toByteArray())
    }
}
