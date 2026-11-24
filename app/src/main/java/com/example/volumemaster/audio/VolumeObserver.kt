package com.example.volumemaster.audio

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Fires when system volumes change from outside the app (hardware keys, other apps, system UI). */
class VolumeObserver(context: Context, private val callback: () -> Unit) {
    private val resolver = context.applicationContext.contentResolver
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { callback() }
    }
    private var registered = false

    fun start() {
        if (registered) return
        try {
            resolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
            registered = true
        } catch (_: Exception) { }
    }

    fun stop() {
        if (!registered) return
        try { resolver.unregisterContentObserver(observer) } catch (_: Exception) { }
        registered = false
    }
}
