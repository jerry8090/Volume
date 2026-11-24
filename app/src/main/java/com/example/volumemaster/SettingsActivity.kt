package com.example.volumemaster

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.example.volumemaster.audio.VolumeCategory
import com.example.volumemaster.audio.VolumeEvents
import com.example.volumemaster.databinding.ActivitySettingsBinding
import com.example.volumemaster.service.ServiceController
import com.example.volumemaster.utils.PermissionManager
import com.example.volumemaster.utils.VolumePreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: VolumePreferences
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = VolumePreferences(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        loadValues()
        setupListeners()
    }

    private fun alignedSize(v: Int) = 40 + ((v.coerceIn(40, 96) - 40) / 4) * 4
    private fun alignedOpacity(v: Int) = 30 + ((v.coerceIn(30, 100) - 30) / 5) * 5

    private fun autoCollapseLabel(seconds: Int) =
        if (seconds == 0) getString(R.string.auto_collapse_never) else getString(R.string.auto_collapse_value, seconds)

    private fun loadValues() {
        loading = true
        binding.switchFloating.isChecked = prefs.floatingEnabled
        binding.switchNotification.isChecked = prefs.notificationEnabled
        binding.switchBoot.isChecked = prefs.autoStart

        val size = alignedSize(prefs.buttonSizeDp)
        binding.sliderSize.value = size.toFloat()
        binding.txtSize.text = getString(R.string.size_value, size)

        val opacity = alignedOpacity(prefs.opacityPercent)
        binding.sliderOpacity.value = opacity.toFloat()
        binding.txtOpacity.text = getString(R.string.opacity_value, opacity)

        binding.radioGroupEdge.check(
            if (prefs.floatEdge == VolumePreferences.EDGE_LEFT) R.id.radio_edge_left else R.id.radio_edge_right
        )
        val delay = prefs.autoCollapseSeconds.coerceIn(0, 15)
        binding.sliderAutoCollapse.value = delay.toFloat()
        binding.txtAutoCollapse.text = autoCollapseLabel(delay)
        binding.switchStartExpanded.isChecked = prefs.startExpanded

        binding.radioGroupCategory.check(
            when (prefs.defaultCategory) {
                VolumeCategory.MEDIA -> R.id.radio_media
                VolumeCategory.RING -> R.id.radio_ring
                VolumeCategory.ALARM -> R.id.radio_alarm
            }
        )
        binding.radioGroupTheme.check(
            when (prefs.theme) {
                VolumePreferences.THEME_LIGHT -> R.id.radio_light
                VolumePreferences.THEME_SYSTEM -> R.id.radio_system
                else -> R.id.radio_dark
            }
        )
        loading = false
    }

    private fun setupListeners() {
        binding.switchFloating.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            prefs.floatingEnabled = checked
            if (checked && !PermissionManager.canDrawOverlays(this)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.perm_overlay_title)
                    .setMessage(R.string.perm_overlay_msg)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(PermissionManager.overlaySettingsIntent(this))
                        } catch (e: Exception) {
                            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
                        }
                    }
                    .setNegativeButton(R.string.not_now, null)
                    .show()
            }
            ServiceController.sync(this)
        }
        binding.switchNotification.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            prefs.notificationEnabled = checked
            ServiceController.sync(this)
        }
        binding.switchBoot.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            prefs.autoStart = checked
        }

        binding.sliderSize.addOnChangeListener { _, value, fromUser ->
            val v = value.toInt()
            binding.txtSize.text = getString(R.string.size_value, v)
            if (fromUser) prefs.buttonSizeDp = v
        }
        binding.sliderOpacity.addOnChangeListener { _, value, fromUser ->
            val v = value.toInt()
            binding.txtOpacity.text = getString(R.string.opacity_value, v)
            if (fromUser) prefs.opacityPercent = v
        }

        binding.radioGroupEdge.setOnCheckedChangeListener { _, id ->
            if (loading) return@setOnCheckedChangeListener
            prefs.floatEdge = if (id == R.id.radio_edge_left) VolumePreferences.EDGE_LEFT else VolumePreferences.EDGE_RIGHT
        }
        binding.sliderAutoCollapse.addOnChangeListener { _, value, fromUser ->
            val v = value.toInt()
            binding.txtAutoCollapse.text = autoCollapseLabel(v)
            if (fromUser) prefs.autoCollapseSeconds = v
        }
        binding.switchStartExpanded.setOnCheckedChangeListener { _, checked ->
            if (loading) return@setOnCheckedChangeListener
            prefs.startExpanded = checked
        }

        binding.radioGroupCategory.setOnCheckedChangeListener { _, id ->
            if (loading) return@setOnCheckedChangeListener
            val category = when (id) {
                R.id.radio_ring -> VolumeCategory.RING
                R.id.radio_alarm -> VolumeCategory.ALARM
                else -> VolumeCategory.MEDIA
            }
            prefs.defaultCategory = category
            prefs.selectedCategory = category
            VolumeEvents.post()
        }

        binding.radioGroupTheme.setOnCheckedChangeListener { _, id ->
            if (loading) return@setOnCheckedChangeListener
            prefs.theme = when (id) {
                R.id.radio_light -> VolumePreferences.THEME_LIGHT
                R.id.radio_system -> VolumePreferences.THEME_SYSTEM
                else -> VolumePreferences.THEME_DARK
            }
            AppCompatDelegate.setDefaultNightMode(prefs.nightMode()) // recreates the activities
        }

        binding.btnReset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_msg)
                .setPositiveButton(R.string.reset_confirm) { _, _ ->
                    prefs.reset()
                    AppCompatDelegate.setDefaultNightMode(prefs.nightMode())
                    ServiceController.sync(this)
                    recreate()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }
}
