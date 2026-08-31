package com.arkarizdev.bonked.core.watcher

import android.content.Context

/**
 * A single durable flag: has the T-109 onboarding flow ever been completed?
 * Deliberately SharedPreferences, not Room — this is one boolean with no
 * query/relational need, unlike HeartbeatStore which graduated to Room in
 * T-103 because it needed cross-process reads. See main.dart: this gates
 * whether app launch shows onboarding or the post-onboarding scaffold.
 */
object OnboardingPrefs {
    private const val PREFS_NAME = "bonked_onboarding"
    private const val KEY_COMPLETE = "complete"

    fun isComplete(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_COMPLETE, false)

    fun setComplete(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMPLETE, true).apply()
    }
}
