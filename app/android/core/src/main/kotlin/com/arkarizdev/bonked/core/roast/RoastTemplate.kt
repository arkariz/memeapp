package com.arkarizdev.bonked.core.roast

/** Mirrors one template entry in roast_pack.json (see assets/roast_pack_v1/roast_pack.json). */
data class RoastTemplate(
    val id: String,
    val tier: Int,
    val requiresIntentText: Boolean,
    val line1: String,
    val line2: String,
    val degradeLine1: String,
    val degradeLine2: String,
    val asset: String, // doubles as the mood id for GIPHY (tech plan §7c, T-208/T-209) — not resolved here
)
