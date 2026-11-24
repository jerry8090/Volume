package com.example.volumemaster.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.volumemaster.notification.NotificationActionHandler

/** Receives notification button taps; works even when no activity is open. */
class VolumeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        NotificationActionHandler.handle(context.applicationContext, action)
    }
}
