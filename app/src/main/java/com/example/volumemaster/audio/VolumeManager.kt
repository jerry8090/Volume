package com.example.volumemaster.audio

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Process
import android.util.Log
import com.example.volumemaster.utils.VolumePreferences
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Single, shared entry point for reading/writing system volume through AudioManager.
 * The dashboard, the floating edge controller and the notification buttons all go through
 * [get], so there is exactly one AudioManager reference and one set of volume rules.
 *
 * Logcat: `adb logcat -s VolumeManager`
 */
class VolumeManager private constructor(context: Context) {

    enum class Result { OK, RESTRICTED, UNAVAILABLE }

    /** Why Android did not apply a requested volume (best effort, see [diagnose]). */
    enum class Reason { NONE, FIXED_VOLUME, DND_ACTIVE, DND_ACCESS, APP_OPS, UNKNOWN }

    /** What happened on the last write for one category. */
    data class Outcome(
        val category: VolumeCategory,
        val requestedIndex: Int,
        val appliedIndex: Int,
        val maxIndex: Int,
        val result: Result,
        val reason: Reason
    ) {
        val requestedPercent: Int get() = VolumeManager.toPercent(requestedIndex, maxIndex)
        val appliedPercent: Int get() = VolumeManager.toPercent(appliedIndex, maxIndex)
    }

    private val appContext = context.applicationContext
    private val prefs = VolumePreferences(appContext)
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val outcomes = ConcurrentHashMap<VolumeCategory, Outcome>()

    fun lastOutcome(category: VolumeCategory): Outcome? = outcomes[category]

    // ---- reading (always straight from AudioManager, never cached) ------------------------

    fun getMax(category: VolumeCategory): Int =
        try { audioManager?.getStreamMaxVolume(category.stream) ?: 0 } catch (e: Exception) { 0 }

