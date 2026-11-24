package com.example.volumemaster.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.volumemaster.utils.PermissionManager
import com.example.volumemaster.utils.VolumePreferences

/** Decides whether the foreground service should run, and starts/stops it accordingly. */
object ServiceController {

    fun sync(context: Context) {
        val app = context.applicationContext
        val prefs = VolumePreferences(app)
        val floating = prefs.floatingEnabled && PermissionManager.canDrawOverlays(app)
        if (floating || prefs.notificationEnabled) start(app) else stop(app)
    }

    fun start(context: Context) {
        try {
            val intent = Intent(context, VolumeForegroundService::class.java)
                .setAction(VolumeForegroundService.ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // e.g. started from the background on Android 12+ - the app is simply not running then.
            Log.w("ServiceController", "Could not start service", e)
        }
    }

    fun stop(context: Context) {
        try {
            context.stopService(Intent(context, VolumeForegroundService::class.java))
        } catch (e: Exception) {
            Log.w("ServiceController", "Could not stop service", e)
        }
    }
}
