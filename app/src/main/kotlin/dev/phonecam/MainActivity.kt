package dev.phonecam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val status by lazy { TextView(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("phonecam", MODE_PRIVATE)
        val report = probe(getSystemService(CameraManager::class.java))
        report.lines().forEach { Log.i(TAG, it) }
        val start = Button(this).apply {
            text = "Start"
            setOnClickListener { start() }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                stopService(Intent(this@MainActivity, CamService::class.java))
                refresh()
            }
        }
        val boot = CheckBox(this).apply {
            text = "Start on boot"
            isChecked = prefs.getBoolean("boot", false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("boot", on).apply() }
        }
        val dump = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            text = report
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            listOf(start, stop, boot, status, dump).forEach(::addView)
        }
        setContentView(
            ScrollView(this).apply {
                fitsSystemWindows = true
                addView(column)
            },
        )
        if (intent.getBooleanExtra("start", false)) start()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
    }

    private fun start() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        startForegroundService(Intent(this, CamService::class.java))
        status.postDelayed(::refresh, 500)
    }

    private fun refresh() {
        status.text = if (CamService.instance != null) "Streaming\nrtsp://${ip()}:8554/live\nhttp://${ip()}:8080" else "Stopped"
    }
}
