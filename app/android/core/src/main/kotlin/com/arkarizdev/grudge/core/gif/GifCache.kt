package com.arkarizdev.grudge.core.gif

import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * T-208/T-209: pure file logic for the GIPHY GIF cache — deliberately
 * Context/OkHttp-free so it's fully JVM-testable. Caller passes
 * `File(context.filesDir, "gif_cache")`.
 *
 * Cache lifetime matches tech plan §7c ("cache locally for the session's
 * lifetime; don't treat the cache as a permanent redistribution store"):
 * WatcherService calls [deleteForSession] the moment a session finalizes,
 * since the roast overlay never replays history — [RoastPayloadDao.latestFor]
 * only ever serves the currently-active session. [sweepOrphans] is a
 * defensive safety net on service start, mirroring the "Pack GC rule"
 * already documented in tech plan §7 (delete on start, never on swap) —
 * catches a delete-on-finalize that was missed (crash, force-kill, etc.).
 */
class GifCache(private val baseDir: File) {
    fun fileFor(sessionId: Long, moodId: String): File = File(baseDir, "${sessionId}_$moodId.gif")

    /**
     * [fetchBytes] returning null (or throwing) means fetch/download
     * failure — cleans up and returns false. On success, writes to a
     * `.part` sibling first and renames into place, so the roast overlay
     * can never read a half-written file mid-download.
     */
    suspend fun write(dest: File, fetchBytes: suspend () -> ByteArray?): Boolean {
        val partFile = File(dest.path + ".part")
        return try {
            val bytes = fetchBytes() ?: return false
            baseDir.mkdirs()
            partFile.writeBytes(bytes)
            partFile.renameTo(dest)
        } catch (t: CancellationException) {
            throw t // never swallow — a timeout above must still propagate as a timeout
        } catch (_: Exception) {
            false
        } finally {
            if (partFile.exists()) partFile.delete()
        }
    }

    /** Deletes every cached file belonging to [sessionId] — called once a session finalizes. */
    fun deleteForSession(sessionId: Long) {
        val prefix = "${sessionId}_"
        baseDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix)) file.delete()
        }
    }

    /** Deletes any cached file whose session id isn't in [activeSessionIds], plus any stray `.part` file (always a crash artifact). */
    fun sweepOrphans(activeSessionIds: Set<Long>) {
        val files = baseDir.listFiles() ?: return
        for (file in files) {
            if (file.name.endsWith(".part")) {
                file.delete()
                continue
            }
            val sessionId = file.name.substringBefore('_', missingDelimiterValue = "").toLongOrNull()
            if (sessionId == null || sessionId !in activeSessionIds) file.delete()
        }
    }
}
