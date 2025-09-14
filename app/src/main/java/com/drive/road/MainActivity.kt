package com.drive.road

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel

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