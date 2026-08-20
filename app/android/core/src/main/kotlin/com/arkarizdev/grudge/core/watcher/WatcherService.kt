package com.arkarizdev.grudge.core.watcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.arkarizdev.grudge.core.data.GrudgeDatabase
import com.arkarizdev.grudge.core.data.SessionDao
import com.arkarizdev.grudge.core.data.SessionEntity
import com.arkarizdev.grudge.core.data.WatchedAppEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service: polls UsageEvents, drives the SessionStateMachine,
 * writes a heartbeat every tick, and (T-103) persists active sessions to
 * Room so a killed-and-restarted process doesn't lose a live grant.
 *
 * Ported from spike/ (see spike/README.md for the validated latency
 * numbers) — same poll-loop shape, now coroutine-scheduled so it can call
 * suspend DAO functions directly on the hot path instead of nesting
 * per-tick coroutine launches.
 */
class WatcherService : Service() {
    companion object {
        private const val TAG = "GrudgeWatcher"
        private const val NOTIF_CHANNEL = "watch"
        private const val NOTIF_ID = 1
        private const val DEFAULT_POLL_MS = 500L

        /**
         * Same-process reference for dev/test-only calls (debugGrant) that
         * need to reach the live instance's SessionStateMachine. Not a
         * pattern to extend for real product features — a bound service
         * would be the correct tool if this needs to grow beyond debugging.
         */
        var instance: WatcherService? = null
            private set

        /** Seeded into watched_app once, only if the table is empty (T-109 owns editing it). */
        private val DEFAULT_WATCHED = listOf(
            "com.android.settings",
            "com.android.chrome",
            "com.google.android.youtube",
            "com.android.vending",
        )

        var pollMs = DEFAULT_POLL_MS
    }

    private lateinit var usm: UsageStatsManager
    private lateinit var db: GrudgeDatabase
    private lateinit var heartbeat: HeartbeatStore
    private lateinit var intentOverlay: IntentOverlayController
    private val sessionStateMachine = SessionStateMachine()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastQueryTs = 0L
    private var currentForegroundPkg: String? = null
    private var watchedPkgs: Set<String> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        db = GrudgeDatabase.get(this)
        heartbeat = HeartbeatStore(db.heartbeatDao())
        intentOverlay = IntentOverlayController(this)

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

        // Seed + reload must finish before the first poll, or an early
        // onAppForegrounded could race an empty watchedPkgs / empty
        // restored-session map. Starting the loop from inside the same
        // launch block makes that ordering explicit rather than hoped-for.
        serviceScope.launch {
            try {
                seedWatchedAppsIfEmpty()
                watchedPkgs = db.watchedAppDao().enabled().map { it.pkg }.toSet()
                reloadActiveSessions()
                Log.i(TAG, "watcher started pollMs=$pollMs watched=$watchedPkgs")
            } catch (t: Throwable) {
                Log.e(TAG, "startup sequence failed", t)
                return@launch
            }

            while (isActive) {
                try {
                    poll()
                } catch (t: Throwable) {
                    Log.e(TAG, "poll() failed, loop continues", t)
                }
                delay(pollMs)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requested = intent?.getLongExtra("pollMs", 0L) ?: 0L
        if (requested > 0) {
            pollMs = requested
            Log.i(TAG, "pollMs set to $pollMs")
        }
        return START_STICKY
    }

    /** Dev/test-only entry point — see WatcherHostApi.debugGrant in the Pigeon contract. */
    fun debugGrant(pkg: String, minutes: Int, intentText: String?) {
        serviceScope.launch {
            try {
                val now = System.currentTimeMillis()
                if (sessionStateMachine.snapshot(pkg) == null) {
                    sessionStateMachine.onAppForegrounded(pkg, now)
                }
                val ok = sessionStateMachine.grant(pkg, minutes, intentText, now)
                Log.i(TAG, "debugGrant pkg=$pkg minutes=$minutes ok=$ok")
                if (ok) {
                    persistActiveSessions()
                    Log.i(TAG, "debugGrant persisted pkg=$pkg")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "debugGrant failed", t)
            }
        }
    }

    private suspend fun seedWatchedAppsIfEmpty() {
        val dao = db.watchedAppDao()
        if (dao.count() > 0) return
        val now = System.currentTimeMillis()
        dao.insertAll(DEFAULT_WATCHED.map { WatchedAppEntity(it, budgetMin = 15, enabled = true, addedAt = now) })
    }

    private suspend fun reloadActiveSessions() {
        val restored = db.sessionDao().findAllActive().map { it.toSession() }
        sessionStateMachine.restore(restored)
    }

    private suspend fun poll() {
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
                intentOverlay.dismissIfShowing(previousForeground)
            }
            if (e.packageName in watchedPkgs) {
                sessionStateMachine.onAppForegrounded(e.packageName, now)
                maybeShowIntentOverlay(e.packageName, e.timeStamp)
            }
        }

