package com.arkarizdev.grudge.core.watcher

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service shell. UsageEvents polling, session state machine,
 * and overlay dispatch are ported from the spike in T-102 — this scaffold
 * only proves the service is correctly registered and startable.
 */
class WatcherService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
