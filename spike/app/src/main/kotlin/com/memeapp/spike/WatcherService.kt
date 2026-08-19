package com.memeapp.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Latency spike: polls UsageEvents on a fixed interval, throws a full-screen
 * overlay when a watched package reaches the foreground, and logs
 * event-timestamp -> overlay-visible latency with running percentiles.
 *
 * Read results via:  adb logcat -s SPIKE
 */
class WatcherService : Service() {

    companion object {
        const val TAG = "SPIKE"
        val WATCHED = setOf(
            "com.android.settings",
            "com.android.chrome",
            "com.google.android.youtube",
            "com.android.vending"
        )
        var pollMs = 500L
    }

    private lateinit var usm: UsageStatsManager
    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var lastQueryTs = 0L
    private var overlayView: View? = null
    private var lastRoastPkg = ""
    private var lastRoastAt = 0L
    private val latencies = mutableListOf<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("watch", "Watcher", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, "watch")
            .setContentTitle("memeapp spike: the watch is up")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
        startForeground(1, notif)

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
        // Small overlap so events landing exactly on the boundary aren't missed.
        val events = usm.queryEvents(lastQueryTs - 100, now)
        lastQueryTs = now
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
            if (e.packageName !in WATCHED) continue
            // Debounce: one roast per package per 5s window.
            if (e.packageName == lastRoastPkg && now - lastRoastAt < 5000) continue
            lastRoastPkg = e.packageName
            lastRoastAt = now
            val detectMs = now - e.timeStamp
            showOverlay(e.packageName, e.timeStamp, detectMs)
        }
    }

    private fun showOverlay(pkg: String, eventTs: Long, detectMs: Long) {
        removeOverlay()
        val label = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(TextView(context).apply {
                text = "TIME'S UP"
                setTextColor(Color.parseColor("#FFE600"))
                textSize = 42f
                gravity = Gravity.CENTER
            })
            addView(label)
            setOnClickListener { removeOverlay() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE
        )
        wm.addView(view, lp)
        overlayView = view
        val addMs = System.currentTimeMillis() - eventTs
        var logged = false
        view.viewTreeObserver.addOnPreDrawListener {
            if (!logged) {
                logged = true
                val drawMs = System.currentTimeMillis() - eventTs
                latencies.add(drawMs)
                val sorted = latencies.sorted()
                val p50 = sorted[sorted.size / 2]
                val p90 = sorted[((sorted.size - 1) * 9) / 10]
                label.text = "$pkg\ndetect=${detectMs}ms add=${addMs}ms draw=${drawMs}ms\nn=${latencies.size} p50=${p50}ms p90=${p90}ms"
                Log.i(TAG, "ROAST pkg=$pkg pollMs=$pollMs detectMs=$detectMs addMs=$addMs drawMs=$drawMs n=${latencies.size} p50=$p50 p90=$p90")
            }
            true
        }
        // Auto-dismiss so repeated test runs don't need manual taps.
        handler.postDelayed({ removeOverlay() }, 2500)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }
}
