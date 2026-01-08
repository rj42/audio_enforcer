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
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EnforcerService : Service() {

    private var targetCarMac: String = ""
    private var targetDacMac: String = ""

    private val ACTION_ACTIVE_DEVICE = "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"

    // Volume Safety State
    private var cachedSafeVolume: Int = -1
    private var isSafeDeviceActive: Boolean = false
    private lateinit var audioManager: AudioManager

    private var lastHijackTime: Long = 0

    // --- CLAMPER LOGIC ---
    private val handler = Handler(Looper.getMainLooper())
    private var isClamping = false

    private val stopClampingRunnable = Runnable {
        stopClamping()
        log("🛡️ Clamping finished.")
    }

    // This runnable will hammer the volume back to safe level every 10ms
    private val volumeClamper = object : Runnable {
        override fun run() {
            if (!isClamping) return

            if (cachedSafeVolume != -1) {
                try {
                    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (current != cachedSafeVolume) {
                        log("🔨 CLAMP HIT: Detected $current. Forcing $cachedSafeVolume.")
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, cachedSafeVolume, 0)
                    }
                } catch (e: Exception) { }
            }
            // Repeat every 10ms
            handler.postDelayed(this, 10)
        }
    }


    private lateinit var volumeObserver: VolumeContentObserver

    companion object {
        const val PREFS_NAME = "EnforcerPrefs"
        const val KEY_CAR = "pref_car"
        const val KEY_DAC = "pref_dac"
        const val ACTION_LOG_UPDATE = "com.example.audio_enforcer.LOG_UPDATE"
        const val EXTRA_LOG_MSG = "msg"
        var isServiceRunning = false
        val logHistory = StringBuilder()
    }

    private var a2dpProfile: BluetoothA2dp? = null
    private val btAdapter = BluetoothAdapter.getDefaultAdapter()

    inner class VolumeContentObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            handleVolumeChange()
        }
    }

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
            val action = intent?.action ?: return

            if (action == ACTION_ACTIVE_DEVICE) {
                loadSettings()
                if (targetCarMac.isEmpty() || targetDacMac.isEmpty()) return

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

                    // Defensive clamping upon return to DAC, because VAG often sends a late volume command.
                    startClamping()

                    if (cachedSafeVolume != -1) {
                        enforceSafeguards()
                        log("✅ Audio stabilized on DAC. Enforcing Vol: $cachedSafeVolume")
                    } else {
                        // First run logic
                        updateVolumeCache()
                        log("✅ Audio stabilized on DAC. Initial Vol: $cachedSafeVolume")
                    }
                } else {
                    isSafeDeviceActive = false
                }

                // --- HIJACK LOGIC ---
                if (addr.equals(targetCarMac, ignoreCase = true)) {
                    lastHijackTime = System.currentTimeMillis()
                    log("⚠️ Hijack detected ($addr). Force switch!")

                    // ACTIVATE NUCLEAR DEFENSE (Clamping) at the moment of hijack
                    startClamping()

                    forceSwitch()
                } else if (!isSafeDeviceActive && addr != "NONE") {
                    log("ℹ️ Active: $addr (Not DAC)")
                }
            }
        }
    }

    private fun handleVolumeChange() {
        // If claming is active, we IGNORE all volume changes and let the Clamper do its job
        if (isClamping) return

        if (isSafeDeviceActive) {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // Case A: First initialization.
            if (cachedSafeVolume == -1) {
                updateVolumeCache()
                return
            }

            // Case B: SPIKE PROTECTION
            val delta = current - cachedSafeVolume
            val isDangerZone = (System.currentTimeMillis() - lastHijackTime) < 60_000 // 60s Window

            if (isDangerZone && delta > 3) {
                log("🛡️ BLOCKED Spike: $cachedSafeVolume -> $current. Reverting & Clamping...")

                // If a spike got through, activate Clamping immediately
                startClamping()

                // Revert immediately just in case clamper has delay
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, cachedSafeVolume, 0)
                return
            }

            // Case C: Normal change
            if (cachedSafeVolume != current) {
                log("🔊 Probably manual change: $cachedSafeVolume -> $current")
            }
            updateVolumeCache()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        loadSettings()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        startStealthForeground()
        loadSettings()

        if (targetCarMac.isEmpty()) log("❌ SETUP REQUIRED")
        else log("Started. Block: $targetCarMac -> Target: $targetDacMac")

        btAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)

        val filter = IntentFilter(ACTION_ACTIVE_DEVICE)
        registerReceiver(receiver, filter)

        volumeObserver = VolumeContentObserver(Handler(Looper.getMainLooper()))
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopClamping() // Important cleanup
        contentResolver.unregisterContentObserver(volumeObserver)
        unregisterReceiver(receiver)
        a2dpProfile?.let { btAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        log("Stopped.")
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

    // --- CLAMPER HELPERS ---
    private fun startClamping() {
        // Only clamp if we have a valid safe volume
        if (cachedSafeVolume == -1) return

        // Cancel pending stop so we extend the time instead of cutting short
        handler.removeCallbacks(stopClampingRunnable)

        if (!isClamping) {
            log("🛡️ Clamping started.")
        } else {
            log("🛡️ Clamping continued.")
        }

        isClamping = true
        // Restart the loop
        handler.removeCallbacks(volumeClamper)
        handler.post(volumeClamper)

        // Stop automatically after 10 seconds of war
        handler.postDelayed(stopClampingRunnable, 10_000)
    }

    private fun stopClamping() {
        isClamping = false
        handler.removeCallbacks(volumeClamper)
        handler.removeCallbacks(stopClampingRunnable)
    }
    // -----------------------

    // ... (forceSwitch, enforceSafeguards, log, startStealthForeground - SAME AS BEFORE) ...
    // Just to keep the response short, paste the previous methods here.
    // They did not change logic, but I can include them if needed.

    @SuppressLint("MissingPermission")
    private fun forceSwitch() {
        val proxy = a2dpProfile ?: return
        loadSettings()
        if (targetDacMac.isEmpty()) return

        val dac = proxy.connectedDevices.find { it.address.equals(targetDacMac, ignoreCase = true) }
        if (dac != null) {
            try {
                proxy.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java).invoke(proxy, dac)

                // Volume check after switch command
                enforceSafeguards()

                log("🚀 Switched audio to DAC")
            } catch (e: Exception) { log("❌ Reflect Err: ${e.message}") }
        } else {
            log("⚠️ DAC not connected")
        }
    }

    private fun enforceSafeguards() {
        if (cachedSafeVolume == -1) return

        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (currentVol != cachedSafeVolume) {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (currentVol == maxVol && cachedSafeVolume != maxVol) {
                log("👮 Gotcha! Volume spike (MAX) detected. Fixing...")
                startClamping()
            }
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
                val chan = NotificationChannel(chanId, "Audio Enforcer", NotificationManager.IMPORTANCE_MIN)
                chan.setShowBadge(false)
                getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
            }
            val pIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notif = Notification.Builder(this, chanId)
                .setContentTitle("Audio Enforcer")
                .setContentText("Protecting...")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pIntent)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(1, notif)
            }
        } catch (e: Exception) { Log.e("AudioLog", "Fg err: ${e.message}") }
    }
}
