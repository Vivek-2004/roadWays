package com.drive.road

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigurationScreen(
    onConfigurationSet: (VehicleType, PhonePlacement) -> Unit,
    onStartMonitoring: () -> Unit
) {
    val scrollState = rememberScrollState()
    var selectedVehicle by remember { mutableStateOf<VehicleType?>(null) }
    var selectedPlacement by remember { mutableStateOf<PhonePlacement?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "Road Monitoring Configuration",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Configure your setup to ensure accurate road condition detection based on RoadSurP research.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Vehicle Type Selection
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Select Vehicle Type",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(12.dp))

                Column(Modifier.selectableGroup()) {
                    VehicleType.values().forEach { vehicle ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .selectable(
                                    selected = (selectedVehicle == vehicle),
                                    onClick = { selectedVehicle = vehicle },
                                    role = Role.RadioButton
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedVehicle == vehicle),
                                onClick = null
                            )
                            Column(
                                modifier = Modifier.padding(start = 16.dp)
                            ) {
                                Text(
                                    text = when (vehicle) {
                                        VehicleType.TWO_WHEELER_SCOOTY -> "Two-Wheeler (Scooty)"
                                        VehicleType.TWO_WHEELER_BIKE -> "Two-Wheeler (Bike)"
                                        VehicleType.THREE_WHEELER_AUTO -> "Three-Wheeler (Auto-rickshaw)"
                                        VehicleType.FOUR_WHEELER_CAR -> "Four-Wheeler (Car)"
                                    },
                                    fontSize = 16.sp
                                )
//                                Text(
//                                    text = when (vehicle) {
//                                        VehicleType.TWO_WHEELER_SCOOTY -> "Honda Dio, Aviator, etc."
//                                        VehicleType.TWO_WHEELER_BIKE -> "Honda Achiever, FZS Yamaha, etc."
//                                        VehicleType.THREE_WHEELER_AUTO -> "Auto-rickshaw"
//                                        VehicleType.FOUR_WHEELER_CAR -> "Car (i10, Dzire, Etios, etc.)"
//                                    },
//                                    fontSize = 12.sp,
//                                    color = MaterialTheme.colorScheme.onSurfaceVariant
//                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectedVehicle != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Select Phone Placement",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val availablePlacements = when (selectedVehicle) {
                        VehicleType.TWO_WHEELER_SCOOTY, VehicleType.TWO_WHEELER_BIKE ->
                            listOf(PhonePlacement.MOUNTER, PhonePlacement.POCKET)

                        VehicleType.THREE_WHEELER_AUTO ->
//                            listOf(PhonePlacement.WINDSHIELD, PhonePlacement.POCKET)
                            listOf(PhonePlacement.MOUNTER, PhonePlacement.POCKET)

                        VehicleType.FOUR_WHEELER_CAR ->
//                            listOf(
//                                PhonePlacement.WINDSHIELD,
//                                PhonePlacement.DASHBOARD,
//                                PhonePlacement.POCKET
//                            )
                            listOf(PhonePlacement.MOUNTER, PhonePlacement.POCKET)

                        else -> emptyList()
                    }

                    Column(Modifier.selectableGroup()) {
                        availablePlacements.forEach { placement ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .selectable(
                                        selected = (selectedPlacement == placement),
                                        onClick = { selectedPlacement = placement },
                                        role = Role.RadioButton
                                    )
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (selectedPlacement == placement),
                                    onClick = null
                                )
                                Column(
                                    modifier = Modifier.padding(start = 16.dp)
                                ) {
                                    Text(
                                        text = when (placement) {
                                            PhonePlacement.MOUNTER -> "Handle Mount"
                                            PhonePlacement.POCKET -> "Shirt Pocket"
                                            PhonePlacement.WINDSHIELD -> "Windshield Mount"
                                            PhonePlacement.DASHBOARD -> "Dashboard Mount"
                                        },
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = when (placement) {
                                            PhonePlacement.MOUNTER -> "Mounted on vehicle handle (recommended)"
                                            PhonePlacement.POCKET -> "Phone kept in shirt pocket"
                                            PhonePlacement.WINDSHIELD -> "Mounted on windshield glass"
                                            PhonePlacement.DASHBOARD -> "Placed on dashboard"
                                        },
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (selectedVehicle != null && selectedPlacement != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Initial Thresholds (Based on Research)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    val thresholds = getInitialThresholds(selectedVehicle!!, selectedPlacement!!)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Speed Breaker", fontSize = 12.sp)
                            Text(
                                "${thresholds.first}g",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Pothole", fontSize = 12.sp)
                            Text(
                                "${thresholds.second}g",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "These thresholds will automatically adjust based on your speed using the auto-threshold algorithm.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Start Button
        Button(
            onClick = {
                selectedVehicle?.let { vehicle ->
                    selectedPlacement?.let { placement ->
                        onConfigurationSet(vehicle, placement)
                        onStartMonitoring()
                    }
                }
            },
            enabled = selectedVehicle != null && selectedPlacement != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Road Monitoring")
        }
    }
}

private fun getInitialThresholds(
    vehicle: VehicleType,
    placement: PhonePlacement
): Pair<Float, Float> {
    // Return thresholds based on Table 2 from the research paper
    return when (Pair(vehicle, placement)) {
        Pair(VehicleType.TWO_WHEELER_SCOOTY, PhonePlacement.MOUNTER) -> Pair(1.8f, 0.714f)
        Pair(VehicleType.TWO_WHEELER_SCOOTY, PhonePlacement.POCKET) -> Pair(1.57f, 0.612f)

        Pair(VehicleType.TWO_WHEELER_BIKE, PhonePlacement.MOUNTER) -> Pair(1.73f, 0.816f)
        Pair(VehicleType.TWO_WHEELER_BIKE, PhonePlacement.POCKET) -> Pair(1.53f, 0.714f)

        Pair(VehicleType.THREE_WHEELER_AUTO, PhonePlacement.WINDSHIELD) -> Pair(1.47f, 0.612f)

        Pair(VehicleType.FOUR_WHEELER_CAR, PhonePlacement.WINDSHIELD) -> Pair(1.08f, 0.41f)
        Pair(VehicleType.FOUR_WHEELER_CAR, PhonePlacement.DASHBOARD) -> Pair(1.08f, 0.41f)
        else -> Pair(1.73f, 0.816f) // Default fallback to Bike
    }
}