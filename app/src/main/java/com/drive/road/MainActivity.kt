package com.drive.road

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.systemuicontroller.rememberSystemUiController

class MainActivity : ComponentActivity() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoadMonitoringApp()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun RoadMonitoringApp() {
    var isConfigured by remember { mutableStateOf(false) }

    val systemUiController = rememberSystemUiController()

    SideEffect {
        systemUiController.setStatusBarColor(
            color = Color.Black,
            darkIcons = true // Set true if your background is light
        )
    }

    // FIXED: Use single ViewModel instance throughout the app
    val viewModel: RoadMonitoringViewModel = viewModel()

    if (!isConfigured) {
        ConfigurationScreen(
            onConfigurationSet = { vehicleType, phonePlacement ->
                viewModel.setVehicleConfiguration(vehicleType, phonePlacement)
                isConfigured = true
            },
            onStartMonitoring = {
                // Configuration is complete, will show main screen
            }
        )
    } else {
        // FIXED: Pass the same ViewModel instance
        RoadMonitoringScreen(viewModel = viewModel)
    }
}