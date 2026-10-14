package com.example.polaralign.alignment

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

/** Errors inside this band count as aligned. Matches a displayed tenth of a degree. */
const val ALIGNED_WITHIN_DEGREES = 1.05

/** Sideways tilt past this means the phone is not lying flat on the mount. */
const val LEVEL_WITHIN_DEGREES = 8.0

enum class CompassAccuracy {
  Unknown,
  Unreliable,
  Low,
  Medium,
  High,
}

data class ScreenAim(
  val magneticAzimuthDegrees: Double,
  val altitudeDegrees: Double,
  val sidewaysTiltDegrees: Double,
  val screenFacesUp: Boolean,
)

data class Guidance(
  val azimuthErrorDegrees: Double,
  val altitudeErrorDegrees: Double,
  val sidewaysTiltDegrees: Double,
  val aligned: Boolean,
  val azimuthMessage: String,
  val altitudeMessage: String,
  val statusMessage: String?,
)

/**
 * Northern-hemisphere polar alignment for a phone lying screen-up, with the top of the
 * screen aimed at the pole.
 *
 * [rotationMatrix] maps screen axes into the world frame used by Android: X east, Y north,
 * Z up. Column 1 (indexes 1, 4, 7) is where the top of the screen points.
 */
object PolarAlignment {
  fun wrapTo180(degrees: Double): Double {
    var wrapped = degrees % 360.0
    if (wrapped <= -180.0) wrapped += 360.0
    if (wrapped > 180.0) wrapped -= 360.0
    return wrapped
  }

  fun screenAim(rotationMatrix: FloatArray): ScreenAim {
    val east = rotationMatrix[1].toDouble()
    val north = rotationMatrix[4].toDouble()
    val up = rotationMatrix[7].toDouble()
    val altitude = Math.toDegrees(atan2(up, hypot(east, north)))
    val magneticAzimuth = Math.toDegrees(atan2(east, north))
    val sidewaysTilt = Math.toDegrees(asin(rotationMatrix[6].toDouble().coerceIn(-1.0, 1.0)))
    return ScreenAim(
      magneticAzimuthDegrees = magneticAzimuth,
      altitudeDegrees = altitude,
      sidewaysTiltDegrees = sidewaysTilt,
      screenFacesUp = rotationMatrix[8] > 0f,
    )
  }

  fun guidance(
    magneticAzimuthDegrees: Double,
    altitudeDegrees: Double,
    sidewaysTiltDegrees: Double,
    screenFacesUp: Boolean,
    latitudeDegrees: Double,
    declinationDegrees: Double,
    compassTrustworthy: Boolean,
  ): Guidance {
    val trueAzimuth = wrapTo180(magneticAzimuthDegrees + declinationDegrees)
    val azimuthError = wrapTo180(trueAzimuth)
    val altitudeError = altitudeDegrees - latitudeDegrees
    val level = abs(sidewaysTiltDegrees) <= LEVEL_WITHIN_DEGREES
    val onTarget = abs(azimuthError) < ALIGNED_WITHIN_DEGREES && abs(altitudeError) < ALIGNED_WITHIN_DEGREES
    val aligned = screenFacesUp && level && onTarget && compassTrustworthy
    val status =
      when {
        !screenFacesUp -> "Turn the phone so the screen faces the sky."
        !level && sidewaysTiltDegrees > 0 -> "The phone is twisted, right side high. Rotate it so the left and right edges are level."
        !level -> "The phone is twisted, left side high. Rotate it so the left and right edges are level."
        aligned -> "Aligned"
        !compassTrustworthy -> "Calibrate the compass before treating this as aligned."
        else -> "Get both axes within 1°."
      }
    return Guidance(
      azimuthErrorDegrees = azimuthError,
      altitudeErrorDegrees = altitudeError,
      sidewaysTiltDegrees = sidewaysTiltDegrees,
      aligned = aligned,
      azimuthMessage = azimuthMessage(azimuthError),
      altitudeMessage = altitudeMessage(altitudeError),
      statusMessage = status,
    )
  }

  fun compassMessage(accuracy: CompassAccuracy): String? =
    when (accuracy) {
      CompassAccuracy.High -> null
      CompassAccuracy.Medium -> "Compass is usable. A figure-8 away from the mount can steady it."
      CompassAccuracy.Low,
      CompassAccuracy.Unreliable ->
        "Compass needs a calibration. Wave the phone in a figure-8 away from the metal mount, then set it back down."
      CompassAccuracy.Unknown -> "Waiting for the compass."
    }

  fun formatLatitude(latitude: Double): String {
    val hemisphere = if (latitude >= 0) "N" else "S"
    return String.format(Locale.US, "%.2f° %s", abs(latitude), hemisphere)
  }

  private fun azimuthMessage(error: Double): String {
    if (abs(error) < ALIGNED_WITHIN_DEGREES) return "Azimuth is within 1°."
    val amount = formatMagnitude(error)
    return if (error > 0) {
      "Too far east by $amount. Turn the mount west."
    } else {
      "Too far west by $amount. Turn the mount east."
    }
  }

  private fun altitudeMessage(error: Double): String {
    if (abs(error) < ALIGNED_WITHIN_DEGREES) return "Altitude is within 1°."
    val amount = formatMagnitude(error)
    return if (error > 0) {
      "Too high by $amount. Lower the altitude."
    } else {
      "Too low by $amount. Raise the altitude."
    }
  }

  private fun formatMagnitude(degrees: Double): String = String.format(Locale.US, "%.1f°", abs(degrees))
}
