package com.drive.road

import android.Manifest
import android.content.Context
import android.location.Location
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val coroutineScope = rememberCoroutineScope()
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
            viewModel.stopLocationTracking()
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
                    currentLocation = currentLocation
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
                            coroutineScope.launch {
                                viewModel.startLocationTracking(context)
                                delay(3000)
                                viewModel.stopLocationTracking()
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.location),
                            contentDescription = "Current Location"
                        )
                    }

                    FloatingActionButton(
                        onClick = {
                            if (isTracking) {
                                viewModel.stopLocationTracking()
                            } else {
                                viewModel.startLocationTracking(context)
                            }
                        }
                    ) {
                        Text(
                            text = if (isTracking) {
                                "STOP"
                            } else {
                                "START"
                            }
                        )
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
    locationPoints: List<Location> = listOf<Location>(
        Location("test").apply {
            latitude = 23.5179
            longitude = 87.3779
        },
        Location("test").apply {
            latitude = 23.5146
            longitude = 87.3772
        },
        Location("test").apply {
            latitude = 23.5170
            longitude = 87.3760
        },
    ),
) {
    val context = LocalContext.current
    var mapView by remember { mutableStateOf<MapView?>(null) }

    AndroidView(
        factory = { ctx ->
            Configuration.getInstance()
                .load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
                mapView = this

                setUseDataConnection(true)
            }
        },
        update = { map ->
            // Clear all overlays first
            map.overlays.clear()

            // Create markers for all location points
            locationPoints.forEach { loc ->
                val geoPoint = GeoPoint(loc.latitude, loc.longitude)

                val marker = Marker(map).apply {
                    position = geoPoint
                    title = "Lat: ${loc.latitude}, Lng: ${loc.longitude}"
                    icon = ContextCompat.getDrawable(context, R.drawable.target)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { marker, mapView ->
                        marker.showInfoWindow()
                        true
                    }
                }

                // Add marker to map overlays
                map.overlays.add(marker)
            }

            // Add current location marker
            currentLocation?.let { location ->
                val currentGeoPoint = GeoPoint(location.latitude, location.longitude)
                val currentMarker = Marker(map).apply {
                    position = currentGeoPoint
                    title = "Current Location"
                    icon = ContextCompat.getDrawable(context, R.drawable.location)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                map.overlays.add(currentMarker)
            }

            // Center and zoom map
            if (locationPoints.isNotEmpty() || currentLocation != null) {
                val centerGeoPoint = currentLocation?.let {
                    GeoPoint(it.latitude, it.longitude)
                } ?: locationPoints.firstOrNull()?.let {
                    GeoPoint(it.latitude, it.longitude)
                } ?: GeoPoint(0.0, 0.0)

                map.controller.setZoom(18.0)
                map.controller.animateTo(centerGeoPoint)
            }

            // Force map to refresh and show overlays
            map.invalidate()
        },
        modifier = Modifier.fillMaxSize()
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
            Text("Latitude: ${location.latitude}")
            Text("Longitude: ${location.longitude}")
            Text("Accuracy: ${location.accuracy} m")
            if (location.hasSpeed()) {
                Text("Speed: ${location.speed * 3.6} km/h")
            }
        }
    }
}