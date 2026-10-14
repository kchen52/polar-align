package com.example.polaralign

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.polaralign.theme.PolarAlignTheme
import com.example.polaralign.ui.main.AlignRoute
import com.example.polaralign.ui.main.AlignViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    enableEdgeToEdge()
    setContent {
      val viewModel: AlignViewModel = viewModel()
      val state by viewModel.uiState.collectAsStateWithLifecycle()
      PolarAlignTheme(nightMode = state.nightMode) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          AlignRoute(modifier = Modifier.fillMaxSize().safeDrawingPadding(), viewModel = viewModel)
        }
      }
    }
  }
}
