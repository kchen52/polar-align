package com.example.polaralign.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

class LocationReader(context: Context) {
  private val appContext = context.applicationContext
  private val locationManager = appContext.getSystemService(LocationManager::class.java)
  private var callback: ((Location) -> Unit)? = null

  private val listener =
    LocationListener { location -> callback?.invoke(location) }

  fun start(onLocation: (Location) -> Unit) {
    callback = onLocation
    if (
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val last =
      locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
        ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    if (last != null) onLocation(last)
    try {
      locationManager.requestLocationUpdates(
        LocationManager.FUSED_PROVIDER,
        UPDATE_INTERVAL_MS,
        0f,
        listener,
        Looper.getMainLooper(),
      )
    } catch (_: IllegalArgumentException) {
      // Location services are off, or this provider is unavailable.
    } catch (_: SecurityException) {
      // Permission was revoked between the check and the request.
    }
  }

  fun stop() {
    callback = null
    try {
      locationManager.removeUpdates(listener)
    } catch (_: SecurityException) {
      // Permission was already revoked.
    }
  }

  private companion object {
    const val UPDATE_INTERVAL_MS = 2_000L
  }
}