    /** Lowest index Android accepts for this stream (alarm is usually 1, not 0). */
    fun getMin(category: VolumeCategory): Int =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                audioManager?.getStreamMinVolume(category.stream) ?: 0
            } else if (category == VolumeCategory.ALARM) 1 else 0
        } catch (e: Exception) { 0 }

    fun getVolume(category: VolumeCategory): Int =
        try { audioManager?.getStreamVolume(category.stream) ?: 0 } catch (e: Exception) { 0 }

    fun getPercent(category: VolumeCategory): Int = toPercent(getVolume(category), getMax(category))

    fun isMuted(category: VolumeCategory): Boolean = getVolume(category) == 0

    // ---- writing ------------------------------------------------------------------------

    /**
     * Applies [index] to the category's own stream and verifies the result against the same
     * stream. Only returns RESTRICTED when Android genuinely ends up at a different value.
     */
    fun setVolume(category: VolumeCategory, index: Int): Result {
        val am = audioManager
            ?: return record(category, index, 0, 0, Result.UNAVAILABLE, Reason.UNKNOWN)
        val max = getMax(category)
        if (max <= 0) return record(category, index, 0, 0, Result.UNAVAILABLE, Reason.UNKNOWN)

        val target = index.coerceIn(0, max)
        val before = getVolume(category)
        if (before == target) return record(category, target, before, max, Result.OK, Reason.NONE)

        var via = "setStreamVolume"
        var security: SecurityException? = null
        try {
            am.setStreamVolume(category.stream, target, 0)
        } catch (e: SecurityException) {
            security = e
            Log.w(TAG, "setStreamVolume threw SecurityException for ${describe(category)}: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "setStreamVolume failed for ${describe(category)}", e)
            return record(category, target, before, max, Result.UNAVAILABLE, Reason.UNKNOWN)
        }

        var actual = getVolume(category)
        if (security == null && !isAcceptable(category, target, actual)) {
            Log.w(TAG, "setStreamVolume was ignored (${describe(category)} requested=$target actual=$actual); trying adjustStreamVolume")
            actual = nudge(category, target, actual)
            via = "adjustStreamVolume fallback"
        }

        val ok = isAcceptable(category, target, actual)
        Log.i(
            TAG,
            "stream=${describe(category)} requested=$target max=$max before=$before actual=$actual via=$via ok=$ok"
        )
        VolumeEvents.post()
        if (ok) return record(category, target, actual, max, Result.OK, Reason.NONE)

        val reason = if (security != null) classifySecurity(security) else diagnose(category)
        return record(category, target, actual, max, Result.RESTRICTED, reason)
    }

    fun setPercent(category: VolumeCategory, percent: Int): Result =
        setVolume(category, percentToIndex(percent, getMax(category)))

    fun step(category: VolumeCategory, delta: Int): Result =
        setVolume(category, getVolume(category) + delta)

    /** Mute button: set the stream to zero (remembering the old level for "Normal"). */
    fun setMute(category: VolumeCategory): Result {
        val current = getVolume(category)
        if (current > 0) prefs.setPreMute(category, current)
        return setVolume(category, 0)
    }

    /** Maximum button. */
    fun setMaximum(category: VolumeCategory): Result {
        val current = getVolume(category)
        if (current in 1 until getMax(category)) prefs.setPreMute(category, current)
        return setVolume(category, getMax(category))
    }

    /** Normal button: previous non-zero level if known, otherwise about half. */
    fun setNormal(category: VolumeCategory): Result {
        val max = getMax(category)
        val stored = prefs.getPreMute(category)
        val target = if (stored in 1..max) stored else (max / 2f).roundToInt().coerceAtLeast(1)
        return setVolume(category, target)
    }

    /** Notification / dashboard mute toggle (kept from the original behaviour). */
    fun toggleMute(category: VolumeCategory): Result {
        return if (getVolume(category) > 0) setMute(category) else setNormal(category)
    }

    /** True if Android ended up noticeably away from the requested percent. */
    fun deviatesFrom(category: VolumeCategory, requestedPercent: Int): Boolean {
        val max = getMax(category)
        if (max <= 0) return false
        return abs(requestedPercent - getPercent(category)) > 100f / max
    }

    // ---- internals ----------------------------------------------------------------------

    private fun record(
        category: VolumeCategory, requested: Int, applied: Int, max: Int,
        result: Result, reason: Reason
    ): Result {
        outcomes[category] = Outcome(category, requested, applied, max, result, reason)
        return result
    }

    /** Alarm refuses 0 on most devices, so the lowest legal value counts as success. */
    private fun isAcceptable(category: VolumeCategory, target: Int, actual: Int): Boolean =
        actual == target || actual == max(target, getMin(category))

    /** Fallback: step towards the target like the hardware keys would. */
    private fun nudge(category: VolumeCategory, target: Int, start: Int): Int {
        val am = audioManager ?: return start
        var current = start
        var guard = getMax(category) + 2
        while (current != target && guard-- > 0) {
            val direction = if (target > current) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            try {
                am.adjustStreamVolume(category.stream, direction, 0)
            } catch (e: Exception) {
                Log.w(TAG, "adjustStreamVolume failed for ${describe(category)}: ${e.message}")
                break
            }
            val now = getVolume(category)
            if (now == current) break
            current = now
        }
        return current
    }

    private fun classifySecurity(e: SecurityException): Reason {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val access = try { nm?.isNotificationPolicyAccessGranted ?: false } catch (_: Exception) { false }
        Log.w(TAG, "SecurityException while changing volume; dndAccessGranted=$access message=${e.message}")
        return if (!access) Reason.DND_ACCESS else Reason.UNKNOWN
    }

    /** Collects everything that can legitimately stop a volume change and logs it. */
    private fun diagnose(category: VolumeCategory): Reason {
        val am = audioManager ?: return Reason.UNKNOWN
        val fixed = try { am.isVolumeFixed } catch (_: Exception) { false }
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val filter = try { nm?.currentInterruptionFilter ?: -1 } catch (_: Exception) { -1 }
        val dndAccess = try { nm?.isNotificationPolicyAccessGranted ?: false } catch (_: Exception) { false }
        // Total silence / Alarms only mute streams and Android then refuses volume changes.
        val dndBlocking = filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        val opMode = appOpMode(category)
        val ringer = try { am.ringerMode } catch (_: Exception) { -1 }
        val streamMute = try { am.isStreamMute(category.stream) } catch (_: Exception) { false }

        Log.w(
            TAG,
            "diagnosis stream=${describe(category)} fixedVolume=$fixed dndFilter=$filter dndBlocking=$dndBlocking " +
                "dndAccess=$dndAccess ringerMode=$ringer streamMuted=$streamMute appOpMode=$opMode outputs=${outputDevices()}"
        )
        return when {
            fixed -> Reason.FIXED_VOLUME
            opMode != null && opMode != AppOpsManager.MODE_ALLOWED -> Reason.APP_OPS
            dndBlocking -> Reason.DND_ACTIVE
            else -> Reason.UNKNOWN
        }
    }

    @Suppress("DEPRECATION")
    private fun appOpMode(category: VolumeCategory): Int? = try {
        val ops = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val op = when (category) {
            VolumeCategory.MEDIA -> "android:audio_media_volume"
            VolumeCategory.RING -> "android:audio_ring_volume"
            VolumeCategory.ALARM -> "android:audio_alarm_volume"
        }
        ops.checkOpNoThrow(op, Process.myUid(), appContext.packageName)
    } catch (_: Exception) {
        null
    }

    private fun outputDevices(): String = try {
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.joinToString(",") { it.type.toString() } ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun describe(category: VolumeCategory): String = "${category.key}(stream ${category.stream})"

    companion object {
        private const val TAG = "VolumeManager"

        @Volatile
        private var instance: VolumeManager? = null

        fun get(context: Context): VolumeManager =
            instance ?: synchronized(this) {
                instance ?: VolumeManager(context.applicationContext).also { instance = it }
            }

        fun toPercent(index: Int, max: Int): Int {
            if (max <= 0) return 0
            return (index * 100f / max).roundToInt().coerceIn(0, 100)
        }

        /** 50% of 15 -> 8; any non-zero percent maps to at least step 1 so it never mutes by rounding. */
        fun percentToIndex(percent: Int, max: Int): Int {
            if (max <= 0 || percent <= 0) return 0
            return (percent.coerceAtMost(100) * max / 100f).roundToInt().coerceIn(1, max)
        }
    }
}
