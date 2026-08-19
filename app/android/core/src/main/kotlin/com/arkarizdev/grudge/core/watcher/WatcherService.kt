package com.arkarizdev.grudge.core.watcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Foreground service: polls UsageEvents, drives the SessionStateMachine,
 * writes a heartbeat every tick. Ported from spike/ (see spike/README.md
 * for the validated latency numbers) — same poll-loop shape, now wired to
 * real session state instead of a one-shot debug overlay.
 *
 * Overlays (T-104/T-106) and roast content (T-105) are not built yet;
 * state transitions are observable via `adb logcat -s GrudgeSessionSM
 * GrudgeWatcher`.
 */
class WatcherService : Service() {
    companion object {
        private const val TAG = "GrudgeWatcher"
        private const val NOTIF_CHANNEL = "watch"
        private const val NOTIF_ID = 1
        private const val DEFAULT_POLL_MS = 500L

        /**
         * TODO(T-103/T-109): replace with the real watched_app table +
         * onboarding app picker. Mirrors the spike's WATCHED set so the
         * already-validated latency numbers stay comparable.
         */
        val WATCHED = setOf(
            "com.android.settings",
            "com.android.chrome",
            "com.google.android.youtube",
            "com.android.vending",
        )

        var pollMs = DEFAULT_POLL_MS
    }

    private lateinit var usm: UsageStatsManager
    private lateinit var heartbeat: HeartbeatStore
    private val sessionStateMachine = SessionStateMachine()
    private val handler = Handler(Looper.getMainLooper())
    private var lastQueryTs = 0L
    private var currentForegroundPkg: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        heartbeat = HeartbeatStore(this)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL, "Watcher", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Grudge is watching")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        startForeground(NOTIF_ID, notif)

        lastQueryTs = System.currentTimeMillis()
        handler.post(ticker)
        Log.i(TAG, "watcher started pollMs=$pollMs watched=$WATCHED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra("pollMs", 0L) ?: 0L
        if (requested > 0) {
            pollMs = requested
            Log.i(TAG, "pollMs set to $pollMs")
        }
        return START_STICKY
    }

    private val ticker = object : Runnable {
        override fun run() {
            poll()
            handler.postDelayed(this, pollMs)
        }
    }

    private fun poll() {
        val now = System.currentTimeMillis()
        heartbeat.recordTick(now)

        // Small overlap so events landing exactly on the boundary aren't missed.
        val events = usm.queryEvents(lastQueryTs - 100, now)
        lastQueryTs = now
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue

            val previousForeground = currentForegroundPkg
            currentForegroundPkg = e.packageName
            if (previousForeground != null && previousForeground != e.packageName) {
                sessionStateMachine.onAppLeft(previousForeground, now)
            }
            if (e.packageName in WATCHED) {
                sessionStateMachine.onAppForegrounded(e.packageName, now)
            }
        }

        sessionStateMachine.tick(now)
    }

    /** Exposed for WatcherCore to relay grant/extend/done calls once T-104/T-107 exist. */
    fun stateMachine(): SessionStateMachine = sessionStateMachine

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        heartbeat.recordServiceStopped()
        super.onDestroy()
    }
}
