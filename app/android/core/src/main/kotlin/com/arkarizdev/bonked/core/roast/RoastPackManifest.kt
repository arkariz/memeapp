package com.arkarizdev.bonked.core.roast

import org.json.JSONObject

/**
 * T-207 / tech plan §7: the entire remote surface is this one static file
 * — `{ "latest": 4, "url": "…/pack_v4.zip", "sha256": "…" }` on any static
 * host (GitHub Releases / Cloudflare R2). No backend, no Firebase Remote
 * Config — that would add SDK weight to do what a static file already does.
 */
data class RoastPackManifest(val latest: Int, val url: String, val sha256: String) {
    companion object {
        /** Null on anything malformed — a bad manifest must never crash the sync job, just skip this attempt. */
        internal fun parse(json: String): RoastPackManifest? = try {
            val obj = JSONObject(json)
            RoastPackManifest(
                latest = obj.getInt("latest"),
                url = obj.getString("url"),
                sha256 = obj.getString("sha256"),
            )
        } catch (_: Exception) {
            null
        }
    }
}
