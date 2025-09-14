package com.drive.road

import android.Manifest
import android.content.Context
import android.graphics.Paint
import android.location.Location
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.abs

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RoadMonitoringScreen(
    viewModel: RoadMonitoringViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentLocation by viewModel.currentLocation.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val sensorData by viewModel.sensorData.collectAsState()
    val accelerometerHistory by viewModel.accelerometerHistory.collectAsState()
    val detectedEvents by viewModel.detectedEvents.collectAsState()
    val showEventDialog by viewModel.showEventDialog.collectAsState()
    val pendingEvent by viewModel.pendingEvent.collectAsState()

    // Request permissions
    val locationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    LaunchedEffect(locationPermissions.allPermissionsGranted) {
        if (locationPermissions.allPermissionsGranted) {
            viewModel.initialize(context)
        }
    }

    // FIXED: Add periodic location refresh when tracking
    LaunchedEffect(isTracking) {
        if (isTracking) {
            while (isTracking) {
                delay(2000) // Request location update every 2 seconds
                viewModel.requestLocationUpdate()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (!locationPermissions.allPermissionsGranted) {
            PermissionRequestContent(
                onRequestPermissions = { locationPermissions.launchMultiplePermissionRequest() }
            )
        } else {
            // Top Bar with Status
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Road Monitor",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )

                        Button(
                            onClick = {
                                if (isTracking) {
                                    viewModel.stopTracking()
                                } else {
                                    viewModel.startTracking(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isTracking) Color.Red else Color.Green
                            )
                        ) {
                            Text(if (isTracking) "STOP" else "START")
                        }
                    }

                    // FIXED: Add location status indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (currentLocation != null) {
                                "📍 GPS: %.6f, %.6f".format(
                                    currentLocation!!.latitude,
                                    currentLocation!!.longitude
                                )
                            } else {
                                "📍 GPS: Searching..."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )

                        Text(
                            text = if (currentLocation?.hasAccuracy() == true) {
                                "±${currentLocation!!.accuracy.toInt()}m"
                            } else {
                                "No Accuracy"
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Location Status Card - FIXED: Add detailed location info
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Location Status",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (currentLocation != null) {
                            val loc = currentLocation!!
                            Text("📍 Latitude: ${loc.latitude}")
                            Text("📍 Longitude: ${loc.longitude}")
                            if (loc.hasAccuracy()) {
                                Text("🎯 Accuracy: ±${loc.accuracy.toInt()}m")
                            }
                            if (loc.hasSpeed()) {
                                Text("🚗 GPS Speed: %.1f km/h".format(loc.speed * 3.6f))
                            }
                            Text("📊 Sensor Speed: %.1f km/h".format(sensorData.speed))
                            Text("⏰ Last Update: ${System.currentTimeMillis() - loc.time}ms ago")
                        } else {
                            Text(
                                "🔍 Searching for GPS signal...",
                                color = Color.Red
                            )
                        }

                        // Manual location request button
                        Button(
                            onClick = { viewModel.requestLocationUpdate() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🔄 Refresh Location")
                        }
                    }
                }

                // Sensor Data Graphs
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Accelerometer Data",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Z-Axis Graph (Reoriented)
                        Text("Z-Axis (Reoriented)", fontSize = 14.sp)
                        AccelerometerGraph(
                            data = accelerometerHistory.map { it.reorientedZ },
                            color = Color.Blue,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Current Values
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Speed", fontSize = 12.sp, color = Color.Gray)
                                Text(
                                    "%.1f km/h".format(sensorData.speed),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Z-Axis", fontSize = 12.sp, color = Color.Gray)
                                Text(
                                    "%.3f g".format(sensorData.reorientedZ),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Events", fontSize = 12.sp, color = Color.Gray)
                                Text(
                                    "${detectedEvents.size}",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Detected Events List
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Detected Events",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )

                            if (detectedEvents.isNotEmpty()) {
                                TextButton(
                                    onClick = { viewModel.clearEvents() }
                                ) {
                                    Text("Clear", color = Color.Red)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (detectedEvents.isEmpty()) {
                            Text(
                                "No events detected yet",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        } else {
                            detectedEvents.takeLast(5).reversed().forEach { event ->
                                EventCard(event = event)
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                }

                // Map View - FIXED: Better handling of null location
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .padding(8.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    if (currentLocation != null) {
                        MapView(
                            currentLocation = currentLocation!!,
                            detectedEvents = detectedEvents
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "🗺️ Waiting for GPS location...",
                                fontSize = 16.sp,
                                color = Color.Gray
                            )
                            Text(
                                "Make sure you're outside with clear sky view",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }

    // Event Confirmation Dialog
    if (showEventDialog && pendingEvent != null) {
        EventConfirmationDialog(
            event = pendingEvent!!,
            onConfirm = { viewModel.confirmEvent(true) },
            onDeny = { viewModel.confirmEvent(false) }
        )
    }
}

@Composable
fun AccelerometerGraph(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas

        val width = size.width
        val height = size.height
        val maxPoints = 300
        val displayData = data.takeLast(maxPoints)

        if (displayData.isEmpty()) return@Canvas

        val maxValue = displayData.maxOfOrNull { abs(it) } ?: 1f
        val minValue = -maxValue
        val range = maxValue - minValue

        // Draw grid
        val gridColor = Color.Gray.copy(alpha = 0.2f)
        for (i in 0..4) {
            val y = height * i / 4
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = 1f
            )
        }

        // Draw zero line
        val zeroY = height / 2
        drawLine(
            color = Color.Gray.copy(alpha = 0.5f),
            start = Offset(0f, zeroY),
            end = Offset(width, zeroY),
            strokeWidth = 2f
        )

        // Draw data
        val path = Path()
        displayData.forEachIndexed { index, value ->
            val x = width * index / (maxPoints - 1)
            val normalizedValue = (value - minValue) / range
            val y = height * (1 - normalizedValue)

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2f)
        )

        // Draw axis labels
        drawContext.canvas.nativeCanvas.apply {
            val paint = Paint().apply {
                textSize = 24f
                this.color = Color.Gray.toArgb()
            }
            drawText("%.2fg".format(maxValue), 10f, 30f, paint)
            drawText("%.2fg".format(minValue), 10f, height - 10f, paint)
        }
    }
}

@Composable
fun EventCard(event: RoadEvent) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = when (event.type) {
            EventType.SPEED_BREAKER -> Color(0xFFFFF3E0)
            EventType.POTHOLE -> Color(0xFFFFEBEE)
            EventType.BROKEN_PATCH -> Color(0xFFFCE4EC)
            else -> Color.LightGray
        },
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = when (event.type) {
                        EventType.SPEED_BREAKER -> "🚧 Speed Breaker"
                        EventType.POTHOLE -> "🕳️ Pothole"
                        EventType.BROKEN_PATCH -> "⚠️ Broken Patch"
                        else -> "Road Event"
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Conf: ${(event.confidence * 100).toInt()}% • Speed: ${
                        "%.1f".format(event.speed)
                    } km/h",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            Text(
                text = "%.6f, %.6f".format(event.latitude, event.longitude),
                fontSize = 10.sp,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun EventConfirmationDialog(
    event: RoadEvent,
    onConfirm: () -> Unit,
    onDeny: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDeny,
        icon = {
            Text(
                text = when (event.type) {
                    EventType.SPEED_BREAKER -> "🚧"
                    EventType.POTHOLE -> "🕳️"
                    EventType.BROKEN_PATCH -> "⚠️"
                    else -> "❓"
                },
                fontSize = 48.sp
            )
        },
        title = {
            Text(
                text = "Road Event Detected!",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    text = when (event.type) {
                        EventType.SPEED_BREAKER -> "Speed Breaker Detected"
                        EventType.POTHOLE -> "Pothole Detected"
                        EventType.BROKEN_PATCH -> "Broken Road Patch Detected"
                        else -> "Unknown Event"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text("Location: %.6f, %.6f".format(event.latitude, event.longitude))
                Text("Confidence: ${(event.confidence * 100).toInt()}%")
                Text("Speed: %.1f km/h".format(event.speed))

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Is this detection correct?",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
            ) {
                Text("YES")
            }
        },
        dismissButton = {
            Button(
                onClick = onDeny,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("NO")
            }
        }
    )
}

@Composable
fun MapView(
    currentLocation: Location,
    detectedEvents: List<RoadEvent>
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            Configuration.getInstance()
                .load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(18.0) // Higher zoom for better detail
                setUseDataConnection(true)
            }
        },
        update = { map ->
            map.overlays.clear()

            // Add current location marker
            val geoPoint = GeoPoint(currentLocation.latitude, currentLocation.longitude)
            val currentMarker = Marker(map).apply {
                position = geoPoint
                title = "Current Location"
                snippet = "Speed: %.1f km/h\nAccuracy: %.1fm".format(
                    currentLocation.speed * 3.6f,
                    currentLocation.accuracy
                )
                icon = ContextCompat.getDrawable(context, R.drawable.location)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            map.overlays.add(currentMarker)

            // Add event markers
            detectedEvents.forEach { event ->
                val eventGeoPoint = GeoPoint(event.latitude, event.longitude)
                val eventMarker = Marker(map).apply {
                    position = eventGeoPoint
                    title = when (event.type) {
                        EventType.SPEED_BREAKER -> "🚧 Speed Breaker"
                        EventType.POTHOLE -> "🕳️ Pothole"
                        EventType.BROKEN_PATCH -> "⚠️ Broken Patch"
                        else -> "Road Event"
                    }
                    snippet = "Confidence: ${(event.confidence * 100).toInt()}%\nSpeed: %.1f km/h".format(event.speed)
                    icon = ContextCompat.getDrawable(context, R.drawable.target)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                    // Show info window on click
                    setOnMarkerClickListener { marker, mapView ->
                        marker.showInfoWindow()
                        true
                    }
                }
                map.overlays.add(eventMarker)
            }

            // Center map on current location
            map.controller.animateTo(geoPoint)
            map.invalidate()
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun PermissionRequestContent(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Location Permission Required",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "This app needs location permission to track road conditions and your position.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text("Grant Permission")
        }
    }
}