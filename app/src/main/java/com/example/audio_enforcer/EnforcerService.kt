package com.example.audio_enforcer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EnforcerService : Service() {

    // Dynamic variables loaded from SharedPreferences
    private var targetCarMac: String = ""
    private var targetDacMac: String = ""

    // Hidden API constant
    private val ACTION_ACTIVE_DEVICE = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"

    companion object {
        // Preference keys
        const val PREFS_NAME = "EnforcerPrefs"
        const val KEY_CAR = "pref_car"
        const val KEY_DAC = "pref_dac"

        // UI communication
        const val ACTION_LOG_UPDATE = "com.example.audio_enforcer.LOG_UPDATE"
        const val EXTRA_LOG_MSG = "msg"

        // Status flag
        var isServiceRunning = false
    }

    private var a2dpProfile: BluetoothA2dp? = null
    private val btAdapter = BluetoothAdapter.getDefaultAdapter()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = proxy as BluetoothA2dp
                log("Service connected to A2DP Profile")
                forceSwitch()
            }
        }
        override fun onServiceDisconnected(profile: Int) {
            a2dpProfile = null
            log("A2DP Profile disconnected")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ACTIVE_DEVICE) {

                // Always reload settings to ensure hot-swapping works
                loadSettings()

                if (targetCarMac.isEmpty() || targetDacMac.isEmpty()) {
                    log("⚠️ Config missing! Select devices in App.")
                    return
                }

                val newDevice = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                val addr = newDevice?.address ?: "NONE"

                // Logic
                if (addr.equals(targetCarMac, ignoreCase = true)) {
                    log("⚠️ Hijack detected ($addr). Forcing DAC...")
                    forceSwitch()
                } else if (addr.equals(targetDacMac, ignoreCase = true)) {
                    log("✅ Audio successfully on DAC.")
                } else {
                    log("Active device changed to: $addr")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadSettings()
        return START_STICKY // Restart if killed
    }

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        startStealthForeground() // Minimal notification
        loadSettings()

        if (targetCarMac.isEmpty()) log("❌ SETUP REQUIRED in App!")
        else log("Started. Block: $targetCarMac -> Target: $targetDacMac")

        btAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
        registerReceiver(receiver, IntentFilter(ACTION_ACTIVE_DEVICE))
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        unregisterReceiver(receiver)
        a2dpProfile?.let { btAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        log("Service Stopped.")
    }

    override fun onBind(i: Intent?): IBinder? = null

    private fun loadSettings() {
        val p = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        targetCarMac = p.getString(KEY_CAR, "") ?: ""
        targetDacMac = p.getString(KEY_DAC, "") ?: ""
    }

    @SuppressLint("MissingPermission")
    private fun forceSwitch() {
        val proxy = a2dpProfile ?: return
        loadSettings()
        if (targetDacMac.isEmpty()) return

        val dac = proxy.connectedDevices.find { it.address.equals(targetDacMac, ignoreCase = true) }
        if (dac != null) {
            try {
                // Reflection to call internal API
                proxy.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java).invoke(proxy, dac)
                log("🚀 Switched audio to DAC")
            } catch (e: Exception) { log("❌ Reflection Error: ${e.message}") }
        } else {
            log("⚠️ DAC is not connected via BT")
        }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        Log.d("AudioLog", msg)
        sendBroadcast(Intent(ACTION_LOG_UPDATE).setPackage(packageName).putExtra(EXTRA_LOG_MSG, "[$time] $msg"))
    }

    private fun startStealthForeground() {
        val chanId = "DaemonChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // MANAGE_IMPORTANCE_MIN makes it silent and collapsed
            val chan = NotificationChannel(chanId, "Audio Enforcer", NotificationManager.IMPORTANCE_MIN)
            chan.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }

        val pIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        val notif = Notification.Builder(this, chanId)
            .setContentTitle("Audio Enforcer")
            .setContentText("Protecting audio output...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pIntent)
            .build()
        startForeground(1, notif)
    }
}
