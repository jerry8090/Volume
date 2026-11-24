package com.example.volumemaster.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.max

/** Real screen size and system-bar / cutout insets, for keeping the handle on screen. */
internal class ScreenGeometry(
    private val context: Context,
    private val windowManager: WindowManager
) {

    @Suppress("DEPRECATION")
    fun screenSize(): Point {
        val p = Point()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = windowManager.currentWindowMetrics.bounds
                p.set(b.width(), b.height())
            } else {
                windowManager.defaultDisplay.getRealSize(p)
            }
        } catch (_: Exception) { }
        if (p.x <= 0 || p.y <= 0) {
            val dm = context.resources.displayMetrics
            p.set(dm.widthPixels, dm.heightPixels)
        }
        return p
    }

    /** Insets (status bar, navigation/gesture bar, display cutout) in pixels. */
    fun insets(anchor: View?): Rect {
        val r = Rect()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                r.set(i.left, i.top, i.right, i.bottom)
            } else {
                r.top = dimen("status_bar_height")
                val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                r.bottom = dimen(if (landscape) "navigation_bar_height_landscape" else "navigation_bar_height")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val cutout = anchor?.rootWindowInsets?.displayCutout
                    if (cutout != null) {
                        r.top = max(r.top, cutout.safeInsetTop)
                        r.bottom = max(r.bottom, cutout.safeInsetBottom)
                        r.left = cutout.safeInsetLeft
                        r.right = cutout.safeInsetRight
                    }
                }
            }
        } catch (_: Exception) {
            r.top = dimen("status_bar_height")
        }
        return r
    }

    private fun dimen(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
