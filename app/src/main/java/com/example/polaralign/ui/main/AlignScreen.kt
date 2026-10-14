package com.example.polaralign.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.polaralign.theme.PolarAlignTheme

@Composable
fun AlignRoute(modifier: Modifier = Modifier, viewModel: AlignViewModel = viewModel()) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val view = LocalView.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      viewModel.onPermissionResult(granted)
    }

  LaunchedEffect(view.display.rotation) { viewModel.setDisplayRotation(view.display.rotation) }

  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        when (event) {
          Lifecycle.Event.ON_START -> viewModel.start()
          Lifecycle.Event.ON_STOP -> viewModel.stop()
          else -> Unit
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
      viewModel.start()
    }
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      viewModel.stop()
    }
  }

  LaunchedEffect(Unit) {
    val granted =
      ContextCompat.checkSelfPermission(view.context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    viewModel.onPermissionResult(granted)
    if (!granted) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
  }

  AlignScreen(
    state = state,
    modifier = modifier,
    onNightMode = viewModel::setNightMode,
    onSound = viewModel::setSoundEnabled,
    onSubmitLatitude = viewModel::setManualLatitude,
    onUseGps = {
      val granted =
        ContextCompat.checkSelfPermission(view.context, Manifest.permission.ACCESS_FINE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED
      if (granted) {
        viewModel.onPermissionResult(true)
        viewModel.useGpsLatitude()
      } else {
        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
      }
    },
    onRequestPermission = { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
    onOpenLocationSettings = {
      val activity = view.context.findActivity()
      val intent =
        Intent(
          Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
          Uri.fromParts("package", view.context.packageName, null),
        )
      activity?.startActivity(intent) ?: view.context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    },
  )
}

@Composable
fun AlignScreen(
  state: AlignUiState,
  onNightMode: (Boolean) -> Unit,
  onSound: (Boolean) -> Unit,
  onSubmitLatitude: (Double) -> Unit,
  onUseGps: () -> Unit,
  onRequestPermission: () -> Unit,
  onOpenLocationSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showLatitudeDialog by remember { mutableStateOf(false) }

  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
    Column(
      Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
          "Polar Align",
          style = MaterialTheme.typography.headlineMedium,
          color = MaterialTheme.colorScheme.onBackground,
          modifier = Modifier.weight(1f),
        )
        TextButton(
          onClick = { onSound(!state.soundEnabled) },
          contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
          Text(if (state.soundEnabled) "Sound off" else "Sound on")
        }
        TextButton(
          onClick = { onNightMode(!state.nightMode) },
          contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
          Text(if (state.nightMode) "Day view" else "Night view")
        }
      }

      Text(
        "Tape the phone to the tracker, screen toward the sky, with the top of the screen pointing along the polar axis. A tilted tracker is fine.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
      )

      when {
        !state.sensorsAvailable ->
          Text(
            "This phone did not report a compass, so it cannot aim at the pole.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
          )
        state.southernHemisphere ->
          Text(
            state.statusMessage.orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
          )
        state.needsLatitude ->
          Text(
            "The pole sits due north, at an angle above the horizon equal to your latitude.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
          )
        else -> {
          state.statusMessage?.let { message ->
            Text(
              message,
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = if (state.aligned) FontWeight.Bold else FontWeight.Normal,
              color = MaterialTheme.colorScheme.onBackground,
            )
          }
          CorrectionText(state.azimuthMessage)
          CorrectionText(state.altitudeMessage)
          PointingChart(
            azimuthError = state.azimuthError ?: 0.0,
            altitudeError = state.altitudeError ?: 0.0,
            aligned = state.aligned,
          )
        }
      }

      state.compassMessage?.let { message ->
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      if (!state.declinationKnown && !state.needsLatitude && !state.southernHemisphere) {
        Text(
          "Turn on location so north can be corrected for magnetic declination. Until then, the compass can be off by several degrees.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      LatitudeRow(
        state = state,
        onChange = { showLatitudeDialog = true },
        onUseGps = onUseGps,
        onRequestPermission = onRequestPermission,
        onOpenLocationSettings = onOpenLocationSettings,
      )

      Text(
        "Metal in the mount can pull the compass by a degree or two. That is enough for a star tracker with a camera lens.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }

  if (showLatitudeDialog) {
    LatitudeDialog(
      initial = state.latitudeLabel?.substringBefore("°") ?: "",
      onDismiss = { showLatitudeDialog = false },
      onSubmit = { latitude ->
        onSubmitLatitude(latitude)
        showLatitudeDialog = false
      },
    )
  }
}

@Composable
private fun CorrectionText(message: String?) {
  if (message == null) return
  Text(
    message,
    color = MaterialTheme.colorScheme.onBackground,
    fontSize = 22.sp,
    lineHeight = 28.sp,
    fontWeight = FontWeight.Medium,
  )
}

@Composable
private fun LatitudeRow(
  state: AlignUiState,
  onChange: () -> Unit,
  onUseGps: () -> Unit,
  onRequestPermission: () -> Unit,
  onOpenLocationSettings: () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    val label =
      when {
        state.latitudeLabel == null -> "Latitude needed"
        state.latitudeIsManual -> "${state.latitudeLabel}, entered"
        else -> "${state.latitudeLabel} from GPS"
      }
    Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    Row {
      TextButton(onClick = onChange) { Text("Enter latitude") }
      if (state.latitudeIsManual) {
        TextButton(onClick = onUseGps) { Text("Use GPS") }
      } else if (!state.permissionGranted) {
        TextButton(onClick = onRequestPermission) { Text("Allow location") }
        TextButton(onClick = onOpenLocationSettings) { Text("Settings") }
      }
    }
  }
}

@Composable
private fun LatitudeDialog(initial: String, onDismiss: () -> Unit, onSubmit: (Double) -> Unit) {
  var draft by remember(initial) { mutableStateOf(initial) }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Latitude") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Northern hemisphere only. Example: 47.6")
        OutlinedTextField(
          value = draft,
          onValueChange = {
            draft = it
            error = null
          },
          label = { Text("Degrees north") },
          singleLine = true,
          isError = error != null,
          supportingText = error?.let { message -> { Text(message) } },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          val latitude = draft.trim().replace(',', '.').toDoubleOrNull()
          when {
            latitude == null -> error = "Enter a number, like 47.6."
            latitude < 0.0 || latitude > 90.0 -> error = "Enter a northern latitude from 0 to 90."
            else -> onSubmit(latitude)
          }
        }
      ) {
        Text("Save")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

private tailrec fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun AlignScreenNightPreview() {
  PolarAlignTheme(nightMode = true) {
    Surface(color = MaterialTheme.colorScheme.background) {
      AlignScreen(
        state = previewState(),
        onNightMode = {},
        onSound = {},
        onSubmitLatitude = {},
        onUseGps = {},
        onRequestPermission = {},
        onOpenLocationSettings = {},
      )
    }
  }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800)
@Composable
private fun AlignScreenDayPreview() {
  PolarAlignTheme(nightMode = false) {
    Surface(color = MaterialTheme.colorScheme.background) {
      AlignScreen(
        state =
          previewState().copy(
            nightMode = false,
            aligned = true,
            azimuthError = 0.2,
            altitudeError = -0.4,
            azimuthMessage = "Azimuth is within 1°.",
            altitudeMessage = "Altitude is within 1°.",
            statusMessage = "Locked on. Polaris approves.",
            compassMessage = null,
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
}

private fun previewState() =
  AlignUiState(
    nightMode = true,
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
    statusMessage = "Warmer…",
    compassMessage = "Compass is usable. A figure-8 away from the mount can steady it.",
  )
