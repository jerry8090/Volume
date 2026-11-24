package com.example.volumemaster

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.volumemaster.audio.VolumeCategory
import com.example.volumemaster.audio.VolumeEvents
import com.example.volumemaster.audio.VolumeManager
import com.example.volumemaster.audio.VolumeObserver
import com.example.volumemaster.databinding.ActivityMainBinding
import com.example.volumemaster.databinding.ItemVolumeCardBinding
import com.example.volumemaster.service.ServiceController
import com.example.volumemaster.utils.PermissionManager
import com.example.volumemaster.utils.VolumePreferences
import com.example.volumemaster.utils.animateTo
import com.example.volumemaster.utils.dp
import com.example.volumemaster.utils.reportVolumeResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: VolumePreferences
    private lateinit var volumeManager: VolumeManager
    private lateinit var observer: VolumeObserver

    private val cards = mutableListOf<Card>()
    private var updatingUi = false
    private val eventListener: () -> Unit = { refreshVolumes() }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, R.string.notif_denied, Toast.LENGTH_LONG).show()
            updatePermissionUi()
            ServiceController.sync(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = VolumePreferences(this)
        volumeManager = VolumeManager.get(this)
        observer = VolumeObserver(this) { refreshVolumes() }

        buildCards()
        setupControllers()
        setupSelectorAndPresets()
        binding.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        if (savedInstanceState == null) maybeAskNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        observer.start()
        VolumeEvents.add(eventListener)
        refreshVolumes()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUi()
        ServiceController.sync(this) // also (re)starts after the user grants a permission in Settings
    }

    override fun onStop() {
        observer.stop()
        VolumeEvents.remove(eventListener)
        super.onStop()
    }

    // ---- volume cards -------------------------------------------------------------------

    private fun buildCards() {
        VolumeCategory.values().forEach { category ->
            val cardBinding = ItemVolumeCardBinding.inflate(layoutInflater, binding.volumeContainer, false)
            binding.volumeContainer.addView(cardBinding.root)
            cards += Card(category, cardBinding)
        }
    }

    private inner class Card(val category: VolumeCategory, val b: ItemVolumeCardBinding) {
        private var dragging = false
        private var lastResult = VolumeManager.Result.OK

        init {
            b.title.setText(category.labelRes)
            b.subtitle.setText(category.descRes)
            b.icon.setImageResource(category.iconRes)

            b.slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    lastResult = volumeManager.setPercent(category, value.roundToInt())
                    b.percent.text = "${volumeManager.getPercent(category)}%" // actual Android value
                }
            }
            b.slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {
                    dragging = true
                    select(category)
                }

                override fun onStopTrackingTouch(slider: Slider) {
                    dragging = false
                    val result = lastResult
                    lastResult = VolumeManager.Result.OK
                    if (result != VolumeManager.Result.OK) reportVolumeResult(volumeManager, category, result)
                    refresh()
                }
            })
            b.btnDown.setOnClickListener { act(-1) }
            b.btnUp.setOnClickListener { act(+1) }
            b.btnMute.setOnClickListener {
                select(category)
                reportVolumeResult(volumeManager, category, volumeManager.toggleMute(category))
            }
            b.root.setOnClickListener { select(category) }
        }

        private fun act(delta: Int) {
            select(category)
            reportVolumeResult(volumeManager, category, volumeManager.step(category, delta))
        }

        fun refresh() {
            val percent = volumeManager.getPercent(category)
            if (!dragging) b.slider.animateTo(percent.toFloat())
            b.percent.text = "$percent%"
            b.btnMute.setIconResource(
                if (volumeManager.isMuted(category)) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            )
            val selected = prefs.selectedCategory == category
            b.root.strokeWidth = if (selected) dp(2) else 0
            b.root.strokeColor = ContextCompat.getColor(this@MainActivity, R.color.vm_accent)
        }
    }

    private fun select(category: VolumeCategory) {
        if (prefs.selectedCategory != category) {
            prefs.selectedCategory = category
            VolumeEvents.post()
        }
    }

    private fun refreshVolumes() {
        cards.forEach { it.refresh() }
        syncSelectedChip()
    }

    // ---- selector + presets -------------------------------------------------------------

    private fun chipIdFor(category: VolumeCategory): Int = when (category) {
        VolumeCategory.MEDIA -> R.id.chip_media
        VolumeCategory.RING -> R.id.chip_ring
        VolumeCategory.ALARM -> R.id.chip_alarm
    }

    private fun categoryForChip(id: Int): VolumeCategory = when (id) {
        R.id.chip_ring -> VolumeCategory.RING
        R.id.chip_alarm -> VolumeCategory.ALARM
        else -> VolumeCategory.MEDIA
    }

    private fun syncSelectedChip() {
        val id = chipIdFor(prefs.selectedCategory)
        if (binding.chipGroupCategory.checkedChipId != id) binding.chipGroupCategory.check(id)
    }

    private fun setupSelectorAndPresets() {
        binding.chipGroupCategory.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            select(categoryForChip(id))
        }
        listOf(
            binding.chipSilent to 0,
            binding.chipLow to 25,
            binding.chipMedium to 50,
            binding.chipHigh to 75,
            binding.chipMax to 100
        ).forEach { (chip, percent) ->
            chip.setOnClickListener {
                val category = prefs.selectedCategory
                reportVolumeResult(volumeManager, category, volumeManager.setPercent(category, percent))
            }
        }
    }

    // ---- controllers + permissions -------------------------------------------------------

    private fun setupControllers() {
        binding.switchFloating.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.floatingEnabled = checked
            if (checked && !PermissionManager.canDrawOverlays(this)) showOverlayRationale()
            ServiceController.sync(this)
            updatePermissionUi()
        }
        binding.switchNotification.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            prefs.notificationEnabled = checked
            if (checked) maybeAskNotificationPermission()
            ServiceController.sync(this)
            updatePermissionUi()
        }
        binding.btnOverlay.setOnClickListener { showOverlayRationale() }
        binding.btnNotif.setOnClickListener { requestNotificationAccess() }
        binding.btnDnd.setOnClickListener { openSafely(PermissionManager.dndSettingsIntent()) }
    }

    private fun updatePermissionUi() {
        val overlayOk = PermissionManager.canDrawOverlays(this)
        val notifOk = PermissionManager.areNotificationsEnabled(this)

        updatingUi = true
        binding.switchFloating.isChecked = prefs.floatingEnabled
        binding.switchNotification.isChecked = prefs.notificationEnabled
        updatingUi = false

        binding.txtFloatingStatus.setText(
            when {
                !prefs.floatingEnabled -> R.string.floating_off
                !overlayOk -> R.string.floating_need_perm
                else -> R.string.floating_on
            }
        )
        binding.btnOverlay.isVisible = !overlayOk

        binding.txtNotificationStatus.setText(
            when {
                !prefs.notificationEnabled -> R.string.notif_off
                !notifOk -> R.string.notif_blocked
                else -> R.string.notif_on
            }
        )
        binding.btnNotif.isVisible = !notifOk

        val dndOk = PermissionManager.hasDndAccess(this)
        binding.txtDndHint.isVisible = !dndOk
        binding.btnDnd.isVisible = !dndOk
    }

    private fun showOverlayRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_overlay_title)
            .setMessage(R.string.perm_overlay_msg)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    startActivity(PermissionManager.overlaySettingsIntent(this))
                } catch (e: ActivityNotFoundException) {
                    openSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            }
            .setNegativeButton(R.string.not_now, null)
            .show()
    }

    private fun maybeAskNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            !PermissionManager.hasNotificationPermission(this) &&
            !prefs.notificationPermissionAsked
        ) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_notif_title)
                .setMessage(R.string.perm_notif_msg)
                .setPositiveButton(R.string.continue_label) { _, _ -> requestNotificationAccess() }
                .setNegativeButton(R.string.not_now, null)
                .show()
        }
    }

    private fun requestNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 33 &&
            !PermissionManager.hasNotificationPermission(this) &&
            !prefs.notificationPermissionAsked
        ) {
            prefs.notificationPermissionAsked = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openSafely(PermissionManager.appNotificationSettingsIntent(this))
        }
    }

    private fun openSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}
