package com.arkarizdev.grudge.core.watcher

import android.content.Context
import android.content.pm.PackageManager

/**
 * Shared with IntentOverlayController (T-104) so the app-picker list (T-109)
 * and the intent-capture overlay resolve the same app label the same way.
 * Known limitation carried over from T-104: PackageManager's label lookup
 * falls back to the raw package string for some apps (e.g. YouTube) — an
 * OS/API quirk, not something either caller can fix locally.
 */
internal fun resolveAppLabel(context: Context, pkg: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (_: PackageManager.NameNotFoundException) {
    pkg
}
