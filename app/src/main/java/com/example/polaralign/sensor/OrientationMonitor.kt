package com.example.polaralign.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.example.polaralign.alignment.CompassAccuracy
import com.example.polaralign.alignment.PolarAlignment

data class OrientationSample(
  val magneticAzimuthDegrees: Double = 0.0,
  val altitudeDegrees: Double = 0.0,
  val sidewaysTiltDegrees: Double = 0.0,
  val screenFacesUp: Boolean = true,
  val accuracy: CompassAccuracy = CompassAccuracy.Unknown,
  val available: Boolean = true,
)

/**
 * Tracks where the top of the current screen is pointing. Sensor axes stay fixed to the
 * device, so each reading is remapped into the screen the activity is actually on.
 */
class OrientationMonitor(context: Context) {
  private val sensorManager = context.getSystemService(SensorManager::class.java)
  private val rotationSensor =
    sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
      ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
  private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
  private val handler = Handler(Looper.getMainLooper())

  private var callback: ((OrientationSample) -> Unit)? = null
  private var running = false
  private var rotation = Surface.ROTATION_0
  private var accuracy = CompassAccuracy.Unknown
  private var smoothAzimuth: Double? = null
  private var smoothAltitude: Double? = null
  private var smoothTilt: Double? = null
  private var lastEmitted: OrientationSample? = null

  private val listener =
    object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
          accuracy = event.accuracy.toCompassAccuracy()
          return
        }
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR && event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) {
          return
        }
        // Heading accuracy lives in values[4]. Many phones leave SensorEvent.accuracy at 0 for this sensor.
        if (magnetometer == null) accuracy = event.toHeadingAccuracy()
        val vector =
          if (event.values.size >= 4) {
            event.values.copyOfRange(0, 4)
          } else {
            event.values.copyOfRange(0, 3)
          }
        val deviceMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(deviceMatrix, vector)
        emit(deviceMatrix)
      }

      override fun onAccuracyChanged(sensor: Sensor?, accuracyValue: Int) {
        if (sensor?.type != Sensor.TYPE_MAGNETIC_FIELD) return
        accuracy = accuracyValue.toCompassAccuracy()
        lastEmitted = null
      }
    }

  fun setDisplayRotation(displayRotation: Int) {
    if (rotation == displayRotation) return
    rotation = displayRotation
    smoothAzimuth = null
    smoothAltitude = null
    smoothTilt = null
    lastEmitted = null
  }

  fun start(onSample: (OrientationSample) -> Unit) {
    callback = onSample
    if (running) return
    running = true
    val sensor = rotationSensor
    if (sensor == null) {
      onSample(OrientationSample(available = false, accuracy = CompassAccuracy.Unreliable))
      return
    }
    sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
    magnetometer?.let { magnetic ->
      sensorManager.registerListener(listener, magnetic, SensorManager.SENSOR_DELAY_NORMAL, handler)
    }
  }

  fun stop() {
    if (!running) return
    running = false
    callback = null
    sensorManager.unregisterListener(listener)
    smoothAzimuth = null
    smoothAltitude = null
    smoothTilt = null
    lastEmitted = null
  }

  private fun emit(deviceMatrix: FloatArray) {
    val screenMatrix = FloatArray(9)
    val remapped =
      SensorManager.remapCoordinateSystem(deviceMatrix, axisX(rotation), axisY(rotation), screenMatrix)
    val aim = PolarAlignment.screenAim(if (remapped) screenMatrix else deviceMatrix)
    val azimuth = smoothAngle(smoothAzimuth, aim.magneticAzimuthDegrees).also { smoothAzimuth = it }
    val altitude = smoothLinear(smoothAltitude, aim.altitudeDegrees).also { smoothAltitude = it }
    val tilt = smoothLinear(smoothTilt, aim.sidewaysTiltDegrees).also { smoothTilt = it }
    val sample =
      OrientationSample(
        magneticAzimuthDegrees = azimuth,
        altitudeDegrees = altitude,
        sidewaysTiltDegrees = tilt,
        screenFacesUp = aim.screenFacesUp,
        accuracy = accuracy,
      )
    val previous = lastEmitted
    val sameReading =
      previous != null &&
        previous.accuracy == sample.accuracy &&
        previous.screenFacesUp == sample.screenFacesUp &&
        tenths(previous.magneticAzimuthDegrees) == tenths(sample.magneticAzimuthDegrees) &&
        tenths(previous.altitudeDegrees) == tenths(sample.altitudeDegrees) &&
        tenths(previous.sidewaysTiltDegrees) == tenths(sample.sidewaysTiltDegrees)
    if (sameReading) return
    lastEmitted = sample
    callback?.invoke(sample)
  }

  private fun smoothAngle(previous: Double?, sample: Double): Double {
    if (previous == null) return sample
    val delta = PolarAlignment.wrapTo180(sample - previous)
    return PolarAlignment.wrapTo180(previous + delta * SMOOTHING)
  }

  private fun smoothLinear(previous: Double?, sample: Double): Double {
    if (previous == null) return sample
    return previous + (sample - previous) * SMOOTHING
  }

  private fun tenths(value: Double): Int = kotlin.math.round(value * 10.0).toInt()

  private fun axisX(displayRotation: Int): Int =
    when (displayRotation) {
      Surface.ROTATION_90 -> SensorManager.AXIS_Y
      Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X
      Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y
      else -> SensorManager.AXIS_X
    }

  private fun axisY(displayRotation: Int): Int =
    when (displayRotation) {
      Surface.ROTATION_90 -> SensorManager.AXIS_MINUS_X
      Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_Y
      Surface.ROTATION_270 -> SensorManager.AXIS_X
      else -> SensorManager.AXIS_Y
    }

  private fun Int.toCompassAccuracy(): CompassAccuracy =
    when (this) {
      SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassAccuracy.High
      SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassAccuracy.Medium
      SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassAccuracy.Low
      else -> CompassAccuracy.Unreliable
    }

  private fun SensorEvent.toHeadingAccuracy(): CompassAccuracy {
    if (values.size >= 5 && values[4] >= 0f) {
      val degrees = Math.toDegrees(values[4].toDouble())
      return when {
        degrees <= 15.0 -> CompassAccuracy.High
        degrees <= 30.0 -> CompassAccuracy.Medium
        degrees <= 50.0 -> CompassAccuracy.Low
        else -> CompassAccuracy.Unreliable
      }
    }
    return this.accuracy.toCompassAccuracy()
  }

  private companion object {
    const val SMOOTHING = 0.25
  }
}
