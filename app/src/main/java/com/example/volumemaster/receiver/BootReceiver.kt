package com.example.volumemaster.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.volumemaster.service.ServiceController
import com.example.volumemaster.utils.VolumePreferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val app = context.applicationContext
                if (VolumePreferences(app).autoStart) {
                    // sync() re-checks the overlay permission and feature switches first.
                    ServiceController.sync(app)
                }
            }
        }
    }
}
