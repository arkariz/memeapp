package com.arkarizdev.grudge.core.watcher

import android.content.Context

/**
 * T-202: durable "already celebrated" pointers, same reasoning as
 * OnboardingPrefs — this is two counters with no query/relational need,
 * not Room material. Prevents the same beaten session or streak milestone
 * from generating a share card twice across app opens.
 */
object CardPrefs {
    private const val PREFS_NAME = "grudge_cards"
    private const val KEY_LAST_SHOWN_SESSION_ID = "last_shown_session_id"
    private const val KEY_LAST_CELEBRATED_STREAK = "last_celebrated_streak"

    fun lastShownSessionId(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_SHOWN_SESSION_ID, 0L)

    fun setLastShownSessionId(context: Context, id: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_SHOWN_SESSION_ID, id).apply()
    }

    fun lastCelebratedStreak(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_LAST_CELEBRATED_STREAK, 0)

    fun setLastCelebratedStreak(context: Context, streak: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_LAST_CELEBRATED_STREAK, streak).apply()
    }
}
