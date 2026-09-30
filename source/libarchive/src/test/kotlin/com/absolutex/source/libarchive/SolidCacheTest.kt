package com.absolutex.source.libarchive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The decode-once cache for solid archives, end to end over a fake archive: what a page turn gets
 * from it, and what it leaves on disk in every way a pass can end.
 */
class SolidCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rig by lazy { SolidRig(tmp.newFolder("cache")) }

    private fun start(streamer: EntryStreamer, book: SolidBook = bookOf(5), launch: SolidLaunch? = null) =
        startSolidCache(rig.config, book, launch ?: rig.inlineLaunch(streamer))

    private fun filesOf(): List<String> = File(rig.root, "solid-archives").walkTopDown().filter { it.isFile }
        .map { it.name }.toList()

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            Thread.sleep(2)
        }
    }

    @Test fun pages_are_served_from_the_cache_after_one_pass_and_never_decoded_again() {
        val streamer = FakeStreamer(pageBytes(5))
        val session = start(streamer)!!
        repeat(3) {
            for (ordinal in 0 until 5) {
                assertArrayEquals(ByteArray(16) { ordinal.toByte() }, session.read(ordinal).bytes())
            }
        }
        assertEquals("one decode for the whole book, however many page turns", 1, streamer.calls.get())
        session.close()
    }

    @Test fun a_page_not_yet_decoded_waits_for_the_pass_and_then_gets_its_bytes() {
        val gate = CountDownLatch(1)
        val streamer = FakeStreamer(pageBytes(4)) { ordinal -> if (ordinal == 2) gate.await() }
        val session = start(streamer, bookOf(4), rig.threadedLaunch(streamer))!!
        assertArrayEquals(ByteArray(16) { 0 }, session.read(0).bytes())

        val answer = AtomicReference<ByteArray>()
        val reader = Thread { answer.set(session.read(3).bytes()) }.apply { start() }
        waitUntil("the reader to wait") { reader.state == Thread.State.WAITING }
        assertNull("nothing to return yet", answer.get())

        gate.countDown()
        reader.join(10_000)
        assertArrayEquals(ByteArray(16) { 3 }, answer.get())
        rig.joinPass()
        session.close()
    }

    @Test fun a_cancelled_wait_ends_at_once_and_leaves_the_pass_running() {
        val gate = CountDownLatch(1)
        val streamer = FakeStreamer(pageBytes(3)) { ordinal -> if (ordinal == 1) gate.await() }
        val session = start(streamer, bookOf(3), rig.threadedLaunch(streamer))!!
        val outcome = AtomicReference<Throwable>()
        val reader = Thread {
            try { session.read(2) } catch (e: CancellationException) { outcome.set(e) }
        }.apply { start() }
        waitUntil("the reader to wait") { reader.state == Thread.State.WAITING }

        reader.interrupt()
        reader.join(10_000)
        assertTrue(outcome.get() is CancellationException)

        gate.countDown()
        assertArrayEquals("the pass carried on for everyone else", ByteArray(16) { 2 }, session.read(2).bytes())
        session.close()
    }

    @Test fun closing_mid_pass_stops_it_wakes_readers_deletes_the_partial_cache_and_frees_the_archive() {
        val gate = CountDownLatch(1)
        val streamer = FakeStreamer(pageBytes(6)) { ordinal -> if (ordinal == 2) gate.await() }
        val session = start(streamer, bookOf(6), rig.threadedLaunch(streamer))!!
        val failure = AtomicReference<Throwable>()
        val reader = Thread {
            try { session.read(5) } catch (e: IOException) { failure.set(e) }
        }.apply { start() }
        waitUntil("the reader to wait") { reader.state == Thread.State.WAITING }

        session.close()
        reader.join(10_000)
        assertTrue("the waiting read fails instead of hanging", failure.get() is IOException)

        gate.countDown()
        rig.joinPass()
        assertEquals("the pass stopped right after the entry in flight", 3, streamer.delivered.get())
        assertFalse("a partial cache is never kept", rig.store.dir(rig.key).exists())
        assertTrue("the archive can be claimed again", rig.store.claim(rig.key))
    }

    @Test fun a_finished_cache_is_reused_with_no_decoding_and_no_thread() {
        start(FakeStreamer(pageBytes(5)))!!.close()
        assertTrue(rig.store.isComplete(rig.key, 5))

        val never = FakeStreamer(pageBytes(5)) { throw AssertionError("must not decode") }
        val session = start(never)!!
        assertEquals(0, never.calls.get())
        assertEquals("only the first, inline pass ever spawned a thread", 1, rig.spawned.get())
        assertArrayEquals(ByteArray(16) { 4 }, session.read(4).bytes())
        session.close()
    }

    @Test fun a_solid_pass_delivers_comicinfo_when_it_arrives_and_a_reopen_reads_it_from_disk() {
        val xml = "<ComicInfo><Title>Solid</Title><PageCount>3</PageCount></ComicInfo>".toByteArray()
        val entries = pageBytes(3) + listOf(xml)
        val gate = CountDownLatch(1)
        val streamer = FakeStreamer(entries) { ordinal -> if (ordinal == 3) gate.await() }
        val session = start(streamer, bookOf(3, withInfo = true), rig.threadedLaunch(streamer))!!
        assertNull("ComicInfo is written last, so the open does not wait for it", session.comicInfo)
        assertArrayEquals(ByteArray(16) { 0 }, session.read(0).bytes())

        gate.countDown()
        rig.joinPass()
        assertEquals("Solid", session.comicInfo?.title)
        session.close()

        val again = start(FakeStreamer(entries) { throw AssertionError() }, bookOf(3, withInfo = true))!!
        assertEquals("Solid", again.comicInfo?.title)
        again.close()
    }

    @Test fun a_non_solid_archive_never_touches_the_cache() {
        val streamer = FakeStreamer(pageBytes(5))
        val launch = rig.inlineLaunch(streamer, probe = SolidProbe(solid = false, totalBytes = 900L shl 20))
        assertNull(start(streamer, launch = launch))
        assertEquals(0, streamer.calls.get())
        assertEquals(0, rig.spawned.get())
        assertFalse("not even the cache directory exists", File(rig.root, "solid-archives").exists())
        assertTrue("the claim was given back", rig.store.claim(rig.key))
    }

    @Test fun a_small_solid_book_is_read_directly() {
        val streamer = FakeStreamer(pageBytes(5))
        val small = SolidProbe(solid = true, totalBytes = SolidCachePolicy.MIN_BOOK_BYTES - 1)
        assertNull(start(streamer, launch = rig.inlineLaunch(streamer, probe = small)))
        assertEquals(0, streamer.calls.get())
    }

    @Test fun a_book_over_the_cap_falls_back_to_reading_directly() {
        val streamer = FakeStreamer(pageBytes(5))
        val huge = SolidProbe(solid = true, totalBytes = SolidCachePolicy.MAX_CAP_BYTES + 1)
        assertNull(start(streamer, launch = rig.inlineLaunch(streamer, probe = huge)))
        val tight = rig.inlineLaunch(streamer, free = SolidCachePolicy.MIN_BOOK_BYTES)
        assertNull("a quarter of a nearly full disk is under the minimum", start(streamer, launch = tight))
        assertEquals(0, streamer.calls.get())
        assertFalse(rig.store.dir(rig.key).exists())
    }

    @Test fun bytes_past_the_budget_abandon_the_cache_and_reads_fall_back() {
        val page = ByteArray(8 shl 20)
        val streamer = FakeStreamer(List(6) { page })
        val probe = SolidProbe(solid = true, totalBytes = SolidCachePolicy.MIN_BOOK_BYTES)
        val session = start(streamer, bookOf(6), rig.inlineLaunch(streamer, probe = probe))!!
        assertEquals("the pass was cut off when the fifth page crossed the ceiling", 5, streamer.delivered.get())
        assertSame(SolidRead.Fallback, session.read(0))
        assertSame(SolidRead.Fallback, session.read(5))
        assertFalse("the files were removed", rig.store.dir(rig.key).exists())
        session.close()
    }

    @Test fun a_failed_write_such_as_a_full_disk_falls_back_instead_of_failing() {
        var writes = 0
        val writer = PageWriter { file, data ->
            if (++writes > 2) throw IOException("No space left on device")
            PageWriter.Default.write(file, data)
        }
        val streamer = FakeStreamer(pageBytes(5))
        val session = start(streamer, launch = rig.inlineLaunch(streamer, writer = writer))!!
        assertSame("pages the pass had written are not trusted after it gave up", SolidRead.Fallback, session.read(0))
        assertSame(SolidRead.Fallback, session.read(4))
        assertFalse(rig.store.dir(rig.key).exists())
        session.close()
        assertFalse("nothing complete was published", rig.store.isComplete(rig.key, 5))
    }

    @Test fun an_archive_that_breaks_mid_stream_keeps_its_pages_and_fails_the_rest_without_a_marker() {
        val streamer = FakeStreamer(pageBytes(5).take(3), end = PassEnd.FAILED)
        val session = start(streamer)!!
        assertArrayEquals(ByteArray(16) { 2 }, session.read(2).bytes())
        assertSame("the existing verdict for an unreadable page", SolidRead.Torn, session.read(3))
        assertSame(SolidRead.Torn, session.read(4))
        assertFalse("an incomplete pass must not look complete", rig.store.isComplete(rig.key, 5))
        session.close()
        assertFalse("and is deleted, not kept for reuse", rig.store.dir(rig.key).exists())
    }

    @Test fun a_pass_that_fails_before_delivering_anything_falls_back_instead_of_tearing_every_page() {
        val streamer = FakeStreamer(emptyList(), end = PassEnd.FAILED)
        val session = start(streamer)!!
        assertSame("nothing was tried, so the direct read gets its turn", SolidRead.Fallback, session.read(0))
        assertSame(SolidRead.Fallback, session.read(4))
        assertFalse(rig.store.dir(rig.key).exists())
        session.close()
    }

    @Test fun a_torn_entry_costs_that_page_only() {
        val entries = pageBytes(4).toMutableList().also { it[1] = null }
        val session = start(FakeStreamer(entries), bookOf(4))!!
        assertSame(SolidRead.Torn, session.read(1))
        assertArrayEquals(ByteArray(16) { 2 }, session.read(2).bytes())
        assertFalse("a cache with a hole in it is not kept for reuse", rig.store.isComplete(rig.key, 4))
        session.close()
    }

    @Test fun a_stream_that_throws_abandons_the_cache_and_the_thread_ends() {
        val streamer = EntryStreamer { _, _, _ -> throw IllegalStateException("native failure") }
        val session = start(streamer)!!
        assertSame(SolidRead.Fallback, session.read(0))
        session.close()
        assertFalse(rig.store.dir(rig.key).exists())
    }

    @Test fun an_unfinished_directory_from_a_crash_is_discarded_never_trusted() {
        val dir = rig.store.dir(rig.key).also { it.mkdirs() }
        File(dir, "00000.bin").writeBytes(ByteArray(16) { 99 })
        assertFalse(rig.store.isComplete(rig.key, 5))

        val session = start(FakeStreamer(pageBytes(5)))!!
        val page = session.read(0).bytes()
        assertArrayEquals("the page comes from this pass, not the stale file", ByteArray(16) { 0 }, page)
        session.close()
    }

    @Test fun a_complete_marker_for_another_entry_count_is_stale() {
        start(FakeStreamer(pageBytes(5)))!!.close()
        assertFalse(rig.store.isComplete(rig.key, 6))
    }

    @Test fun a_second_open_of_the_same_archive_reads_directly_instead_of_racing_the_first() {
        val gate = CountDownLatch(1)
        val streamer = FakeStreamer(pageBytes(3)) { gate.await() }
        val first = start(streamer, bookOf(3), rig.threadedLaunch(streamer))!!
        assertNull(start(FakeStreamer(pageBytes(3)), bookOf(3)))
        gate.countDown()
        rig.joinPass()
        first.close()
    }

    @Test fun concurrent_readers_all_get_the_right_bytes_while_the_pass_is_still_running() {
        val count = 40
        val streamer = FakeStreamer(pageBytes(count)) { Thread.sleep(2) }
        val session = start(streamer, bookOf(count), rig.threadedLaunch(streamer))!!
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 200).map { n ->
                val ordinal = (n * 7) % count
                pool.submit<Boolean> { session.read(ordinal).bytes().all { it == ordinal.toByte() } }
            }
            assertTrue(results.all { it.get(30, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
        rig.joinPass()
        assertEquals(1, streamer.calls.get())
        session.close()
    }

    @Test fun files_are_named_by_ordinal_never_by_entry_name() {
        start(FakeStreamer(pageBytes(3)), bookOf(3))!!.close()
        assertEquals(listOf("00000.bin", "00001.bin", "00002.bin", "complete"), filesOf().sorted())
    }

    @Test fun a_purged_page_file_falls_back_for_that_page() {
        val session = start(FakeStreamer(pageBytes(3)), bookOf(3))!!
        rig.store.dir(rig.key).listFiles { f -> f.name == "00001.bin" }!!.single().delete()
        assertSame(SolidRead.Fallback, session.read(1))
        assertNotNull(session.read(0))
        session.close()
    }

    @Test fun a_key_that_is_not_a_hash_is_refused_so_it_cannot_climb_out_of_the_cache() {
        assertThrows(IllegalArgumentException::class.java) { rig.store.dir("../../databases") }
        assertThrows(IllegalArgumentException::class.java) { rig.store.dir("a/b") }
    }
}
