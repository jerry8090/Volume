package com.example.volumemaster.audio

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** In-process bus so dashboard, floating panel and notification stay synchronized. */
object VolumeEvents {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    fun add(listener: () -> Unit) { listeners.add(listener) }
    fun remove(listener: () -> Unit) { listeners.remove(listener) }
    fun post() { handler.post { listeners.forEach { it() } } }
}