        sessionStateMachine.tick(now)
        persistActiveSessions()
    }

    /**
     * Shows the overlay only when the state machine is actually
     * INTENT_PENDING (fresh transition, or still awaiting a grant from an
     * earlier tick) — a no-op for RUNNING/ROASTING packages. Uses the
     * event's own timestamp, not poll-tick `now`, for latency logging —
     * same methodology as the spike (spike/README.md).
     */
    private fun maybeShowIntentOverlay(pkg: String, eventTs: Long) {
        if (sessionStateMachine.snapshot(pkg)?.state != SessionState.INTENT_PENDING) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "pkg=$pkg wants intent overlay but SYSTEM_ALERT_WINDOW not granted")
            return
        }
        intentOverlay.show(pkg, eventTs) { minutes, intentText ->
            val now = System.currentTimeMillis()
            val ok = sessionStateMachine.grant(pkg, minutes, intentText, now)
            Log.i(TAG, "grant pkg=$pkg minutes=$minutes ok=$ok")
            if (ok) {
                serviceScope.launch {
                    try {
                        persistActiveSessions()
                    } catch (t: Throwable) {
                        Log.e(TAG, "persist after grant failed", t)
                    }
                }
            }
        }
    }

    /**
     * Full-sync approach, not per-transition hooks: after every tick, mirror
     * whatever's currently active into Room and drop rows for anything that
     * fell back to IDLE. Simple and correct at poll-loop cadence for a
     * handful of sessions; T-111 will refine this once real outcomes exist.
     */
    private suspend fun persistActiveSessions() {
        val dao = db.sessionDao()
        val active = sessionStateMachine.allActiveSessions()
        val activePkgs = active.map { it.pkg }.toSet()

        for (session in active) {
            if (session.state != SessionState.RUNNING) continue // only grants are reload-worthy
            val existing = dao.findActive(session.pkg)
            if (existing == null) {
                dao.insert(session.toNewEntity())
            } else {
                dao.update(session.toEntity(existing.id, existing.openedAt))
            }
        }

        // Clean up rows for packages that returned to IDLE since the last sync.
        val previouslyActive = dao.findAllActive().map { it.pkg }.toSet()
        for (pkg in previouslyActive - activePkgs) {
            dao.clearActive(pkg)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }
}

private fun SessionEntity.toSession(): Session = Session(
    pkg = pkg,
    intentText = intentText,
    grantedMin = grantedMin,
    expiryAt = expiryAt,
    extensions = extensions,
)

private fun Session.toNewEntity(): SessionEntity = SessionEntity(
    pkg = pkg,
    openedAt = System.currentTimeMillis(),
    intentText = intentText,
    grantedMin = grantedMin ?: 0,
    expiryAt = expiryAt ?: 0L,
    extensions = extensions,
)

private fun Session.toEntity(existingId: Long, originalOpenedAt: Long): SessionEntity = SessionEntity(
    id = existingId,
    pkg = pkg,
    openedAt = originalOpenedAt,
    intentText = intentText,
    grantedMin = grantedMin ?: 0,
    expiryAt = expiryAt ?: 0L,
    extensions = extensions,
)
