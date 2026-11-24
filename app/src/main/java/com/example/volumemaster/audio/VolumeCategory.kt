package com.example.volumemaster.audio

import android.media.AudioManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.example.volumemaster.R

enum class VolumeCategory(
    val key: String,
    val stream: Int,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int,
    @DrawableRes val iconRes: Int
) {
    MEDIA("media", AudioManager.STREAM_MUSIC, R.string.media, R.string.media_desc, R.drawable.ic_music),
    RING("ring", AudioManager.STREAM_RING, R.string.ringtone, R.string.ringtone_desc, R.drawable.ic_call),
    ALARM("alarm", AudioManager.STREAM_ALARM, R.string.alarm, R.string.alarm_desc, R.drawable.ic_alarm);

    fun next(): VolumeCategory = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromKey(key: String?): VolumeCategory = values().firstOrNull { it.key == key } ?: MEDIA
    }
}
