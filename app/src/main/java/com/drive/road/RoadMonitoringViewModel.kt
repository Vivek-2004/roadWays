package com.drive.road

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RoadMonitoringViewModel : ViewModel() {
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _sensorData = MutableStateFlow(SensorReading())
    val sensorData: StateFlow<SensorReading> = _sensorData.asStateFlow()

    private val _accelerometerHistory = MutableStateFlow<List<SensorReading>>(emptyList())
    val accelerometerHistory: StateFlow<List<SensorReading>> = _accelerometerHistory.asStateFlow()

    private val _detectedEvents = MutableStateFlow<List<RoadEvent>>(emptyList())
    val detectedEvents: StateFlow<List<RoadEvent>> = _detectedEvents.asStateFlow()

    private val _showEventDialog = MutableStateFlow(false)
    val showEventDialog: StateFlow<Boolean> = _showEventDialog.asStateFlow()

    private val _pendingEvent = MutableStateFlow<RoadEvent?>(null)
    val pendingEvent: StateFlow<RoadEvent?> = _pendingEvent.asStateFlow()

    // CHANGE 1: Add new StateFlow for current thresholds
    private val _currentThresholds = MutableStateFlow(Pair(0f, 0f))
    val currentThresholds: StateFlow<Pair<Float, Float>> = _currentThresholds.asStateFlow()

    // CHANGE 2: Add StateFlow for system status monitoring
    private val _systemStatus = MutableStateFlow<Map<String, Any>>(emptyMap())
    val systemStatus: StateFlow<Map<String, Any>> = _systemStatus.asStateFlow()

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var sensorDataManager: SensorDataManager? = null
    private var vibrator: Vibrator? = null

    @RequiresApi(Build.VERSION_CODES.O)
    fun initialize(context: Context) {
        sensorDataManager = SensorDataManager(context)
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        // CHANGE 3: Enhanced sensor data observation
        viewModelScope.launch {
            sensorDataManager?.sensorData?.collect { reading ->
                _sensorData.value = reading

                // Update history with proper memory management
                val history = _accelerometerHistory.value.toMutableList()
                history.add(reading)
                if (history.size > 900) { // Keep last 6 seconds at 150Hz
                    history.removeAt(0)
                }
                _accelerometerHistory.value = history

                // CHANGE 4: Update current thresholds from SensorDataManager
                val thresholds = sensorDataManager?.getCurrentThresholds() ?: Pair(0f, 0f)
                _currentThresholds.value = thresholds

                // CHANGE 5: Update system status periodically
                if (System.currentTimeMillis() % 1000 < 100) { // Update every ~1 second
                    _systemStatus.value = sensorDataManager?.getSystemStatus() ?: emptyMap()
                }

                // Enhanced logging with threshold info
                Log.d(
                    "SensorData",
                    "Z: %.3f, Speed: %.1f km/h, Thresholds: SB=%.3f, PH=%.3f, Lat: %f, Lng: %f".format(
                        reading.reorientedZ,
                        reading.speed,
                        thresholds.first,
                        thresholds.second,
                        reading.latitude,
                        reading.longitude
                    )
                )
            }
        }

        // CHANGE 6: Enhanced event detection observation
        viewModelScope.launch {
            sensorDataManager?.detectedEvents?.collect { events ->
                val currentEvents = _detectedEvents.value
                val newEvents = events.filter { newEvent ->
                    !currentEvents.any { existing ->
                        abs(existing.timestamp - newEvent.timestamp) < 1000 &&
                                existing.type == newEvent.type &&
                                calculateDistance(existing, newEvent) < 10.0 // Within 10m
                    }
                }

                if (newEvents.isNotEmpty()) {
                    val latestEvent = newEvents.last()

                    // CHANGE 7: Enhanced confidence-based dialog logic
                    if (latestEvent.confidence > 0.7f) {
                        _pendingEvent.value = latestEvent
                        _showEventDialog.value = true

                        // Enhanced haptic feedback based on event type and confidence
                        provideTactileFeedback(latestEvent)

                        // CHANGE 8: Dynamic auto-dismiss based on confidence
                        val dismissTime = when {
                            latestEvent.confidence > 0.9f -> 3000L // High confidence - 3s
                            latestEvent.confidence > 0.8f -> 4000L // Good confidence - 4s
                            else -> 5000L // Lower confidence - 5s
                        }

                        viewModelScope.launch {
                            delay(dismissTime)
                            if (_showEventDialog.value && _pendingEvent.value == latestEvent) {
                                confirmEvent(true) // Auto-accept after timeout
                            }
                        }
                    } else if (latestEvent.confidence > 0.5f) {
                        // CHANGE 9: Auto-accept medium confidence events
                        _detectedEvents.value = _detectedEvents.value + latestEvent
                        Log.d(
                            "EventAuto",
                            "Auto-accepted: ${latestEvent.type} with confidence ${latestEvent.confidence}"
                        )
                    }
                    // Events below 0.5 confidence are ignored
                }
            }
        }
    }

    // CHANGE 10: Enhanced location tracking with better configuration
    @SuppressLint("MissingPermission")
    fun startTracking(context: Context) {
        if (_isTracking.value) return

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    // CHANGE 11: Enhanced location validation
                    if (isLocationValid(location)) {
                        viewModelScope.launch {
                            _currentLocation.value = location
                            sensorDataManager?.updateLocation(location)

                            Log.d(
                                "Location",
                                "Valid location: Lat=%.6f, Lng=%.6f, Speed=%.1f km/h, Accuracy=%.1f m".format(
                                    location.latitude,
                                    location.longitude,
                                    location.speed * 3.6f,
                                    location.accuracy
                                )
                            )
                        }
                    } else {
                        Log.w(
                            "Location",
                            "Invalid location rejected: Accuracy=${location.accuracy}m"
                        )
                    }
                }
            }
        }

        // CHANGE 12: More aggressive location request configuration
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            500L // Update every 500ms
        ).apply {
            setMinUpdateDistanceMeters(0f) // Update on any movement
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(false)
            setMaxUpdateDelayMillis(1000) // Maximum 1s delay
            setMinUpdateIntervalMillis(200) // Minimum 200ms between updates
        }.build()

        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )

        // Start sensor collection
        sensorDataManager?.startSensorCollection()
        _isTracking.value = true

        // CHANGE 13: Enhanced initial location handling
        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
            location?.let {
                if (isLocationValid(it)) {
                    viewModelScope.launch {
                        _currentLocation.value = it
                        sensorDataManager?.updateLocation(it)
                        Log.d(
                            "Location",
                            "Initial valid location set: %.6f, %.6f".format(
                                it.latitude,
                                it.longitude
                            )
                        )
                    }
                }
            }
        }?.addOnFailureListener { exception ->
            Log.e("Location", "Failed to get last known location", exception)
        }

        Log.d("Tracking", "Started enhanced road monitoring with 500ms location updates")
    }

    // CHANGE 14: Add location validation method
    private fun isLocationValid(location: Location): Boolean {
        return location.hasAccuracy() &&
                location.accuracy < 20f && // Accept up to 20m accuracy
                location.latitude != 0.0 &&
                location.longitude != 0.0 &&
                kotlin.math.abs(location.latitude) <= 90.0 &&
                kotlin.math.abs(location.longitude) <= 180.0
    }

    // CHANGE 15: Add distance calculation for event deduplication
    private fun calculateDistance(event1: RoadEvent, event2: RoadEvent): Double {
        val location1 = Location("").apply {
            latitude = event1.latitude
            longitude = event1.longitude
        }
        val location2 = Location("").apply {
            latitude = event2.latitude
            longitude = event2.longitude
        }
        return location1.distanceTo(location2).toDouble()
    }

    // CHANGE 16: Enhanced tactile feedback
    @RequiresApi(Build.VERSION_CODES.O)
    private fun provideTactileFeedback(event: RoadEvent) {
        vibrator?.let { v ->
            val intensity = when {
                event.confidence > 0.9f -> VibrationEffect.DEFAULT_AMPLITUDE
                event.confidence > 0.8f -> (VibrationEffect.DEFAULT_AMPLITUDE * 0.8f).toInt()
                else -> (VibrationEffect.DEFAULT_AMPLITUDE * 0.6f).toInt()
            }

            when (event.type) {
                EventType.SPEED_BREAKER -> {
                    // Two pulses with intensity based on confidence
                    v.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 150, 80, 150),
                            intArrayOf(0, intensity, 0, intensity),
                            -1
                        )
                    )
                }

                EventType.POTHOLE -> {
                    // Single longer pulse
                    v.vibrate(
                        VibrationEffect.createOneShot(300, intensity)
                    )
                }

                EventType.BROKEN_PATCH -> {
                    // Three short pulses
                    v.vibrate(
                        VibrationEffect.createWaveform(
                            longArrayOf(0, 80, 40, 80, 40, 80),
                            intArrayOf(0, intensity, 0, intensity, 0, intensity),
                            -1
                        )
                    )
                }

                else -> {
                    v.vibrate(VibrationEffect.createOneShot(200, intensity))
                }
            }
        }
    }

    fun stopTracking() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
        sensorDataManager?.stopSensorCollection()
        _isTracking.value = false
        Log.d("Tracking", "Stopped road monitoring")
    }

    // CHANGE 17: Add manual location refresh method
    @SuppressLint("MissingPermission")
    fun requestLocationUpdate() {
        if (!_isTracking.value) return

        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
            location?.let {
                if (isLocationValid(it)) {
                    viewModelScope.launch {
                        _currentLocation.value = it
                        sensorDataManager?.updateLocation(it)
                        Log.d(
                            "Location",
                            "Manual location update: %.6f, %.6f".format(it.latitude, it.longitude)
                        )
                    }
                }
            }
        }
    }

    fun confirmEvent(isCorrect: Boolean) {
        _pendingEvent.value?.let { event ->
            if (isCorrect) {
                val confirmedEvent = event.copy(confidence = 1.0f)
                _detectedEvents.value = _detectedEvents.value + confirmedEvent
                Log.d(
                    "EventConfirm",
                    "User confirmed: ${event.type} at %.6f, %.6f".format(
                        event.latitude,
                        event.longitude
                    )
                )
            } else {
                Log.d(
                    "EventConfirm",
                    "User rejected: ${event.type} with confidence ${event.confidence}"
                )
            }
        }
        _showEventDialog.value = false
        _pendingEvent.value = null
    }

    fun clearEvents() {
        _detectedEvents.value = emptyList()
        sensorDataManager?.clearDetectedEvents()
        Log.d("Events", "Cleared all detected events")
    }

    fun setVehicleConfiguration(vehicleType: VehicleType, phonePlacement: PhonePlacement) {
        sensorDataManager?.setVehicleType(vehicleType)
        sensorDataManager?.setPhonePlacement(phonePlacement)
        Log.d("Config", "Set vehicle: $vehicleType, placement: $phonePlacement")
    }

    // CHANGE 18: Add debugging and monitoring methods
    fun getLocationStatus(): Map<String, Any> {
        val currentLoc = _currentLocation.value
        return mapOf(
            "hasLocation" to (currentLoc != null),
            "latitude" to (currentLoc?.latitude ?: 0.0),
            "longitude" to (currentLoc?.longitude ?: 0.0),
            "accuracy" to (currentLoc?.accuracy ?: Float.MAX_VALUE),
            "speed" to (currentLoc?.speed?.times(3.6f) ?: 0f),
            "isTracking" to _isTracking.value,
            "hasLocationCallback" to (locationCallback != null),
            "hasFusedClient" to (fusedLocationClient != null),
            "lastUpdateTime" to (currentLoc?.time ?: 0L),
            "timeSinceUpdate" to if (currentLoc != null) {
                System.currentTimeMillis() - currentLoc.time
            } else Long.MAX_VALUE
        )
    }

    // CHANGE 19: Add method to get current detection statistics
    fun getDetectionStats(): Map<String, Any> {
        val events = _detectedEvents.value
        return mapOf(
            "totalEvents" to events.size,
            "speedBreakers" to events.count { it.type == EventType.SPEED_BREAKER },
            "potholes" to events.count { it.type == EventType.POTHOLE },
            "brokenPatches" to events.count { it.type == EventType.BROKEN_PATCH },
            "avgConfidence" to if (events.isNotEmpty()) {
                events.map { it.confidence }.average()
            } else 0.0,
            "recentEvents" to events.filter {
                System.currentTimeMillis() - it.timestamp < 300000 // Last 5 minutes
            }.size
        )
    }

    // CHANGE 20: Add method to force system status update
    fun updateSystemStatus() {
        viewModelScope.launch {
            _systemStatus.value = sensorDataManager?.getSystemStatus() ?: emptyMap()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTracking()
        Log.d("ViewModel", "RoadMonitoringViewModel cleared")
    }

    private fun abs(value: Long): Long = if (value < 0) -value else value
}