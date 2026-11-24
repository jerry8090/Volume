package com.example.volumemaster.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.example.volumemaster.audio.VolumeEvents
import com.example.volumemaster.audio.VolumeManager
import com.example.volumemaster.audio.VolumeObserver
import com.example.volumemaster.notification.VolumeNotification
import com.example.volumemaster.overlay.FloatingWindowManager
import com.example.volumemaster.utils.PermissionManager
import com.example.volumemaster.utils.VolumePreferences

/**
 * Owns the ongoing notification and (optionally) the floating overlay.
 * One service instance => no duplicate overlay windows or notifications.
 */
class VolumeForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.example.volumemaster.action.START"
        const val ACTION_STOP = "com.example.volumemaster.action.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "VolumeService"

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var prefs: VolumePreferences
    private lateinit var volumeManager: VolumeManager
    private lateinit var floating: FloatingWindowManager
    private lateinit var observer: VolumeObserver
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false

    private val eventListener: () -> Unit = { scheduleRefresh() }

    // Must be held in a field: SharedPreferences keeps only a weak reference.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            // Feature switches (or a full reset, which reports a null key on Android 11+).
            null, VolumePreferences.KEY_FLOATING, VolumePreferences.KEY_NOTIFICATION -> applyFeatures()
            // Handle appearance / edge / auto-collapse changed in Settings.
            VolumePreferences.KEY_SIZE, VolumePreferences.KEY_OPACITY,
            VolumePreferences.KEY_EDGE, VolumePreferences.KEY_AUTO_COLLAPSE -> floating.applySettings()
            // Selected channel changed (notification "Switch", dashboard chips).
            VolumePreferences.KEY_SELECTED -> scheduleRefresh()
            // Position keys, pre-mute levels etc. are ignored on purpose.
            else -> Unit
        }
    }

    private val refreshRunnable = Runnable {
        floating.refresh()
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, VolumeNotification.build(this, prefs.notificationEnabled))
        } catch (e: Exception) {
            Log.w(TAG, "Notification update failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = VolumePreferences(this)
        volumeManager = VolumeManager.get(this)
        floating = FloatingWindowManager(this, volumeManager, prefs)
        observer = VolumeObserver(this) { scheduleRefresh() }
        VolumeNotification.createChannel(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Must happen quickly after startForegroundService().
        if (!enterForeground()) return START_NOT_STICKY

        if (!initialized) {
            initialized = true
            observer.start()
            VolumeEvents.add(eventListener)
            prefs.register(prefListener)
        }
        // A null intent means the system restarted us (START_STICKY): re-validate everything.
        applyFeatures()
        return START_STICKY
    }

    private fun enterForeground(): Boolean = try {
        val notification = VolumeNotification.build(this, prefs.notificationEnabled)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "startForeground failed", e)
        stopSelf()
        false
    }

    private fun applyFeatures() {
        val showFloating = prefs.floatingEnabled && PermissionManager.canDrawOverlays(this)
        if (!showFloating && !prefs.notificationEnabled) {
            stopSelf()
            return
        }
        if (showFloating) floating.show() else floating.hide()
        scheduleRefresh()
    }

    /** Debounced so fast slider drags do not exceed Android's notification rate limit. */
    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 80L)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        floating.reposition()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (initialized) {
            observer.stop()
            VolumeEvents.remove(eventListener)
            prefs.unregister(prefListener)
        }
        floating.hide()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
