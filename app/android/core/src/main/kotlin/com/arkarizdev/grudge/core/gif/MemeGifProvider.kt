package com.arkarizdev.grudge.core.gif

/**
 * T-208: the swappable abstraction the tech plan's §7c anticipated
 * ("the provider sits behind one interface so this is a low-regret
 * choice") — originally scoped around Tenor, which Google fully shut down
 * on 2026-06-30 before this project ever registered a client. Swapped for
 * GIPHY ([GiphyMemeGifProvider]) with zero changes needed anywhere else
 * that depends on this interface.
 */
interface MemeGifProvider {
    /** Tries each query in order until one returns a usable result. Never throws — returns null on exhaustion, a non-2xx response, or any exception. */
    suspend fun search(queries: List<String>): MemeGifResult?

    /** Downloads the raw GIF bytes for [MemeGifResult.downloadUrl]. Never throws — null on any failure. */
    suspend fun downloadBytes(url: String): ByteArray?

    /** Best-effort "this GIF was actually shown to a user" pingback. Never throws. */
    suspend fun registerUsed(onSentUrl: String)
}

data class MemeGifResult(val id: String, val downloadUrl: String, val onSentUrl: String?)
