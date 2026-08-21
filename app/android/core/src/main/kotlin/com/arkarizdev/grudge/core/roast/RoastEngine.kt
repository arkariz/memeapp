package com.arkarizdev.grudge.core.roast

import android.content.Context
import org.json.JSONObject

/**
 * T-105: loads roast_pack.json (bundled asset — the real, comedy-reviewed
 * pack from assets/roast_pack_v1/), picks a template by tier + intent-text
 * availability, fills its slots, and hands back a ready-to-persist result.
 *
 * Precompute discipline (tech plan §5/§7): this class does the picking and
 * filling — it never runs on the roast-display hot path. WatcherService
 * calls precompute() at grant()/extend() time and writes the result into
 * roast_payload immediately; the roast overlay (T-106) will only ever read
 * that stored row.
 *
 * Slot semantics: `{granted_min}` is always exact (it's what was just
 * granted). `{actual_min}`/`{overage_min}` are computed against elapsed
 * time as of THIS precompute call, not at eventual display time — for the
 * common case (a session that expires naturally, no interruption), the
 * ~500ms poll granularity between expiry and the roast actually showing
 * makes this accurate enough that the distinction doesn't matter for a
 * comedy app's purposes.
 */
class RoastEngine(context: Context) {
    companion object {
        /** Escalation shape per the tone guide: tier 1 first roast, 2 after one extension, 3 after that. */
        internal fun tierFor(extensionsSoFar: Int): Int = when {
            extensionsSoFar <= 0 -> 1
            extensionsSoFar == 1 -> 2
            else -> 3
        }

        internal fun fill(text: String, slots: Map<String, String>): String {
            var result = text
            for ((key, value) in slots) {
                result = result.replace("{$key}", value)
            }
            return result
        }

        /**
         * T-208/T-209: parses roast_pack.json's `moods` map — search-query
         * seeds for the live GIPHY fetch, keyed by the same asset id every
         * template already carries. Skips `_comment` (a plain string entry,
         * not an object) and ignores the Tenor-specific `contentfilter`
         * field left in the pack's content — GIPHY's rating filter is a
         * hardcoded constant in GiphyMemeGifProvider instead, not sourced
         * from already-reviewed T-004 content.
         */
        internal fun parseMoods(json: String): Map<String, List<String>> {
            val moodsObj = JSONObject(json).optJSONObject("moods") ?: return emptyMap()
            val result = mutableMapOf<String, List<String>>()
            for (key in moodsObj.keys()) {
                val entry = moodsObj.optJSONObject(key) ?: continue
                val queries = entry.optJSONArray("queries") ?: continue
                result[key] = (0 until queries.length()).map { queries.getString(it) }
            }
            return result
        }

        private fun readAssetText(context: Context, path: String): String =
            context.assets.open(path).bufferedReader().use { it.readText() }
    }

    private val packJson: String by lazy { readAssetText(context, "roast_pack_v1/roast_pack.json") }
    private val templates: List<RoastTemplate> by lazy { loadTemplates() }
    private val moods: Map<String, List<String>> by lazy { parseMoods(packJson) }

    // Per-pkg last-shown template id, so the same joke doesn't repeat
    // back-to-back (tone guide: "no repeat within last N shown" — N=1 here,
    // the simplest version of that rule).
    private val lastTemplateId = mutableMapOf<String, String>()

    data class PrecomputeResult(
        val templateId: String,
        val tier: Int,
        val line1: String,
        val line2: String,
        val asset: String,
    )

    /**
     * @param extensionsSoFar the session's extension count AT THE MOMENT
     *   this roast is being precomputed for — 0 at initial grant (tier 1),
     *   1 after the first extension (tier 2), 2+ after that (tier 3).
     */
    fun precompute(
        pkg: String,
        intentText: String?,
        grantedMin: Int,
        elapsedMin: Int,
        extensionsSoFar: Int,
    ): PrecomputeResult? {
        val tier = tierFor(extensionsSoFar)
        val hasIntent = !intentText.isNullOrBlank()
        val candidates = templates.filter { it.tier == tier && (!it.requiresIntentText || hasIntent) }
        if (candidates.isEmpty()) return null

        val avoiding = lastTemplateId[pkg]
        val pool = candidates.filter { it.id != avoiding }.ifEmpty { candidates }
        val template = pool.random()
        lastTemplateId[pkg] = template.id

        val overageMin = (elapsedMin - grantedMin).coerceAtLeast(0)
        val slots = mapOf(
            "intent" to (intentText ?: ""),
            "actual_min" to elapsedMin.toString(),
            "granted_min" to grantedMin.toString(),
            "overage_min" to overageMin.toString(),
            "extension_count" to extensionsSoFar.toString(),
            "tier" to tier.toString(),
        )

        return PrecomputeResult(
            templateId = template.id,
            tier = tier,
            line1 = fill(template.line1, slots),
            line2 = fill(template.line2, slots),
            asset = template.asset,
        )
    }

    /** T-208/T-209: search-query seeds for [moodId], or empty if the mood has none / roast_pack.json lacks it. */
    fun queriesFor(moodId: String): List<String> = moods[moodId].orEmpty()

    private fun loadTemplates(): List<RoastTemplate> {
        val root = JSONObject(packJson)
        val array = root.getJSONArray("templates")
        val result = mutableListOf<RoastTemplate>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val requires = obj.getJSONArray("requires")
            val requiresIntentText = (0 until requires.length()).any { requires.getString(it) == "intent_text" }
            val degrade = obj.getJSONArray("degrade")
            result.add(
                RoastTemplate(
                    id = obj.getString("id"),
                    tier = obj.getInt("tier"),
                    requiresIntentText = requiresIntentText,
                    line1 = obj.getString("line1"),
                    line2 = obj.getString("line2"),
                    degradeLine1 = degrade.getString(0),
                    degradeLine2 = degrade.getString(1),
                    asset = obj.getString("asset"),
                )
            )
        }
        return result
    }
}
