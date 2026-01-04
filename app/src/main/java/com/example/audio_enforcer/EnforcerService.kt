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
import android.content.pm.ServiceInfo
import android.media.AudioManager
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

    // Standard Android Volume Intent
    private val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

    // Volume Safety State
    private var cachedSafeVolume: Int = -1
    private var isSafeDeviceActive: Boolean = false
    private lateinit var audioManager: AudioManager

    private var lastHijackTime: Long = 0

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

        // Persistent Log Buffer
        val logHistory = StringBuilder()
    }

    private var a2dpProfile: BluetoothA2dp? = null
    private val btAdapter = BluetoothAdapter.getDefaultAdapter()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.A2DP) {
                a2dpProfile = proxy as BluetoothA2dp
                log("Service connected to A2DP Profile")
                // On connect, we don't know the volume state yet, so we just attempt to enforce device
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
            val action = intent?.action ?: return

            // 1. DEVICE CHANGED EVENT
            if (action == ACTION_ACTIVE_DEVICE) {

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

                // --- STATE TRACKING ---
                if (addr.equals(targetDacMac, ignoreCase = true)) {
                    isSafeDeviceActive = true

                    // Logic: If we already have a safe volume history, enforce it (protection against VAG spike).
                    // If we have NO history (-1), we must trust the current volume as a baseline.
                    if (cachedSafeVolume != -1) {
                        enforceSafeguards()
                        log("✅ Audio stabilized on DAC. Enforcing Vol: $cachedSafeVolume")
                    } else {
                        updateVolumeCache()
                        log("✅ Audio stabilized on DAC. Initial Vol: $cachedSafeVolume")
                    }
                } else {
                    // Car or Unknown -> Untrustworthy state
                    isSafeDeviceActive = false
                }

                // --- HIJACK LOGIC ---
                if (addr.equals(targetCarMac, ignoreCase = true)) {
                    lastHijackTime = System.currentTimeMillis()
                    log("⚠️ Hijack detected ($addr). Force switch!")
                    forceSwitch()
                } else if (!isSafeDeviceActive && addr != "NONE") {
                    log("ℹ️ Active: $addr (Not DAC)")
                }
            }

            // 2. VOLUME CHANGED EVENT
            else if (action == ACTION_VOLUME_CHANGED) {
                if (isSafeDeviceActive) {
                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    log("🔊 Probably manually changed on DAC: $cachedSafeVolume -> $current")

                    // Case A: First initialization. We must trust the system once.
                    if (cachedSafeVolume == -1) {
                        updateVolumeCache()
                        return
                    }

                    // Case B: We have history. Check for VAG Spike.
                    val delta = current - cachedSafeVolume
                    val isDangerZone = (System.currentTimeMillis() - lastHijackTime) < 100_000 // 100s Window

                    // Rule: If under attack AND volume jumped UP by > 3 steps
                    if (isDangerZone && delta > 3) {
                        log("🛡️ BLOCKED Spike: $cachedSafeVolume -> $current. Reverting...")
                        // Force revert immediately
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, cachedSafeVolume, 0)
                        return // Do NOT update cache
                    }

                    // Case C: Normal change (User inputs or minor fluctuations)
                    updateVolumeCache()
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

        // Init Audio
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        startStealthForeground()
        loadSettings()

        if (targetCarMac.isEmpty()) log("❌ SETUP REQUIRED in App!")
        else log("Started. Block: $targetCarMac -> Target: $targetDacMac")

        btAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)

        // Register for both Device changes and Volume changes
        val filter = IntentFilter().apply {
            addAction(ACTION_ACTIVE_DEVICE)
            addAction(ACTION_VOLUME_CHANGED)
        }
        registerReceiver(receiver, filter)
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

    private fun updateVolumeCache() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (current != cachedSafeVolume) {
            cachedSafeVolume = current
        }
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

                // --- VOLUME POLICE ---
                // Force volume restore after switch
                enforceSafeguards()

                log("🚀 Switched audio to DAC")
            } catch (e: Exception) { log("❌ Reflection Error: ${e.message}") }
        } else {
            log("⚠️ DAC is not connected via BT")
        }
    }

    private fun enforceSafeguards() {
        if (cachedSafeVolume == -1) {
            log("⚠️ Safe volume unknown, skipping restore.")
            return
        }

        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // If volume changed unexpectadly
        if (currentVol != cachedSafeVolume) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            // Check for spike
            if (currentVol == maxVol && cachedSafeVolume != maxVol) {
                log("👮 Gotcha! Volume spike (MAX) detected. Fixing...")
            } else {
                log("🔧 Restoring safe volume $currentVol -> $cachedSafeVolume")
            }

            // Restore
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, cachedSafeVolume, 0)
        }
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val fullMsg = "[$time] $msg"
        Log.d("AudioLog", fullMsg)

        synchronized(logHistory) {
            logHistory.append(fullMsg).append("\n")
            if (logHistory.length > 10000) logHistory.delete(0, 2000)
        }

        sendBroadcast(Intent(ACTION_LOG_UPDATE).setPackage(packageName).putExtra(EXTRA_LOG_MSG, fullMsg))
    }

    private fun startStealthForeground() {
        try {
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

            // Fix for Android 14 Crash: Explicitly set service type
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, notif)
            }
        } catch (e: Exception) {
            Log.e("AudioLog", "Foreground start failed: ${e.message}")
        }
    }
}
