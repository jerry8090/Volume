package com.example.volumemaster.notification

import android.content.Context
import com.example.volumemaster.audio.VolumeEvents
import com.example.volumemaster.audio.VolumeManager
import com.example.volumemaster.utils.VolumePreferences
import com.example.volumemaster.utils.reportVolumeResult

object NotificationActionHandler {

    fun handle(context: Context, action: String) {
        val vm = VolumeManager.get(context)
        val prefs = VolumePreferences(context)
        val category = prefs.selectedCategory

        val result = when (action) {
            VolumeNotification.ACTION_UP -> vm.step(category, +1)
            VolumeNotification.ACTION_DOWN -> vm.step(category, -1)
            VolumeNotification.ACTION_MUTE -> vm.toggleMute(category)
            VolumeNotification.ACTION_SWITCH -> {
                prefs.selectedCategory = category.next()
                VolumeManager.Result.OK
            }
            else -> return
        }
        context.reportVolumeResult(vm, category, result)
        // The foreground service listens to this and refreshes notification + floating panel.
        VolumeEvents.post()
    }
}
