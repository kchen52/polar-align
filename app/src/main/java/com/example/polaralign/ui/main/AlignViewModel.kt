package com.example.polaralign.ui.main

import android.app.Application
import android.hardware.GeomagneticField
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import com.example.polaralign.alignment.CompassAccuracy
import com.example.polaralign.alignment.PolarAlignment
import com.example.polaralign.location.LatitudeStore
import com.example.polaralign.location.LocationReader
import com.example.polaralign.sensor.OrientationMonitor
import com.example.polaralign.sensor.OrientationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AlignUiState(
  val nightMode: Boolean = true,
  val sensorsAvailable: Boolean = true,
  val permissionGranted: Boolean = false,
  val needsLatitude: Boolean = true,
  val southernHemisphere: Boolean = false,
  val latitudeLabel: String? = null,
  val latitudeIsManual: Boolean = false,
  val declinationKnown: Boolean = false,
  val showChart: Boolean = false,
  val aligned: Boolean = false,
  val azimuthError: Double? = null,
  val altitudeError: Double? = null,
  val azimuthMessage: String? = null,
  val altitudeMessage: String? = null,
  val statusMessage: String? = null,
  val compassMessage: String? = null,
)

class AlignViewModel(application: Application) : AndroidViewModel(application) {
  private val orientation = OrientationMonitor(application)
  private val locations = LocationReader(application)
  private val store = LatitudeStore(application)

  private var listening = false
  private var gpsLocation: Location? = null
  private var manualLatitude: Double? = store.latitudeOverride()
  private var sample: OrientationSample? = null
  private var sensorsAvailable = true

  private val _uiState =
    MutableStateFlow(
      AlignUiState(nightMode = store.nightMode(), latitudeIsManual = manualLatitude != null)
    )
  val uiState: StateFlow<AlignUiState> = _uiState.asStateFlow()

  init {
    publish()
  }

  fun setDisplayRotation(rotation: Int) {
    orientation.setDisplayRotation(rotation)
  }

  fun onPermissionResult(granted: Boolean) {
    _uiState.update { it.copy(permissionGranted = granted) }
    if (granted && listening) locations.start(::onLocation)
    publish()
  }

  fun setNightMode(night: Boolean) {
    store.setNightMode(night)
    _uiState.update { it.copy(nightMode = night) }
  }

  fun setManualLatitude(latitude: Double) {
    manualLatitude = latitude
    store.setLatitudeOverride(latitude)
    publish()
  }

  fun useGpsLatitude() {
    manualLatitude = null
    store.setLatitudeOverride(null)
    if (listening && _uiState.value.permissionGranted) locations.start(::onLocation)
    publish()
  }

  fun start() {
    if (listening) return
    listening = true
    orientation.start { reading ->
      sensorsAvailable = reading.available
      sample = if (reading.available) reading else null
      publish()
    }
    if (_uiState.value.permissionGranted) locations.start(::onLocation)
  }

  fun stop() {
    if (!listening) return
    listening = false
    orientation.stop()
    locations.stop()
  }

  override fun onCleared() {
    stop()
  }

  private fun onLocation(location: Location) {
    gpsLocation = location
    publish()
  }

  private fun publish() {
    val gps = gpsLocation
    val manual = manualLatitude
    val latitude = manual ?: gps?.latitude
    val southern = latitude != null && latitude < 0.0
    val declinationKnown = gps != null
    val declination =
      if (gps != null) {
        GeomagneticField(
            gps.latitude.toFloat(),
            gps.longitude.toFloat(),
            gps.altitude.toFloat(),
            System.currentTimeMillis(),
          )
          .declination
          .toDouble()
      } else {
        0.0
      }
    val reading = sample
    val accuracy = reading?.accuracy ?: CompassAccuracy.Unknown
    val guidance =
      if (latitude != null && !southern && reading != null && sensorsAvailable) {
        PolarAlignment.guidance(
          magneticAzimuthDegrees = reading.magneticAzimuthDegrees,
          altitudeDegrees = reading.altitudeDegrees,
          sidewaysTiltDegrees = reading.sidewaysTiltDegrees,
          screenFacesUp = reading.screenFacesUp,
          latitudeDegrees = latitude,
          declinationDegrees = declination,
          compassTrustworthy = accuracy == CompassAccuracy.Medium || accuracy == CompassAccuracy.High,
        )
      } else {
        null
      }
    _uiState.update { current ->
      current.copy(
        sensorsAvailable = sensorsAvailable,
        needsLatitude = latitude == null,
        southernHemisphere = southern,
        latitudeLabel = latitude?.let(PolarAlignment::formatLatitude),
        latitudeIsManual = manual != null,
        declinationKnown = declinationKnown,
        showChart = guidance != null,
        aligned = guidance?.aligned == true,
        azimuthError = guidance?.azimuthErrorDegrees,
        altitudeError = guidance?.altitudeErrorDegrees,
        azimuthMessage = guidance?.azimuthMessage,
        altitudeMessage = guidance?.altitudeMessage,
        statusMessage =
          when {
            southern -> "This app finds the north celestial pole. A southern latitude will not line up a mount here."
            guidance != null -> guidance.statusMessage
            else -> null
          },
        compassMessage =
          if (sensorsAvailable && guidance != null) PolarAlignment.compassMessage(accuracy) else null,
      )
    }
  }
}
