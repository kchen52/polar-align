package com.example.polaralign.ui.main

import android.app.Application
import android.hardware.GeomagneticField
import android.location.Location
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.polaralign.alignment.CompassAccuracy
import com.example.polaralign.alignment.PolarAlignment
import com.example.polaralign.feedback.AlignmentTone
import com.example.polaralign.feedback.ProximityFeedback
import com.example.polaralign.location.LatitudeStore
import com.example.polaralign.location.LocationReader
import com.example.polaralign.sensor.OrientationMonitor
import com.example.polaralign.sensor.OrientationSample
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
  val soundEnabled: Boolean = false,
)

class AlignViewModel(application: Application) : AndroidViewModel(application) {
  private val orientation = OrientationMonitor(application)
  private val locations = LocationReader(application)
  private val store = LatitudeStore(application)
  private val proximity = ProximityFeedback(application)
  private val tone = AlignmentTone()

  private var listening = false
  private var gpsLocation: Location? = null
  private var manualLatitude: Double? = store.latitudeOverride()
  private var sample: OrientationSample? = null
  private var sensorsAvailable = true
  private var celebrationIndex = 0
  private var wasAligned = false
  private var hadLock = false
  private var unalignedSinceMs: Long? = null
  private var feedbackJob: Job? = null

  @Volatile private var feedbackSession = 0

  private val _uiState =
    MutableStateFlow(
      AlignUiState(
        nightMode = store.nightMode(),
        latitudeIsManual = manualLatitude != null,
        soundEnabled = store.soundEnabled(),
      )
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

  fun setSoundEnabled(enabled: Boolean) {
    store.setSoundEnabled(enabled)
    _uiState.update { it.copy(soundEnabled = enabled) }
    if (listening) tone.setEnabled(enabled)
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
    val session = ++feedbackSession
    val clock = FeedbackClock()
    tone.setEnabled(_uiState.value.soundEnabled)
    feedbackJob =
      viewModelScope.launch(Dispatchers.Default) {
        while (isActive && session == feedbackSession) {
          delay(feedbackStep(session, clock))
        }
      }
  }

  fun stop() {
    if (!listening) return
    listening = false
    orientation.stop()
    locations.stop()
    feedbackSession++
    feedbackJob?.cancel()
    feedbackJob = null
    tone.setEnabled(false)
  }

  override fun onCleared() {
    stop()
    proximity.release()
    tone.release()
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
    fun align(index: Int) =
      if (latitude != null && !southern && reading != null && sensorsAvailable) {
        PolarAlignment.guidance(
          magneticAzimuthDegrees = reading.magneticAzimuthDegrees,
          altitudeDegrees = reading.altitudeDegrees,
          sidewaysTiltDegrees = reading.sidewaysTiltDegrees,
          screenFacesUp = reading.screenFacesUp,
          latitudeDegrees = latitude,
          declinationDegrees = declination,
          compassTrustworthy = accuracy == CompassAccuracy.Medium || accuracy == CompassAccuracy.High,
          celebrationIndex = index,
        )
      } else {
        null
      }
    var guidance = align(celebrationIndex)
    val now = SystemClock.elapsedRealtime()
    val aligned = guidance?.aligned == true
    if (aligned) {
      val leftFor = unalignedSinceMs?.let { now - it }
      if (!wasAligned && hadLock && leftFor != null && leftFor > LOCK_ON_REARM_MS) {
        celebrationIndex = (celebrationIndex + 1) % PolarAlignment.CELEBRATIONS.size
        guidance = align(celebrationIndex)
      }
      hadLock = true
      unalignedSinceMs = null
    } else if (unalignedSinceMs == null) {
      unalignedSinceMs = now
    }
    wasAligned = aligned
    val shown = guidance
    _uiState.update { current ->
      current.copy(
        sensorsAvailable = sensorsAvailable,
        needsLatitude = latitude == null,
        southernHemisphere = southern,
        latitudeLabel = latitude?.let(PolarAlignment::formatLatitude),
        latitudeIsManual = manual != null,
        declinationKnown = declinationKnown,
        showChart = shown != null,
        aligned = aligned,
        azimuthError = shown?.azimuthErrorDegrees,
        altitudeError = shown?.altitudeErrorDegrees,
        azimuthMessage = shown?.azimuthMessage,
        altitudeMessage = shown?.altitudeMessage,
        statusMessage =
          when {
            southern -> "This app finds the north celestial pole. A southern latitude will not line up a mount here."
            shown != null -> shown.statusMessage
            else -> null
          },
        compassMessage =
          if (sensorsAvailable && shown != null) PolarAlignment.compassMessage(accuracy) else null,
      )
    }
  }

  private fun feedbackStep(session: Int, clock: FeedbackClock): Long {
    if (session != feedbackSession) return FEEDBACK_MIN_SLEEP_MS
    val state = _uiState.value
    val azimuth = state.azimuthError
    val altitude = state.altitudeError
    val now = SystemClock.elapsedRealtime()
    val guiding = state.showChart && azimuth != null && altitude != null
    if (!guiding || state.aligned) {
      if (guiding && state.aligned) {
        clock.unalignedSinceMs = null
        if (clock.lockOnArmed && session == feedbackSession) {
          clock.lockOnArmed = false
          clock.lastTickAtMs = now
          proximity.lockOn()
          tone.lockOn()
        }
      } else {
        noteUnaligned(clock, now)
      }
      return FEEDBACK_POLL_MS
    }
    noteUnaligned(clock, now)
    val total = hypot(azimuth, altitude)
    val interval = ProximityFeedback.intervalMillis(total) ?: return FEEDBACK_POLL_MS
    if (now - clock.lastTickAtMs >= interval && session == feedbackSession) {
      clock.lastTickAtMs = now
      proximity.tick()
      tone.beep(total)
    }
    val untilNext = interval - (SystemClock.elapsedRealtime() - clock.lastTickAtMs)
    return untilNext.coerceIn(FEEDBACK_MIN_SLEEP_MS, FEEDBACK_POLL_MS)
  }

  private fun noteUnaligned(clock: FeedbackClock, now: Long) {
    if (clock.lockOnArmed) {
      clock.unalignedSinceMs = null
      return
    }
    val since = clock.unalignedSinceMs ?: now.also { clock.unalignedSinceMs = it }
    if (now - since > LOCK_ON_REARM_MS) {
      clock.lockOnArmed = true
      clock.unalignedSinceMs = null
    }
  }

  private class FeedbackClock {
    var lockOnArmed = true
    var unalignedSinceMs: Long? = null
    var lastTickAtMs = 0L
  }

  private companion object {
    const val LOCK_ON_REARM_MS = 1_500L
    const val FEEDBACK_POLL_MS = 200L
    const val FEEDBACK_MIN_SLEEP_MS = 16L
  }
}
