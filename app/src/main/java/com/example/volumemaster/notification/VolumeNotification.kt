package com.example.volumemaster.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.volumemaster.MainActivity
import com.example.volumemaster.R
import com.example.volumemaster.audio.VolumeCategory
import com.example.volumemaster.audio.VolumeManager
import com.example.volumemaster.receiver.VolumeActionReceiver
import com.example.volumemaster.utils.VolumePreferences

object VolumeNotification {

    const val CHANNEL_ID = "volume_master_controller"

    const val ACTION_UP = "com.example.volumemaster.action.VOLUME_UP"
    const val ACTION_DOWN = "com.example.volumemaster.action.VOLUME_DOWN"
    const val ACTION_MUTE = "com.example.volumemaster.action.VOLUME_MUTE"
    const val ACTION_SWITCH = "com.example.volumemaster.action.SWITCH_CATEGORY"

    /** Safe to call repeatedly - the channel is created only once. */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_desc)
            setShowBadge(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    fun build(context: Context, withControls: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)

        if (!withControls) {
            return builder
                .setContentTitle(context.getString(R.string.notif_floating_title))
                .setContentText(context.getString(R.string.notif_floating_text))
                .build()
        }

        val vm = VolumeManager.get(context)
        val selected = VolumePreferences(context).selectedCategory
        val title = context.getString(R.string.notif_title, context.getString(selected.labelRes), vm.getPercent(selected))
        val summary = context.getString(
            R.string.notif_summary,
            vm.getPercent(VolumeCategory.MEDIA), vm.getPercent(VolumeCategory.RING), vm.getPercent(VolumeCategory.ALARM)
        )
        val muteLabel = if (vm.isMuted(selected)) R.string.action_unmute else R.string.action_mute

        return builder
            .setContentTitle(title)
            .setContentText(summary)
            .addAction(R.drawable.ic_volume_down, context.getString(R.string.action_down), actionIntent(context, ACTION_DOWN, 1))
            .addAction(R.drawable.ic_volume_up, context.getString(R.string.action_up), actionIntent(context, ACTION_UP, 2))
            .addAction(R.drawable.ic_volume_off, context.getString(muteLabel), actionIntent(context, ACTION_MUTE, 3))
            .addAction(R.drawable.ic_tune, context.getString(R.string.action_switch), actionIntent(context, ACTION_SWITCH, 4))
            .build()
    }

    private fun actionIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, VolumeActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
