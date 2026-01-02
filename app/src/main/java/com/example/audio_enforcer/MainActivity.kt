package com.example.audio_enforcer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var statusIndicator: TextView

    // Receiver to capture logs from Service
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == EnforcerService.ACTION_LOG_UPDATE) {
                val msg = intent.getStringExtra(EnforcerService.EXTRA_LOG_MSG) ?: ""
                appendLog(msg)

                // If we get logs, it means it's running
                updateStatusIndicator(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // --- UI BUILDER ---
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Top padding 150 to fix status bar overlap
            setPadding(30, 150, 30, 30)
            setBackgroundColor(Color.parseColor("#121212")) // Dark BG
        }

        // 1. Header
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 50)
        }

        statusIndicator = TextView(this).apply {
            text = "● OFF"
            textSize = 20f
            setTextColor(Color.RED)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 30, 0)
        }

        val title = TextView(this).apply {
            text = "AUDIO ENFORCER"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }

        headerLayout.addView(statusIndicator)
        headerLayout.addView(title)
        rootLayout.addView(headerLayout)

        // 2. Buttons
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 40)
        }

        val btnStart = Button(this).apply {
            text = "START"
            setBackgroundColor(Color.parseColor("#2E7D32")) // Green
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (checkPermissions()) startEnforcer()
            }
        }

        val spacer = TextView(this).apply { width = 40 }

        val btnStop = Button(this).apply {
            text = "STOP"
            setBackgroundColor(Color.parseColor("#C62828")) // Red
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopEnforcer()
            }
        }

        btnLayout.addView(btnStart)
        btnLayout.addView(spacer)
        btnLayout.addView(btnStop)
        rootLayout.addView(btnLayout)

        // 3. Log Title
        val logTitle = TextView(this).apply {
            text = "SYSTEM LOGS:"
            setTextColor(Color.LTGRAY)
            setPadding(0, 0, 0, 15)
        }
        rootLayout.addView(logTitle)

        // 4. Log Console
        logTextView = TextView(this).apply {
            text = "Initializing...\n"
            setTextColor(Color.GREEN)
            setBackgroundColor(Color.BLACK)
            setPadding(25, 25, 25, 25)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            movementMethod = ScrollingMovementMethod()
        }
        rootLayout.addView(logTextView)

        setContentView(rootLayout)

        // --- AUTO-START LOGIC ---
        if (checkPermissions()) {
            if (EnforcerService.isServiceRunning) {
                appendLog(">>> Service is already running.")
                updateStatusIndicator(true)
            } else {
                appendLog(">>> Auto-starting Service...")
                startEnforcer()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(EnforcerService.ACTION_LOG_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        updateStatusIndicator(EnforcerService.isServiceRunning)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(logReceiver)
    }

    private fun startEnforcer() {
        val intent = Intent(this, EnforcerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatusIndicator(true)
    }

    private fun stopEnforcer() {
        val intent = Intent(this, EnforcerService::class.java)
        stopService(intent)
        updateStatusIndicator(false)
        appendLog(">>> STOPPED MANUALLY")
    }

    private fun updateStatusIndicator(isOn: Boolean) {
        if (isOn) {
            statusIndicator.text = "● ON"
            statusIndicator.setTextColor(Color.GREEN)
        } else {
            statusIndicator.text = "● OFF"
            statusIndicator.setTextColor(Color.RED)
        }
    }

    private fun appendLog(msg: String) {
        val currentText = logTextView.text.toString()
        val newText = if (currentText.length > 5000) {
            currentText.substring(0, 4000) + "\n...[old logs cleared]...\n" + msg
        } else {
            "$currentText\n$msg"
        }
        logTextView.text = newText

        // FIX: Оборачиваем скролл в post, чтобы он выполнялся ПОСЛЕ отрисовки UI
        logTextView.post {
            // Проверяем, что layout существует (защита от NPE)
            if (logTextView.layout != null) {
                val scrollAmount = logTextView.layout.getLineTop(logTextView.lineCount) - logTextView.height
                if (scrollAmount > 0) {
                    logTextView.scrollTo(0, scrollAmount)
                }
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
            return false
        }
        return true
    }
}
