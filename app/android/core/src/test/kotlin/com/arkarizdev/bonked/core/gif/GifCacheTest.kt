package com.arkarizdev.bonked.core.gif

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class GifCacheTest {
    private lateinit var baseDir: File
    private lateinit var cache: GifCache

    @Before
    fun setUp() {
        baseDir = File.createTempFile("gif_cache_test", "").apply {
            delete()
            mkdirs()
        }
        cache = GifCache(baseDir)
    }

    @Test
    fun `fileFor names the file by session id and mood id`() {
        val file = cache.fileFor(sessionId = 42L, moodId = "side_eye")
        assertEquals("42_side_eye.gif", file.name)
    }

    @Test
    fun `write on success renames the part file into place`() = runTest {
        val dest = cache.fileFor(1L, "stonks")
        val ok = cache.write(dest) { byteArrayOf(1, 2, 3) }
        assertTrue(ok)
        assertTrue(dest.exists())
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), dest.readBytes().toList())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun `write cleans up and returns false when fetchBytes returns null`() = runTest {
        val dest = cache.fileFor(1L, "stonks")
        val ok = cache.write(dest) { null }
        assertFalse(ok)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun `write cleans up and returns false when fetchBytes throws`() = runTest {
        val dest = cache.fileFor(1L, "stonks")
        val ok = cache.write(dest) { throw RuntimeException("network down") }
        assertFalse(ok)
        assertFalse(dest.exists())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun `deleteForSession removes only files for that session`() {
        File(baseDir, "1_side_eye.gif").writeText("a")
        File(baseDir, "1_stonks.gif").writeText("b")
        File(baseDir, "2_side_eye.gif").writeText("c")

        cache.deleteForSession(1L)

        assertFalse(File(baseDir, "1_side_eye.gif").exists())
        assertFalse(File(baseDir, "1_stonks.gif").exists())
        assertTrue(File(baseDir, "2_side_eye.gif").exists())
    }

    @Test
    fun `sweepOrphans deletes files for inactive sessions and stray part files, keeps active ones`() {
        File(baseDir, "1_side_eye.gif").writeText("a")
        File(baseDir, "2_stonks.gif").writeText("b")
        File(baseDir, "3_waiting.gif.part").writeText("c")

        cache.sweepOrphans(activeSessionIds = setOf(2L))

        assertFalse(File(baseDir, "1_side_eye.gif").exists())
        assertTrue(File(baseDir, "2_stonks.gif").exists())
        assertFalse(File(baseDir, "3_waiting.gif.part").exists())
    }

    @Test
    fun `sweepOrphans deletes a malformed filename with no session id prefix`() {
        File(baseDir, "not_a_session_id.gif").writeText("a")

        cache.sweepOrphans(activeSessionIds = setOf(1L))

        assertFalse(File(baseDir, "not_a_session_id.gif").exists())
    }
}
