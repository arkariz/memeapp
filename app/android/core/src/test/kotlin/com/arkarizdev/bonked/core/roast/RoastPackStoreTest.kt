package com.arkarizdev.bonked.core.roast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val VALID_PACK_JSON = """{"version":1,"templates":[{"id":"t1","tier":1}]}"""

/** Builds a zip with one `roast_pack.json` entry plus any extra entries, e.g. `"mascot.webp" to byteArrayOf(1,2,3)`. */
private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class RoastPackStoreTest {
    private lateinit var packsDir: File
    private lateinit var store: RoastPackStore

    @Before
    fun setUp() {
        packsDir = File.createTempFile("roast_pack_store_test", "").apply {
            delete()
            mkdirs()
        }
        store = RoastPackStore(packsDir)
    }

    @Test
    fun `currentVersion and currentPackDir are null before anything is applied`() {
        assertNull(store.currentVersion())
        assertNull(store.currentPackDir())
        assertNull(store.currentPackJsonOrNull())
    }

    @Test
    fun `applyDownloadedPack succeeds, flips the pointer, and exposes the json`() {
        val zip = zipOf("roast_pack.json" to VALID_PACK_JSON.toByteArray())
        val ok = store.applyDownloadedPack(version = 3, zipBytes = zip, expectedSha256 = sha256Hex(zip))

        assertTrue(ok)
        assertEquals(3, store.currentVersion())
        assertEquals(VALID_PACK_JSON, store.currentPackJsonOrNull())
        assertTrue(File(packsDir, "3").isDirectory)
    }

    @Test
    fun `applyDownloadedPack extracts extra asset files alongside the json`() {
        val assetBytes = byteArrayOf(1, 2, 3, 4)
        val zip = zipOf("roast_pack.json" to VALID_PACK_JSON.toByteArray(), "mascot.webp" to assetBytes)
        val ok = store.applyDownloadedPack(version = 1, zipBytes = zip, expectedSha256 = sha256Hex(zip))

        assertTrue(ok)
        val assetFile = File(store.currentPackDir(), "mascot.webp")
        assertTrue(assetFile.isFile)
        assertEquals(assetBytes.toList(), assetFile.readBytes().toList())
    }

    @Test
    fun `applyDownloadedPack rejects a hash mismatch and leaves no pointer`() {
        val zip = zipOf("roast_pack.json" to VALID_PACK_JSON.toByteArray())
        val ok = store.applyDownloadedPack(version = 1, zipBytes = zip, expectedSha256 = "0".repeat(64))

        assertFalse(ok)
        assertNull(store.currentVersion())
        assertFalse(File(packsDir, "1").exists())
    }

    @Test
    fun `applyDownloadedPack rejects a zip with no roast_pack json`() {
        val zip = zipOf("readme.txt" to "hi".toByteArray())
        val ok = store.applyDownloadedPack(version = 1, zipBytes = zip, expectedSha256 = sha256Hex(zip))

        assertFalse(ok)
        assertNull(store.currentVersion())
    }

    @Test
    fun `applyDownloadedPack rejects a roast_pack json with an empty templates array`() {
        val badJson = """{"version":1,"templates":[]}"""
        val zip = zipOf("roast_pack.json" to badJson.toByteArray())
        val ok = store.applyDownloadedPack(version = 1, zipBytes = zip, expectedSha256 = sha256Hex(zip))

        assertFalse(ok)
        assertNull(store.currentVersion())
    }

    @Test
    fun `applyDownloadedPack rejects malformed json that fails to parse`() {
        val zip = zipOf("roast_pack.json" to "not json at all".toByteArray())
        val ok = store.applyDownloadedPack(version = 1, zipBytes = zip, expectedSha256 = sha256Hex(zip))

        assertFalse(ok)
        assertNull(store.currentVersion())
    }

    @Test
    fun `applyDownloadedPack rejects a zip-slip entry and touches nothing outside the pack dir`() {
        val zip = zipOf(
            "roast_pack.json" to VALID_PACK_JSON.toByteArray(),
            "../../evil.txt" to "pwned".toByteArray(),
        )
        val ok = store.applyDownloadedPack(version = 1, zipBytes = zip, expectedSha256 = sha256Hex(zip))

        assertFalse(ok)
        assertNull(store.currentVersion())
        assertFalse(File(packsDir.parentFile, "evil.txt").exists())
    }

    @Test
    fun `applying a newer version updates the pointer and old version stays on disk until GC`() {
        val zip1 = zipOf("roast_pack.json" to VALID_PACK_JSON.toByteArray())
        store.applyDownloadedPack(version = 1, zipBytes = zip1, expectedSha256 = sha256Hex(zip1))

        val json2 = """{"version":2,"templates":[{"id":"t2","tier":1}]}"""
        val zip2 = zipOf("roast_pack.json" to json2.toByteArray())
        store.applyDownloadedPack(version = 2, zipBytes = zip2, expectedSha256 = sha256Hex(zip2))

        assertEquals(2, store.currentVersion())
        assertEquals(json2, store.currentPackJsonOrNull())
        assertTrue(File(packsDir, "1").exists()) // not deleted by apply — only gcOldPacks() deletes
    }

    @Test
    fun `gcOldPacks deletes every version dir except the current pointer`() {
        val zip1 = zipOf("roast_pack.json" to VALID_PACK_JSON.toByteArray())
        store.applyDownloadedPack(version = 1, zipBytes = zip1, expectedSha256 = sha256Hex(zip1))
        val json2 = """{"version":2,"templates":[{"id":"t2","tier":1}]}"""
        val zip2 = zipOf("roast_pack.json" to json2.toByteArray())
        store.applyDownloadedPack(version = 2, zipBytes = zip2, expectedSha256 = sha256Hex(zip2))

        store.gcOldPacks()

        assertFalse(File(packsDir, "1").exists())
        assertTrue(File(packsDir, "2").exists())
        assertEquals(2, store.currentVersion())
    }

    @Test
    fun `gcOldPacks leaves stray tmp dirs alone`() {
        File(packsDir, "3.tmp").mkdirs()

        store.gcOldPacks()

        assertTrue(File(packsDir, "3.tmp").exists())
    }
}
