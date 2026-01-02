package com.example.audio_enforcer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
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
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var btnToggle: Button
    private lateinit var spinnerCar: Spinner
    private lateinit var spinnerDac: Spinner
    private val btAdapter = BluetoothAdapter.getDefaultAdapter()

    // Helper class for Spinner display
    data class DeviceItem(val name: String, val addr: String) {
        override fun toString() = "$name ($addr)"
    }

    // Receiver for logs
    private val logRec = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == EnforcerService.ACTION_LOG_UPDATE) {
                appendLog(i.getStringExtra(EnforcerService.EXTRA_LOG_MSG) ?: "")
                refreshBtnState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        // --- UI CONSTRUCTION ---
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 150, 30, 30) // Top padding for status bar
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val title = TextView(this).apply {
            text = "AUDIO ENFORCER"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        root.addView(title)

        // Settings Container
        val configBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(20, 20, 20, 20)
        }

        // CAR Spinner
        configBox.addView(TextView(this).apply { text="Hijacker (Car):"; setTextColor(Color.GRAY) })
        spinnerCar = Spinner(this).apply { setBackgroundColor(Color.LTGRAY) }
        configBox.addView(spinnerCar)

        // DAC Spinner
        configBox.addView(TextView(this).apply { text="Target (DAC):"; setTextColor(Color.GRAY); setPadding(0,20,0,0) })
        spinnerDac = Spinner(this).apply { setBackgroundColor(Color.LTGRAY) }
        configBox.addView(spinnerDac)

        root.addView(configBox)

        // Toggle Button
        btnToggle = Button(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 50 }
            setOnClickListener { if(checkPerms()) toggle() }
        }
        root.addView(btnToggle)

        // Log Console
        logTextView = TextView(this).apply {
            setTextColor(Color.GREEN)
            setBackgroundColor(Color.BLACK)
            setPadding(20, 20, 20, 20)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(-1, -1).apply { topMargin = 40 }
            movementMethod = ScrollingMovementMethod()
        }
        root.addView(logTextView)
        setContentView(root)

        // --- LOGIC ---
        if(checkPerms()) {
            initSpinners()
            refreshBtnState()

            // Auto-Start Check: If config exists and service is not running
            if (!EnforcerService.isServiceRunning) {
                val p = getSharedPreferences(EnforcerService.PREFS_NAME, Context.MODE_PRIVATE)
                if (p.contains(EnforcerService.KEY_CAR) && p.contains(EnforcerService.KEY_DAC)) {
                    toggle() // Auto-start
                }
            }
        }
    }

    private fun initSpinners() {
        if (!btAdapter.isEnabled) return

        val list = ArrayList<DeviceItem>()
        list.add(DeviceItem("- Select -", "")) // Default empty option

        // Populate with bonded devices
        btAdapter.bondedDevices.forEach {
            list.add(DeviceItem(it.name ?: "Unknown", it.address))
        }

        val adp = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list)
        spinnerCar.adapter = adp
        spinnerDac.adapter = adp

        // Load saved preferences
        val prefs = getSharedPreferences(EnforcerService.PREFS_NAME, Context.MODE_PRIVATE)
        val saveCar = prefs.getString(EnforcerService.KEY_CAR, "")
        val saveDac = prefs.getString(EnforcerService.KEY_DAC, "")

        // Restore selection
        list.indexOfFirst { it.addr == saveCar }.takeIf { it > 0 }?.let { spinnerCar.setSelection(it) }
        list.indexOfFirst { it.addr == saveDac }.takeIf { it > 0 }?.let { spinnerDac.setSelection(it) }

        // Save on selection change
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val item = p?.getItemAtPosition(pos) as DeviceItem
                val key = if(p == spinnerCar) EnforcerService.KEY_CAR else EnforcerService.KEY_DAC
                prefs.edit().putString(key, item.addr).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerCar.onItemSelectedListener = listener
        spinnerDac.onItemSelectedListener = listener
    }

    private fun toggle() {
        val i = Intent(this, EnforcerService::class.java)
        if (EnforcerService.isServiceRunning) {
            stopService(i)
            appendLog("STOPPED.")
        } else {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        }
        // Small delay to allow service to start up and update status
        logTextView.postDelayed({ refreshBtnState() }, 200)
    }

    private fun refreshBtnState() {
        if (EnforcerService.isServiceRunning) {
            btnToggle.text = "STOP DAEMON"
            btnToggle.setBackgroundColor(Color.parseColor("#B71C1C")) // Red
        } else {
            btnToggle.text = "START DAEMON"
            btnToggle.setBackgroundColor(Color.parseColor("#1B5E20")) // Green
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(EnforcerService.ACTION_LOG_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(logRec, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(logRec, filter)
        refreshBtnState()
    }

    override fun onPause() { super.onPause(); unregisterReceiver(logRec) }

    private fun appendLog(msg: String) {
        logTextView.text = "${logTextView.text}\n$msg".takeLast(5000)
        logTextView.post {
            if(logTextView.layout != null) {
                val s = logTextView.layout.getLineTop(logTextView.lineCount) - logTextView.height
                if(s > 0) logTextView.scrollTo(0, s)
            }
        }
    }

    private fun checkPerms(): Boolean {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS)
        if (p.isNotEmpty()) { ActivityCompat.requestPermissions(this, p.toTypedArray(), 101); return false }
        return true
    }
}
