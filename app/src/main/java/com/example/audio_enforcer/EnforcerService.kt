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

    // === EXACT TARGET MAC ADDRESSES ===
    private val CAR_MAC = "F0:04:E1:BE:60:9E" // "B0:65:3A:2D:BC:BC"  // VW BT 3822
    private val DAC_MAC = "40:ED:98:1A:ED:7E" // "54:B7:E5:D5:4F:74"  // ATF BT HD
    // ==================================

    // Hidden API constant string
    private val ACTION_ACTIVE_DEVICE_CHANGED = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"

    companion object {
        const val ACTION_LOG_UPDATE = "com.example.media_output.LOG_UPDATE"
        const val EXTRA_LOG_MSG = "msg"

        // Flag to prevent double start
        var isServiceRunning = false
    }

    private var a2dpProfile: BluetoothA2dp? = null
    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

    // Listener for A2DP Profile connection
    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = proxy as BluetoothA2dp
                logToUI("Service connected to A2DP Profile.")
                // Immediate enforcement
                forceActiveDeviceToDac()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = null
                logToUI("A2DP Profile disconnected.")
            }
        }
    }

    // Broadcast Receiver to catch audio hijack
    private val activeDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_ACTIVE_DEVICE_CHANGED) {

                val newDevice = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                val address = newDevice?.address ?: "DISCONNECTED"

                val deviceName = when (address) {
                    CAR_MAC -> "CAR (VW)"
                    DAC_MAC -> "DAC (Target)"
                    else -> address
                }

                logToUI("Active device changed to: $deviceName")

                // === ENFORCEMENT LOGIC ===
                if (address.equals(CAR_MAC, ignoreCase = true)) {
                    logToUI("⚠️ HIJACK DETECTED! Car took audio.")
                    logToUI("⚔️ Counter-measure: Forcing DAC...")
                    forceActiveDeviceToDac()
                } else if (address.equals(DAC_MAC, ignoreCase = true)) {
                    logToUI("✅ Audio is correctly on DAC.")
                }
            }
        }
    }

    // Critical for "Terminator" mode: restarts service if system kills it
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logToUI("Service onStartCommand executed (Sticky)")
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        startMyForeground()

        logToUI("=== PROTECTION AUTO-STARTED ===")
        logToUI("Monitoring: $CAR_MAC")
        logToUI("Target: $DAC_MAC")

        bluetoothAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)

        val filter = IntentFilter(ACTION_ACTIVE_DEVICE_CHANGED)
        registerReceiver(activeDeviceReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        unregisterReceiver(activeDeviceReceiver)
        a2dpProfile?.let { bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        logToUI("=== PROTECTION STOPPED MANUALLY ===")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun forceActiveDeviceToDac() {
        val proxy = a2dpProfile
        if (proxy == null) {
            logToUI("❌ Error: A2DP not ready.")
            return
        }

        val connectedDevices = proxy.connectedDevices
        val dacDevice = connectedDevices.find { it.address.equals(DAC_MAC, ignoreCase = true) }

        if (dacDevice != null) {
            try {
                // Reflection to call hidden setActiveDevice
                logToUI("⚡ Invoking hidden API for DAC...")
                val method = proxy.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
                val success = method.invoke(proxy, dacDevice) as Boolean

                if (success) {
                    logToUI("🚀 SUCCESS: Switched to DAC")
                } else {
                    logToUI("❌ FAILURE: System rejected switch")
                }
            } catch (e: Exception) {
                logToUI("❌ Reflection Error: ${e.message}")
            }
        } else {
            logToUI("⚠️ DAC is NOT connected. Cannot switch.")
        }
    }

    private fun logToUI(msg: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val fullMsg = "[$timestamp] $msg"
        Log.d("AudioLog", fullMsg)

        val intent = Intent(ACTION_LOG_UPDATE)
        intent.setPackage(packageName)
        intent.putExtra(EXTRA_LOG_MSG, fullMsg)
        sendBroadcast(intent)
    }

    private fun startMyForeground() {
        val channelId = "AudioEnforcerID"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "BT Guard Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        // Intent to open UI if notification clicked
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Audio Enforcer Active")
            .setContentText("Protecting against hijack...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .build()
        startForeground(1, notification)
    }
}
