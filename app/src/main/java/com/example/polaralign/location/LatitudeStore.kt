package com.example.polaralign.location

import android.content.Context

class LatitudeStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun nightMode(): Boolean = prefs.getBoolean(KEY_NIGHT, true)

  fun setNightMode(night: Boolean) {
    prefs.edit().putBoolean(KEY_NIGHT, night).apply()
  }

  fun soundEnabled(): Boolean = prefs.getBoolean(KEY_SOUND, false)

  fun setSoundEnabled(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_SOUND, enabled).apply()
  }

  fun latitudeOverride(): Double? =
    if (prefs.contains(KEY_LATITUDE)) {
      Double.fromBits(prefs.getLong(KEY_LATITUDE, 0L))
    } else {
      null
    }

  fun setLatitudeOverride(latitude: Double?) {
    val editor = prefs.edit()
    if (latitude == null) editor.remove(KEY_LATITUDE) else editor.putLong(KEY_LATITUDE, latitude.toRawBits())
    editor.apply()
  }

  private companion object {
    const val PREFS = "polar_align"
    const val KEY_NIGHT = "night_mode"
    const val KEY_SOUND = "sound_enabled"
    const val KEY_LATITUDE = "latitude_override"
  }
}
