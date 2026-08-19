package com.memeapp.spike

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        status = TextView(this).apply { textSize = 14f }

        fun btn(label: String, fn: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { fn() }
            })
        }

        btn("1 · Grant usage access") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        btn("2 · Grant overlay") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("3 · Start watcher") {
            startForegroundService(Intent(this, WatcherService::class.java))
            refreshStatus()
        }
        root.addView(status)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val usage = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        ) == AppOpsManager.MODE_ALLOWED
        val overlay = Settings.canDrawOverlays(this)
        status.text = "usage access: $usage\noverlay: $overlay\nwatched: ${WatcherService.WATCHED.joinToString()}"
        if (usage && overlay) {
            startForegroundService(Intent(this, WatcherService::class.java))
        }
    }
}
