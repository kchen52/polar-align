package com.example.polaralign.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.polaralign.theme.PolarAlignTheme
import org.junit.Rule
import org.junit.Test

class AlignScreenTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun showsWhichWayToTurnTheMount() {
    composeTestRule.setContent {
      PolarAlignTheme {
        AlignScreen(
          state =
            AlignUiState(
              sensorsAvailable = true,
              permissionGranted = true,
              needsLatitude = false,
              latitudeLabel = "47.60° N",
              declinationKnown = true,
              showChart = true,
              azimuthError = 2.4,
              altitudeError = -1.2,
              azimuthMessage = "Too far east by 2.4°. Turn the mount west.",
              altitudeMessage = "Too low by 1.2°. Raise the altitude.",
              statusMessage = "Get both axes within 1°.",
            ),
          onNightMode = {},
          onSound = {},
          onSubmitLatitude = {},
          onUseGps = {},
          onRequestPermission = {},
          onOpenLocationSettings = {},
        )
      }
    }

    composeTestRule.onNodeWithText("Polar Align").assertExists()
    composeTestRule.onNodeWithText("Too far east by 2.4°. Turn the mount west.").assertExists()
    composeTestRule.onNodeWithText("Too low by 1.2°. Raise the altitude.").assertExists()
    composeTestRule.onNodeWithText("47.60° N from GPS").assertExists()
  }
}
