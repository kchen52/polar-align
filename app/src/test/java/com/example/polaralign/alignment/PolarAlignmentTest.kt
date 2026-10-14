package com.example.polaralign.alignment

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarAlignmentTest {
  @Test
  fun flatPhoneAimedNorthAtTheEquatorIsAligned() {
    val aim = PolarAlignment.screenAim(identityMatrix())
    val guidance =
      PolarAlignment.guidance(
        magneticAzimuthDegrees = aim.magneticAzimuthDegrees,
        altitudeDegrees = aim.altitudeDegrees,
        sidewaysTiltDegrees = aim.sidewaysTiltDegrees,
        screenFacesUp = aim.screenFacesUp,
        latitudeDegrees = 0.0,
        declinationDegrees = 0.0,
        compassTrustworthy = true,
      )

    assertEquals(0.0, aim.magneticAzimuthDegrees, 0.05)
    assertEquals(0.0, aim.altitudeDegrees, 0.05)
    assertTrue(aim.screenFacesUp)
    assertTrue(guidance.aligned)
    assertEquals("Locked on. Polaris approves.", guidance.statusMessage)
  }

  @Test
  fun pitchTowardNorthMatchesLatitude() {
    val aim = PolarAlignment.screenAim(aimMatrix(azimuthDegrees = 0.0, altitudeDegrees = 47.0))
    val guidance =
      guidanceFor(
        aim,
        latitude = 47.0,
        declination = 0.0,
      )

    assertEquals(47.0, aim.altitudeDegrees, 0.1)
    assertEquals(0.0, guidance.azimuthErrorDegrees, 0.1)
    assertEquals(0.0, guidance.altitudeErrorDegrees, 0.1)
    assertTrue(guidance.aligned)
  }

  @Test
  fun eastwardErrorTellsYouToTurnWest() {
    val aim = PolarAlignment.screenAim(aimMatrix(azimuthDegrees = 90.0, altitudeDegrees = 40.0))
    val guidance = guidanceFor(aim, latitude = 37.0, declination = 0.0)

    assertEquals(90.0, guidance.azimuthErrorDegrees, 0.1)
    assertEquals(3.0, guidance.altitudeErrorDegrees, 0.1)
    assertEquals("Too far east by 90.0°. Turn the mount west.", guidance.azimuthMessage)
    assertEquals("Too high by 3.0°. Lower the altitude.", guidance.altitudeMessage)
    assertEquals("Cold. Let's go find the pole.", guidance.statusMessage)
    assertFalse(guidance.aligned)
  }

  @Test
  fun westwardAndLowErrorsUseTheOppositeKnobs() {
    val aim = PolarAlignment.screenAim(aimMatrix(azimuthDegrees = -12.4, altitudeDegrees = 35.0))
    val guidance = guidanceFor(aim, latitude = 37.2, declination = 0.0)

    assertEquals("Too far west by 12.4°. Turn the mount east.", guidance.azimuthMessage)
    assertEquals("Too low by 2.2°. Raise the altitude.", guidance.altitudeMessage)
  }

  @Test
  fun eastDeclinationMakesMagneticNorthLookTooFarEast() {
    val aim = PolarAlignment.screenAim(identityMatrix())
    val guidance = guidanceFor(aim, latitude = 0.0, declination = 13.0)

    assertEquals(13.0, guidance.azimuthErrorDegrees, 0.05)
    assertEquals("Too far east by 13.0°. Turn the mount west.", guidance.azimuthMessage)
  }

  @Test
  fun azimuthNear360WrapsToASmallWestError() {
    val aim = PolarAlignment.screenAim(aimMatrix(azimuthDegrees = 350.0, altitudeDegrees = 0.0))
    val guidance = guidanceFor(aim, latitude = 0.0, declination = 0.0)

    assertEquals(-10.0, guidance.azimuthErrorDegrees, 0.1)
    assertEquals("Too far west by 10.0°. Turn the mount east.", guidance.azimuthMessage)
  }

  @Test
  fun warmthBandsFollowTotalError() {
    assertEquals("Cold. Let's go find the pole.", PolarAlignment.warmthMessage(5.01))
    assertEquals("Cold. Let's go find the pole.", PolarAlignment.warmthMessage(12.0))
    assertEquals("Warmer…", PolarAlignment.warmthMessage(5.0))
    assertEquals("Warmer…", PolarAlignment.warmthMessage(2.0))
    assertEquals("Hot! Almost there.", PolarAlignment.warmthMessage(1.99))
    assertEquals("Hot! Almost there.", PolarAlignment.warmthMessage(1.1))

    val diagonal =
      guidanceFor(
        PolarAlignment.screenAim(aimMatrix(azimuthDegrees = 4.0, altitudeDegrees = 44.0)),
        latitude = 40.0,
        declination = 0.0,
      )
    val total = hypot(diagonal.azimuthErrorDegrees, diagonal.altitudeErrorDegrees)
    assertTrue(total > 5.0)
    assertTrue(abs(diagonal.azimuthErrorDegrees) < 5.0)
    assertTrue(abs(diagonal.altitudeErrorDegrees) < 5.0)
    assertEquals("Cold. Let's go find the pole.", diagonal.statusMessage)
    assertEquals("Too far east by 4.0°. Turn the mount west.", diagonal.azimuthMessage)
    assertEquals("Too high by 4.0°. Lower the altitude.", diagonal.altitudeMessage)
  }

  @Test
  fun celebrationStaysOnTheRequestedIndex() {
    assertEquals("Locked on. Polaris approves.", PolarAlignment.celebrationMessage(0))
    assertEquals("Aligned! Clear skies.", PolarAlignment.celebrationMessage(1))
    assertEquals("Right on the pole. Go take some photos.", PolarAlignment.celebrationMessage(2))
    assertEquals(PolarAlignment.celebrationMessage(0), PolarAlignment.celebrationMessage(3))

    val aim = PolarAlignment.screenAim(identityMatrix())
    val first = guidanceFor(aim, latitude = 0.0, declination = 0.0, celebrationIndex = 1)
    val again = guidanceFor(aim, latitude = 0.0, declination = 0.0, celebrationIndex = 1)
    assertTrue(first.aligned)
    assertEquals("Aligned! Clear skies.", first.statusMessage)
    assertEquals(first.statusMessage, again.statusMessage)
    assertEquals(
      "Right on the pole. Go take some photos.",
      guidanceFor(aim, latitude = 0.0, declination = 0.0, celebrationIndex = 2).statusMessage,
    )
  }

  @Test
  fun sidewaysTiltAsksForAFlatPhone() {
    val aim = PolarAlignment.screenAim(rolledMatrix(rollDegrees = 15.0))
    val guidance = guidanceFor(aim, latitude = 0.0, declination = 0.0)

    assertEquals(15.0, aim.sidewaysTiltDegrees, 0.2)
    assertEquals(
      "The phone is twisted, right side high. Rotate it so the left and right edges are level.",
      guidance.statusMessage,
    )
    assertFalse(guidance.aligned)
  }

  @Test
  fun screenFacingDownIsCalledOut() {
    val faceDown = identityMatrix().also { it[8] = -1f }
    val aim = PolarAlignment.screenAim(faceDown)
    val guidance = guidanceFor(aim, latitude = 0.0, declination = 0.0)

    assertFalse(aim.screenFacesUp)
    assertEquals("Turn the phone so the screen faces the sky.", guidance.statusMessage)
    assertFalse(guidance.aligned)
  }

  @Test
  fun untrustworthyCompassDoesNotReportAligned() {
    val guidance = guidanceFor(PolarAlignment.screenAim(identityMatrix()), latitude = 0.0, declination = 0.0, trustworthy = false)

    assertFalse(guidance.aligned)
    assertEquals("Calibrate the compass before treating this as aligned.", guidance.statusMessage)
    assertEquals(
      "Compass needs a calibration. Wave the phone in a figure-8 away from the metal mount, then set it back down.",
      PolarAlignment.compassMessage(CompassAccuracy.Unreliable),
    )
  }

  @Test
  fun wrapAndLatitudeFormatting() {
    assertEquals(10.0, PolarAlignment.wrapTo180(370.0), 0.001)
    assertEquals(-170.0, PolarAlignment.wrapTo180(190.0), 0.001)
    assertEquals(170.0, PolarAlignment.wrapTo180(-190.0), 0.001)
    assertEquals("47.61° N", PolarAlignment.formatLatitude(47.606))
    assertEquals("33.90° S", PolarAlignment.formatLatitude(-33.9))
  }

  private fun guidanceFor(
    aim: ScreenAim,
    latitude: Double,
    declination: Double,
    trustworthy: Boolean = true,
    celebrationIndex: Int = 0,
  ) =
    PolarAlignment.guidance(
      magneticAzimuthDegrees = aim.magneticAzimuthDegrees,
      altitudeDegrees = aim.altitudeDegrees,
      sidewaysTiltDegrees = aim.sidewaysTiltDegrees,
      screenFacesUp = aim.screenFacesUp,
      latitudeDegrees = latitude,
      declinationDegrees = declination,
      compassTrustworthy = trustworthy,
      celebrationIndex = celebrationIndex,
    )

  private fun identityMatrix(): FloatArray = aimMatrix(azimuthDegrees = 0.0, altitudeDegrees = 0.0)

  private fun aimMatrix(azimuthDegrees: Double, altitudeDegrees: Double): FloatArray {
    val azimuth = Math.toRadians(azimuthDegrees)
    val altitude = Math.toRadians(altitudeDegrees)
    val y = Vec(east = sin(azimuth) * cos(altitude), north = cos(azimuth) * cos(altitude), up = sin(altitude))
    val x = Vec(east = cos(azimuth), north = -sin(azimuth), up = 0.0)
    return columns(x, y, x.cross(y))
  }

  private fun rolledMatrix(rollDegrees: Double): FloatArray {
    val roll = Math.toRadians(rollDegrees)
    val x = Vec(east = cos(roll), north = 0.0, up = sin(roll))
    val y = Vec(east = 0.0, north = 1.0, up = 0.0)
    return columns(x, y, x.cross(y))
  }

  private fun columns(x: Vec, y: Vec, z: Vec): FloatArray =
    floatArrayOf(
      x.east.toFloat(),
      y.east.toFloat(),
      z.east.toFloat(),
      x.north.toFloat(),
      y.north.toFloat(),
      z.north.toFloat(),
      x.up.toFloat(),
      y.up.toFloat(),
      z.up.toFloat(),
    )

  private data class Vec(val east: Double, val north: Double, val up: Double) {
    fun cross(other: Vec): Vec =
      Vec(
        east = north * other.up - up * other.north,
        north = up * other.east - east * other.up,
        up = east * other.north - north * other.east,
      )
  }
}
