package com.example.volumemaster

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.volumemaster.utils.VolumePreferences

class VolumeMasterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(VolumePreferences(this).nightMode())
    }
}
