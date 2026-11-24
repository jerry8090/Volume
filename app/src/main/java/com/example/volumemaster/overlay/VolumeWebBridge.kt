package com.example.volumemaster.overlay

import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import com.example.volumemaster.audio.VolumeCategory
import com.example.volumemaster.audio.VolumeManager
import com.example.volumemaster.utils.VolumePreferences
import com.example.volumemaster.utils.reportVolumeResult
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * The ONLY object exposed to the overlay's JavaScript (as `window.VolumeBridge`).
 *
 * - Every real volume operation is executed here in Kotlin through the shared [VolumeManager].
 * - Category names and values coming from JavaScript are validated; unknown input is rejected.
 * - Nothing else (no Context, no files, no intents) is reachable from the page.
 * - Methods run on a WebView binder thread, so anything touching views or Toasts is posted to main.
 */
class VolumeWebBridge(
    context: Context,
    private val volumeManager: VolumeManager,
    private val prefs: VolumePreferences,
    private val host: Host
) {
    /** Window-level actions the page may request. Implemented on the main thread by the overlay. */
    interface Host {
        fun onCollapseRequested()
        fun onCloseRequested()
        fun onOpenAppRequested()
        fun onInteraction(active: Boolean)

        /** Screen-space (px, panel-relative) areas where a horizontal swipe must NOT collapse the panel. */
        fun onNoSwipeZones(zones: List<RectF>)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // ---- reads ---------------------------------------------------------------------------

    @JavascriptInterface
    fun getState(): String = snapshot()

    @JavascriptInterface
    fun getVolume(category: String?): Int = parse(category)?.let { volumeManager.getVolume(it) } ?: -1

    @JavascriptInterface
    fun getMaxVolume(category: String?): Int = parse(category)?.let { volumeManager.getMax(it) } ?: -1

    // ---- volume actions (all return the ACTUAL resulting state as JSON) ----------------------

    /** [percent] is 0..100; Kotlin converts it to a real Android step. */
    @JavascriptInterface
    fun setVolume(category: String?, percent: Double): String {
        val c = parse(category) ?: return error("invalid category")
        if (percent.isNaN() || percent.isInfinite()) return error("invalid value")
        val result = volumeManager.setPercent(c, percent.roundToInt().coerceIn(0, 100))
        return categoryJson(c).put("ok", result == VolumeManager.Result.OK).toString()
    }

    /** Called when a slider drag ends: reports a genuine failure once (not on every move). */
    @JavascriptInterface
    fun commitVolume(category: String?) {
        val c = parse(category) ?: return
        val outcome = volumeManager.lastOutcome(c) ?: return
        if (outcome.result != VolumeManager.Result.OK) {
            main.post { appContext.reportVolumeResult(volumeManager, c, outcome.result) }
        }
    }

    /** One real Android volume step up. */
    @JavascriptInterface
    fun increaseVolume(category: String?): String = perform(category) { volumeManager.step(it, +1) }

    /** One real Android volume step down. */
    @JavascriptInterface
    fun decreaseVolume(category: String?): String = perform(category) { volumeManager.step(it, -1) }

    /** Sets the stream to zero and remembers the previous level. */
    @JavascriptInterface
    fun muteVolume(category: String?): String = perform(category) { volumeManager.setMute(it) }

    /** Restores the level remembered by [muteVolume] (about 50% if none is known). */
    @JavascriptInterface
    fun restoreVolume(category: String?): String = perform(category) { volumeManager.setNormal(it) }

    // ---- selection / window ------------------------------------------------------------------

    @JavascriptInterface
    fun setSelectedCategory(category: String?) {
        val c = parse(category) ?: return
        prefs.selectedCategory = c // shared with the notification controller
    }

    @JavascriptInterface
    fun setInteracting(active: Boolean) {
        main.post { host.onInteraction(active) }
    }

    /**
     * The page reports where its slider and +/- buttons are (CSS px: [[left, top, right, bottom], ...]),
     * so a sideways finger drift while dragging the slider never turns into a collapse swipe.
     */
    @JavascriptInterface
    fun setNoSwipeZones(json: String?) {
        val density = appContext.resources.displayMetrics.density
        val zones = ArrayList<RectF>()
        try {
            val arr = JSONArray(json ?: return)
            for (i in 0 until minOf(arr.length(), MAX_ZONES)) {
                val r = arr.getJSONArray(i)
                if (r.length() != 4) continue
                val l = r.getDouble(0).toFloat()
                val t = r.getDouble(1).toFloat()
                val rr = r.getDouble(2).toFloat()
                val b = r.getDouble(3).toFloat()
                if (listOf(l, t, rr, b).any { it.isNaN() || it.isInfinite() }) continue
                zones.add(RectF(l * density, t * density, rr * density, b * density))
            }
        } catch (_: Exception) {
            return
        }
        main.post { host.onNoSwipeZones(zones) }
    }

    @JavascriptInterface
    fun collapseController() {
        main.post { host.onCollapseRequested() }
    }

    @JavascriptInterface
    fun openApp() {
        main.post { host.onOpenAppRequested() }
    }

    @JavascriptInterface
    fun closeController() {
        main.post { host.onCloseRequested() }
    }

    // ---- Kotlin-side helpers (not exposed to JavaScript) --------------------------------------

    /** Full state of all three channels; also pushed to the page when volumes change externally. */
    fun snapshot(): String {
        val categories = JSONObject()
        VolumeCategory.values().forEach { categories.put(it.key, categoryJson(it)) }
        return JSONObject()
            .put("selected", prefs.selectedCategory.key)
            .put("categories", categories)
            .toString()
    }

    private fun perform(category: String?, action: (VolumeCategory) -> VolumeManager.Result): String {
        val c = parse(category) ?: return error("invalid category")
        val result = action(c)
        if (result != VolumeManager.Result.OK) {
            main.post { appContext.reportVolumeResult(volumeManager, c, result) }
        }
        return categoryJson(c).put("ok", result == VolumeManager.Result.OK).toString()
    }

    private fun categoryJson(c: VolumeCategory): JSONObject = JSONObject()
        .put("category", c.key)
        .put("volume", volumeManager.getVolume(c))
        .put("max", volumeManager.getMax(c))
        .put("percent", volumeManager.getPercent(c))
        .put("muted", volumeManager.isMuted(c))

    private companion object {
        const val MAX_ZONES = 8
    }

    private fun error(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    /** Strict whitelist: only the three known keys are accepted. */
    private fun parse(category: String?): VolumeCategory? =
        VolumeCategory.values().firstOrNull { it.key == category }
}
