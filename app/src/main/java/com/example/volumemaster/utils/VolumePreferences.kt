package com.example.volumemaster.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.example.volumemaster.audio.VolumeCategory

class VolumePreferences(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var floatingEnabled: Boolean
        get() = sp.getBoolean(KEY_FLOATING, false)
        set(value) = sp.edit().putBoolean(KEY_FLOATING, value).apply()

    var notificationEnabled: Boolean
        get() = sp.getBoolean(KEY_NOTIFICATION, true)
        set(value) = sp.edit().putBoolean(KEY_NOTIFICATION, value).apply()

    var autoStart: Boolean
        get() = sp.getBoolean(KEY_AUTOSTART, false)
        set(value) = sp.edit().putBoolean(KEY_AUTOSTART, value).apply()

    var buttonSizeDp: Int
        get() = sp.getInt(KEY_SIZE, 64)
        set(value) = sp.edit().putInt(KEY_SIZE, value).apply()

    var opacityPercent: Int
        get() = sp.getInt(KEY_OPACITY, 90)
        set(value) = sp.edit().putInt(KEY_OPACITY, value).apply()

    var defaultCategory: VolumeCategory
        get() = VolumeCategory.fromKey(sp.getString(KEY_DEFAULT_CAT, null))
        set(value) = sp.edit().putString(KEY_DEFAULT_CAT, value.key).apply()

    /** Category used by quick buttons, presets and notification actions. */
    var selectedCategory: VolumeCategory
        get() = VolumeCategory.fromKey(sp.getString(KEY_SELECTED, null) ?: sp.getString(KEY_DEFAULT_CAT, null))
        set(value) = sp.edit().putString(KEY_SELECTED, value.key).apply()

    var theme: Int
        get() = sp.getInt(KEY_THEME, THEME_DARK)
        set(value) = sp.edit().putInt(KEY_THEME, value).apply()

    var floatX: Int
        get() = sp.getInt(KEY_POS_X, -1)
        set(value) = sp.edit().putInt(KEY_POS_X, value).apply()

    var floatY: Int
        get() = sp.getInt(KEY_POS_Y, -1)
        set(value) = sp.edit().putInt(KEY_POS_Y, value).apply()

    /** Screen edge the handle is docked to: [EDGE_LEFT] or [EDGE_RIGHT]. */
    var floatEdge: Int
        get() = if (sp.getInt(KEY_EDGE, EDGE_RIGHT) == EDGE_LEFT) EDGE_LEFT else EDGE_RIGHT
        set(value) = sp.edit().putInt(KEY_EDGE, value).apply()

    /** Vertical position as a 0..1 fraction of the usable height (survives rotation). -1 = default. */
    var floatYFraction: Float
        get() = sp.getFloat(KEY_Y_FRACTION, -1f)
        set(value) = sp.edit().putFloat(KEY_Y_FRACTION, value).apply()

    /** Seconds of inactivity before the panel slides back to the edge. 0 = never. */
    var autoCollapseSeconds: Int
        get() = sp.getInt(KEY_AUTO_COLLAPSE, 4)
        set(value) = sp.edit().putInt(KEY_AUTO_COLLAPSE, value).apply()

    /**
     * How far (as % of the panel's travel) the handle must be dragged inward before releasing
     * expands the panel. A fast fling always wins over this. No Settings UI yet; default 35.
     */
    var swipeThresholdPercent: Int
        get() = sp.getInt(KEY_SWIPE_THRESHOLD, 35).coerceIn(10, 80)
        set(value) = sp.edit().putInt(KEY_SWIPE_THRESHOLD, value.coerceIn(10, 80)).apply()

    var startExpanded: Boolean
        get() = sp.getBoolean(KEY_START_EXPANDED, false)
        set(value) = sp.edit().putBoolean(KEY_START_EXPANDED, value).apply()

    var notificationPermissionAsked: Boolean
        get() = sp.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

    fun getPreMute(category: VolumeCategory): Int = sp.getInt("premute_${category.key}", 0)
    fun setPreMute(category: VolumeCategory, value: Int) {
        sp.edit().putInt("premute_${category.key}", value).apply()
    }

    fun nightMode(): Int = when (theme) {
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_YES
    }

    fun reset() { sp.edit().clear().apply() }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_DARK = 1
        const val THEME_LIGHT = 2

        const val EDGE_LEFT = 0
        const val EDGE_RIGHT = 1

        const val KEY_POS_X = "float_x"
        const val KEY_POS_Y = "float_y"
        const val KEY_Y_FRACTION = "float_y_fraction"
        const val KEY_EDGE = "float_edge"
        const val KEY_AUTO_COLLAPSE = "float_auto_collapse_s"
        const val KEY_START_EXPANDED = "float_start_expanded"
        const val KEY_SWIPE_THRESHOLD = "float_swipe_threshold_pct"
        const val KEY_FLOATING = "floating_enabled"
        const val KEY_NOTIFICATION = "notification_enabled"
        const val KEY_SIZE = "button_size_dp"
        const val KEY_OPACITY = "button_opacity"
        const val KEY_SELECTED = "selected_category"

        private const val FILE = "volume_master_prefs"
        private const val KEY_AUTOSTART = "auto_start"
        private const val KEY_DEFAULT_CAT = "default_category"
        private const val KEY_THEME = "theme"
        private const val KEY_NOTIF_ASKED = "notif_permission_asked"
    }
}
