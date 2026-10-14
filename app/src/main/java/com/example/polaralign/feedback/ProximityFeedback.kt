package com.example.polaralign.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short ticks that speed up as the mount nears the pole, plus one heavier pulse on lock-on. */
class ProximityFeedback(context: Context) {
  private val vibrator: Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      context.applicationContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

  fun tick() {
    val effect =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
      } else {
        VibrationEffect.createOneShot(TICK_FALLBACK_MS, LIGHT_AMPLITUDE)
      }
    vibrate(effect)
  }

  fun lockOn() {
    val hardware = vibrator
    val effect =
      when {
        hardware != null &&
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
          hardware.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_THUD,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
          ) ->
          VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 80)
            .compose()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
          VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        else ->
          VibrationEffect.createWaveform(
            longArrayOf(0, 40, 50, 30, 40, 80),
            intArrayOf(0, 255, 0, 120, 0, 220),
            -1,
          )
      }
    vibrate(effect)
  }

  fun release() {
    try {
      vibrator?.cancel()
    } catch (_: RuntimeException) {
      // The vibrator can disappear if the service is already gone.
    }
  }

  private fun vibrate(effect: VibrationEffect) {
    val hardware = vibrator ?: return
    if (!hardware.hasVibrator()) return
    try {
      hardware.vibrate(effect)
    } catch (_: RuntimeException) {
      // A predefined effect the device rejects should not stop alignment.
    }
  }

  companion object {
    const val SILENT_BEYOND_DEGREES = 10.0
    const val SLOW_ERROR_DEGREES = 8.0
    const val FAST_ERROR_DEGREES = 1.0
    const val SLOW_INTERVAL_MS = 1_200L
    const val FAST_INTERVAL_MS = 120L

    private const val TICK_FALLBACK_MS = 20L
    private const val LIGHT_AMPLITUDE = 50

    /** Milliseconds between ticks, or null when the error is too large to nag. */
    fun intervalMillis(totalErrorDegrees: Double): Long? {
      if (!totalErrorDegrees.isFinite() || totalErrorDegrees > SILENT_BEYOND_DEGREES) return null
      val span = SLOW_ERROR_DEGREES - FAST_ERROR_DEGREES
      val t = ((totalErrorDegrees - FAST_ERROR_DEGREES) / span).coerceIn(0.0, 1.0)
      return (FAST_INTERVAL_MS + (SLOW_INTERVAL_MS - FAST_INTERVAL_MS) * t).toLong()
    }
  }
}
