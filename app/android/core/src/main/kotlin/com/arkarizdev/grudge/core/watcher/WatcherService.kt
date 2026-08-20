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
import com.arkarizdev.grudge.core.data.RoastPayloadEntity
import com.arkarizdev.grudge.core.data.SessionDao
import com.arkarizdev.grudge.core.data.SessionEntity
import com.arkarizdev.grudge.core.data.WatchedAppEntity
import com.arkarizdev.grudge.core.roast.RoastEngine
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
        private const val GRANT_LOG_TAG = "GrudgeGrantLog" // T-107: PRD P0-4 "every grant and extension logged"
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
    private lateinit var roastOverlay: RoastOverlayController
    private lateinit var roastEngine: RoastEngine
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
        roastOverlay = RoastOverlayController(this)
        roastEngine = RoastEngine(this)

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
                    logGrantEvent(kind = "GRANT", pkg = pkg, minutes = minutes, extensionsSoFar = 0, hasIntentText = !intentText.isNullOrBlank(), now = now)
                    precomputeRoast(pkg, intentText, grantedMin = minutes, extensionsSoFar = 0)
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
                roastOverlay.dismissIfShowing(previousForeground)
            }
            if (e.packageName in watchedPkgs) {
                sessionStateMachine.onAppForegrounded(e.packageName, now)
                maybeShowIntentOverlay(e.packageName, e.timeStamp)
            }
        }

        sessionStateMachine.tick(now)
        persistActiveSessions()
        maybeShowRoastOverlay(now)
    }

    /**
     * Checked after every tick(), not event-driven like the intent overlay
     * — RUNNING -> ROASTING is a time-driven transition, not tied to a
     * UsageEvent. show() itself no-ops if already showing for a pkg, so
     * this is safe to call every poll tick without extra bookkeeping here.
     */
    private suspend fun maybeShowRoastOverlay(now: Long) {
        val roasting = sessionStateMachine.allActiveSessions().filter { it.state == SessionState.ROASTING }
        for (session in roasting) {
            if (roastOverlay.isShowing(session.pkg)) continue
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "pkg=${session.pkg} wants roast overlay but SYSTEM_ALERT_WINDOW not granted")
                continue
            }
            val sessionRow = db.sessionDao().findActive(session.pkg) ?: continue
            val payload = db.roastPayloadDao().latestFor(sessionRow.id)
            if (payload == null) {
                // No precomputed roast to show — most likely a session that
                // predates RoastEngine (T-105) being wired in, restored from
                // an old Room row. There's nothing to display, and leaving
                // it in ROASTING forever means this branch re-runs every
                // poll tick indefinitely (a real bug, caught live during
                // T-106 testing — the fix is to resolve the session as if
                // the user had tapped "done," not to keep retrying).
                Log.w(TAG, "pkg=${session.pkg} is ROASTING with no roast_payload — resolving via markDone, nothing to show")
                sessionStateMachine.markDone(session.pkg, now)
                continue
            }
            roastOverlay.show(
                pkg = session.pkg,
                line1 = payload.line1,
                line2 = payload.line2,
                assetRef = payload.assetRef,
                eventTs = now,
                extensionsSoFar = session.extensions,
                onDone = {
                    sessionStateMachine.markDone(session.pkg, System.currentTimeMillis())
                    Log.i(TAG, "markDone pkg=${session.pkg}")
                    serviceScope.launch { persistActiveSessions() }
                },
                onExtend = { additionalMinutes ->
                    val extendNow = System.currentTimeMillis()
                    val ok = sessionStateMachine.extend(session.pkg, additionalMinutes, extendNow)
                    Log.i(TAG, "extend pkg=${session.pkg} minutes=$additionalMinutes ok=$ok")
                    if (ok) {
                        serviceScope.launch {
                            try {
                                persistActiveSessions()
                                val extendedSession = sessionStateMachine.snapshot(session.pkg)
                                logGrantEvent(
                                    kind = "EXTEND",
                                    pkg = session.pkg,
                                    minutes = additionalMinutes,
                                    extensionsSoFar = extendedSession?.extensions ?: 1,
                                    hasIntentText = !extendedSession?.intentText.isNullOrBlank(),
                                    now = extendNow,
                                )
                                precomputeRoast(
                                    pkg = session.pkg,
                                    intentText = extendedSession?.intentText,
                                    grantedMin = additionalMinutes,
                                    extensionsSoFar = extendedSession?.extensions ?: 1,
                                )
                            } catch (t: Throwable) {
                                Log.e(TAG, "persist/precompute after extend failed", t)
                            }
                        }
                    }
                },
            )
        }
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
                        logGrantEvent(kind = "GRANT", pkg = pkg, minutes = minutes, extensionsSoFar = 0, hasIntentText = !intentText.isNullOrBlank(), now = now)
                        precomputeRoast(pkg, intentText, grantedMin = minutes, extensionsSoFar = 0)
                    } catch (t: Throwable) {
                        Log.e(TAG, "persist/precompute after grant failed", t)
                    }
                }
            }
        }
    }

    /**
     * T-107 / PRD P0-4: "every grant and extension logged with timestamps
     * and session context." A dedicated tag rather than folding into the
     * general TAG logs keeps this filterable on its own — analytics_evt
     * (the durable, synced event queue) stays T-203's job per its own doc
     * comment; this is the always-on debug/observability trail, sufficient
     * for verifying the friction gradient and grant history live via
     * logcat without a network dependency.
     */
    private suspend fun logGrantEvent(
        kind: String,
        pkg: String,
        minutes: Int,
        extensionsSoFar: Int,
        hasIntentText: Boolean,
        now: Long,
    ) {
        val sessionId = db.sessionDao().findActive(pkg)?.id
        Log.i(
            GRANT_LOG_TAG,
            "kind=$kind pkg=$pkg minutes=$minutes extensionsSoFar=$extensionsSoFar " +
                "hasIntentText=$hasIntentText sessionId=$sessionId ts=$now",
        )
    }

    /**
     * T-105: fills roast_payload for the roast this session will show IF
     * it expires naturally — precomputed now, read (never computed) at
     * display time. elapsedMin is set to grantedMin here (the projection
     * "you'll have used about what you asked for"), which the tech plan
     * accepts as accurate enough for the common case; see RoastEngine's
     * own doc comment for the full reasoning. Session Room row must exist
     * first (persistActiveSessions() just ran) — a roast with no session
     * to attach to isn't meaningful, so this silently no-ops if not found
     * rather than inventing a placeholder sessionId.
     */
    private suspend fun precomputeRoast(pkg: String, intentText: String?, grantedMin: Int, extensionsSoFar: Int) {
        val sessionRow = db.sessionDao().findActive(pkg) ?: run {
            Log.w(TAG, "precomputeRoast: no session row for pkg=$pkg, skipping")
            return
        }
        val result = roastEngine.precompute(
            pkg = pkg,
            intentText = intentText,
            grantedMin = grantedMin,
            elapsedMin = grantedMin,
            extensionsSoFar = extensionsSoFar,
        ) ?: run {
            Log.w(TAG, "precomputeRoast: no template matched for pkg=$pkg")
            return
        }
        db.roastPayloadDao().insert(
            RoastPayloadEntity(
                sessionId = sessionRow.id,
                tier = result.tier,
                line1 = result.line1,
                line2 = result.line2,
                assetRef = result.asset,
                createdAt = System.currentTimeMillis(),
                gifSource = "BUNDLED", // T-208/T-209 wire the live Tenor path; bundled is always correct as-of T-105
                gifLocalPath = null,
            )
        )
        Log.i(TAG, "precomputeRoast pkg=$pkg templateId=${result.templateId} tier=${result.tier}")
    }

    /**
     * Full-sync approach, not per-transition hooks: after every tick, mirror
     * whatever's currently active into Room and drop rows for anything that
     * fell back to IDLE. Called after every mutation (grant/extend/markDone)
     * and every poll tick, so draining finished sessions here — rather than
     * only in poll() — catches outcomes from all of those paths uniformly.
     */
    private suspend fun persistActiveSessions() {
        val dao = db.sessionDao()

        // T-111: write finalized outcomes as history (endedAt/outcome/
        // overageS) instead of deleting the row — first time a session row
        // survives past the grant it represents.
        for (finished in sessionStateMachine.drainFinishedSessions()) {
            val existing = dao.findActive(finished.pkg) ?: continue
            dao.markEnded(existing.id, finished.endedAt, finished.outcome.name, finished.overageS, finished.extensions)
            Log.i(TAG, "session ended pkg=${finished.pkg} outcome=${finished.outcome} overageS=${finished.overageS}")
        }

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

        // Defensive fallback only — under normal operation the drain above
        // already accounts for every pkg that leaves the active set via a
        // real outcome. Anything caught here would mean a path exists that
        // drops to IDLE without finalizing, which would itself be a bug.
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
