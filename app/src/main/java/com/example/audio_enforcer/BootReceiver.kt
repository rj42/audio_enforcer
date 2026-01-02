package com.example.audio_enforcer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            // Check if user has configured the app before auto-starting
            val prefs = context.getSharedPreferences(EnforcerService.PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(EnforcerService.KEY_CAR) && prefs.contains(EnforcerService.KEY_DAC)) {

                val i = Intent(context, EnforcerService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            }
        }
    }
}
