package com.drive.road

import android.Manifest
import android.content.Context
import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LocationTrackingScreen(
    viewModel: LocationViewModel = viewModel()
) {
    val context = LocalContext.current
    val currentLocation by viewModel.currentLocation.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()

    // Request location permissions
    val locationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    )

    LaunchedEffect(locationPermissions.allPermissionsGranted) {
        if (locationPermissions.allPermissionsGranted) {
            viewModel.startLocationTracking(context)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Permission handling
        if (!locationPermissions.allPermissionsGranted) {
            PermissionRequestScreen(
                onRequestPermissions = { locationPermissions.launchMultiplePermissionRequest() }
            )
        } else {
            // Map and controls
            Box(modifier = Modifier.weight(1f)) {
                OSMMapView(
                    currentLocation = currentLocation,
                    modifier = Modifier.fillMaxSize()
                )

                // Floating action buttons
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            if (isTracking) {
                                viewModel.stopLocationTracking()
                            } else {
                                viewModel.startLocationTracking(context)
                            }
                        }
                    ) {
                        Text(if (isTracking) "Stop" else "Start")
                    }
                }
            }

            // Location info card
            currentLocation?.let { location ->
                LocationInfoCard(location = location)
            }
        }
    }
}

@Composable
fun PermissionRequestScreen(onRequestPermissions: () -> Unit) {
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
            text = "This app needs location permission to track your position on the map.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermissions) {
            Text("Grant Permission")
        }
    }
}

@Composable
fun OSMMapView(
    currentLocation: Location?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var mapView by remember { mutableStateOf<MapView?>(null) }
    var currentMarker by remember { mutableStateOf<Marker?>(null) }

    AndroidView(
        factory = { ctx ->
            // Initialize OSMDroid configuration
            Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)

                // Set initial location (default to a known location)
                val initialPoint = GeoPoint(37.7749, -122.4194) // San Francisco
                controller.setCenter(initialPoint)

                mapView = this
            }
        },
        update = { map ->
            currentLocation?.let { location ->
                val geoPoint = GeoPoint(location.latitude, location.longitude)

                // Remove previous marker
                currentMarker?.let { marker ->
                    map.overlays.remove(marker)
                }

                // Add new marker for current location
                val marker = Marker(map).apply {
                    position = geoPoint
                    title = "Your Location"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }

                map.overlays.add(marker)
                currentMarker = marker

                // Center map on current location
                map.controller.animateTo(geoPoint)
                map.invalidate()
            }
        },
        modifier = modifier
    )
}

@Composable
fun LocationInfoCard(location: Location) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Current Location",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Latitude: ${String.format("%.6f", location.latitude)}")
            Text("Longitude: ${String.format("%.6f", location.longitude)}")
            Text("Accuracy: ${String.format("%.1f", location.accuracy)}m")
            if (location.hasSpeed()) {
                Text("Speed: ${String.format("%.1f", location.speed * 3.6)} km/h")
            }
        }
    }
}