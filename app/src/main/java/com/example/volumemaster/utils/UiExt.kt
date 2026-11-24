package com.example.volumemaster.utils

import android.animation.ValueAnimator
import android.content.Context
import android.widget.Toast
import com.example.volumemaster.R
import com.example.volumemaster.audio.VolumeCategory
import com.example.volumemaster.audio.VolumeManager
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.roundToInt

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

/** Smoothly animate a slider to [target] (programmatic changes do not trigger user callbacks). */
fun Slider.animateTo(target: Float, durationMs: Long = 160L) {
    (getTag(R.id.tag_slider_anim) as? ValueAnimator)?.cancel()
    val t = target.coerceIn(valueFrom, valueTo)
    if (abs(value - t) < 0.5f || !isAttachedToWindow) {
        value = t
        return
    }
    val animator = ValueAnimator.ofFloat(value, t)
    animator.duration = durationMs
    animator.addUpdateListener { this@animateTo.value = (it.animatedValue as Float).coerceIn(valueFrom, valueTo) }
    setTag(R.id.tag_slider_anim, animator)
    animator.start()
}

/** Only called with a non-OK result when Android really ended up at a different volume. */
fun Context.reportVolumeResult(manager: VolumeManager, category: VolumeCategory, result: VolumeManager.Result) {
    when (result) {
        VolumeManager.Result.OK -> Unit
        VolumeManager.Result.UNAVAILABLE ->
            Toast.makeText(this, R.string.msg_unavailable, Toast.LENGTH_LONG).show()
        VolumeManager.Result.RESTRICTED -> {
            val outcome = manager.lastOutcome(category)
            val shown = manager.getPercent(category)
            val requested = outcome?.requestedPercent ?: shown
            val reason = when (outcome?.reason) {
                VolumeManager.Reason.FIXED_VOLUME -> R.string.reason_fixed
                VolumeManager.Reason.DND_ACTIVE -> R.string.reason_dnd_active
                VolumeManager.Reason.DND_ACCESS -> R.string.reason_dnd_access
                VolumeManager.Reason.APP_OPS -> R.string.reason_app_ops
                else -> R.string.reason_unknown
            }
            val text = getString(R.string.msg_restricted, getString(category.labelRes), shown, requested) +
                " " + getString(reason)
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        }
    }
}
