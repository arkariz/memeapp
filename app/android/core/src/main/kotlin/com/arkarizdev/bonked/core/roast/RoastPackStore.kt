package com.arkarizdev.bonked.core.roast

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * T-207 / tech plan §7: pure file logic for the remote roast pack cache —
 * deliberately Context/OkHttp-free so it's fully JVM-testable, same split
 * as the gif package's GifCache/GifFetchGateway. Caller passes
 * File(context.filesDir, "packs"); [RoastPackFetchGateway] is the network
 * side that calls into this.
 *
 * Layout:
 * ```
 * packs/<version>/roast_pack.json + asset files   ← one dir per version
 * packs/current                                    ← plain-text version pointer
 * ```
 *
 * "Atomic swap" (tech plan §7's resolution-order diagram): a downloaded
 * pack is fully extracted into a fresh `<version>` dir and hash/schema
 * verified BEFORE the pointer is ever written, and the pointer write
 * itself is write-to-temp-then-rename (same pattern GifCache uses for
 * `.part` files) — so a crash or kill mid-apply can never leave `current`
 * pointing at a half-extracted or unverified pack. RoastEngine and
 * RoastOverlayController only ever read whatever `current` resolves to
 * and trust it; a bad download simply never flips the pointer, and the
 * app falls back to the bundled pack exactly as if none had ever synced.
 */
class RoastPackStore(private val packsDir: File) {
    companion object {
        private const val CURRENT_POINTER = "current"
        private const val PACK_JSON_NAME = "roast_pack.json"
    }

    /** The version currently active, or null if no pack has ever been applied (bundled-only). */
    fun currentVersion(): Int? {
        val pointer = File(packsDir, CURRENT_POINTER)
        if (!pointer.isFile) return null
        return pointer.readText().trim().toIntOrNull()
    }

    /** The active pack's directory, or null — caller falls back to the bundled asset pack. */
    fun currentPackDir(): File? {
        val version = currentVersion() ?: return null
        val dir = File(packsDir, version.toString())
        return dir.takeIf { it.isDirectory && File(it, PACK_JSON_NAME).isFile }
    }

    /** roast_pack.json's text from the active pack, or null (no pack applied, or somehow missing the file). */
    fun currentPackJsonOrNull(): String? =
        currentPackDir()?.let { File(it, PACK_JSON_NAME) }?.takeIf { it.isFile }?.readText()

    /**
     * Verifies [zipBytes] against [expectedSha256], extracts it into a
     * fresh `packs/<version>` dir, confirms the extracted roast_pack.json
     * at least parses with a non-empty `templates` array, and only then
     * atomically flips the `current` pointer to it. Returns false (no
     * pointer change, extraction cleaned up) on any hash mismatch,
     * malformed zip, zip-slip attempt, or unparseable pack — this is the
     * one gate standing between "downloaded" and "trusted."
     */
    fun applyDownloadedPack(version: Int, zipBytes: ByteArray, expectedSha256: String): Boolean {
        if (sha256Hex(zipBytes) != expectedSha256.lowercase()) return false

        val versionDir = File(packsDir, version.toString())
        val tempDir = File(packsDir, "$version.tmp")
        return try {
            tempDir.deleteRecursively() // stale partial extract from a previous crashed attempt, if any
            tempDir.mkdirs()
            extractZip(zipBytes, tempDir)
            val packJsonFile = File(tempDir, PACK_JSON_NAME)
            if (!packJsonFile.isFile || !looksLikeValidPack(packJsonFile.readText())) return false

            versionDir.deleteRecursively()
            if (!tempDir.renameTo(versionDir)) return false
            writePointer(version)
            true
        } catch (_: Exception) {
            false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Pack GC rule (tech plan §7): old pack dirs are deleted only here,
     * called once at service start — never mid-swap or mid-download,
     * since a live roast_payload row resolves its asset path against
     * whatever pack version was active when it was precomputed, and that
     * must keep working until the session finalizes. A `*.tmp` dir from
     * an in-progress or crashed apply is deliberately left alone here too
     * (its name doesn't parse as a bare version number) — applyDownloadedPack
     * owns cleaning those up itself.
     */
    fun gcOldPacks() {
        val current = currentVersion()
        val dirs = packsDir.listFiles { f -> f.isDirectory } ?: return
        for (dir in dirs) {
            val version = dir.name.toIntOrNull() ?: continue
            if (version != current) dir.deleteRecursively()
        }
    }

    private fun writePointer(version: Int) {
        packsDir.mkdirs()
        val pointer = File(packsDir, CURRENT_POINTER)
        val tempPointer = File(packsDir, "$CURRENT_POINTER.tmp")
        tempPointer.writeText(version.toString())
        tempPointer.renameTo(pointer)
    }

    private fun looksLikeValidPack(json: String): Boolean = try {
        JSONObject(json).getJSONArray("templates").length() > 0
    } catch (_: Exception) {
        false
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Zip-slip guard: every entry's resolved path must stay inside
     * [destDir] — a malicious or corrupt zip naming an entry like
     * "../../evil" must never be allowed to write outside the pack
     * directory, since [zipBytes] comes from the network.
     */
    private fun extractZip(zipBytes: ByteArray, destDir: File) {
        val destPrefix = destDir.canonicalPath + File.separator
        ZipInputStream(zipBytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (!outFile.canonicalPath.startsWith(destPrefix)) {
                    throw SecurityException("zip entry escapes pack dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
