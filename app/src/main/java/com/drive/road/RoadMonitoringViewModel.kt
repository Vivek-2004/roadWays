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

    // Phase tracking for debugging
    private val _phase1Candidates = MutableStateFlow<List<RoadEvent>>(emptyList())
    val phase1Candidates: StateFlow<List<RoadEvent>> = _phase1Candidates.asStateFlow()

    private val _currentThresholds = MutableStateFlow(Pair(0f, 0f))
    val currentThresholds: StateFlow<Pair<Float, Float>> = _currentThresholds.asStateFlow()

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var sensorDataManager: SensorDataManager? = null
    private var vibrator: Vibrator? = null

    @RequiresApi(Build.VERSION_CODES.O)
    fun initialize(context: Context) {
        sensorDataManager = SensorDataManager(context)
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Observe sensor data with proper history management
        viewModelScope.launch {
            sensorDataManager?.sensorData?.collect { reading ->
                _sensorData.value = reading

                // Update history (keep last 900 readings for 6 seconds at 150Hz as per paper)
                val history = _accelerometerHistory.value.toMutableList()
                history.add(reading)
                if (history.size > 900) { // 6 seconds * 150Hz
                    history.removeAt(0)
                }
                _accelerometerHistory.value = history

                // Log sensor data for debugging
                Log.d("SensorData", "Z: ${reading.reorientedZ}, Speed: ${reading.speed}")
            }
        }

        // Observe detected events from Phase 2 classification
        viewModelScope.launch {
            sensorDataManager?.detectedEvents?.collect { events ->
                // Find new events that haven't been processed yet
                val currentEvents = _detectedEvents.value
                val newEvents = events.filter { newEvent ->
                    !currentEvents.any { existing ->
                        abs(existing.timestamp - newEvent.timestamp) < 1000 && // Within 1 second
                                existing.type == newEvent.type
                    }
                }

                if (newEvents.isNotEmpty()) {
                    val latestEvent = newEvents.last()

                    // Only show confirmation dialog for high-confidence events
                    if (latestEvent.confidence > 0.7f) {
                        _pendingEvent.value = latestEvent
                        _showEventDialog.value = true

                        // Provide haptic feedback
                        vibrator?.let { v ->
                            when (latestEvent.type) {
                                EventType.SPEED_BREAKER -> {
                                    // Two short pulses for speed breaker
                                    v.vibrate(
                                        VibrationEffect.createWaveform(
                                            longArrayOf(0, 200, 100, 200), -1
                                        )
                                    )
                                }

                                EventType.POTHOLE -> {
                                    // One longer pulse for pothole
                                    v.vibrate(
                                        VibrationEffect.createOneShot(
                                            400,
                                            VibrationEffect.DEFAULT_AMPLITUDE
                                        )
                                    )
                                }

                                EventType.BROKEN_PATCH -> {
                                    // Three pulses for broken patch
                                    v.vibrate(
                                        VibrationEffect.createWaveform(
                                            longArrayOf(0, 100, 50, 100, 50, 100), -1
                                        )
                                    )
                                }

                                else -> {
                                    v.vibrate(
                                        VibrationEffect.createOneShot(
                                            200,
                                            VibrationEffect.DEFAULT_AMPLITUDE
                                        )
                                    )
                                }
                            }
                        }

                        // Auto-dismiss dialog after 5 seconds with automatic acceptance
                        viewModelScope.launch {
                            delay(5000)
                            if (_showEventDialog.value && _pendingEvent.value == latestEvent) {
                                confirmEvent(true) // Auto-accept after timeout
                            }
                        }
                    } else {
                        // Automatically accept low-confidence events without user intervention
                        _detectedEvents.value = _detectedEvents.value + latestEvent
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking(context: Context) {
        if (_isTracking.value) return

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    viewModelScope.launch {
                        _currentLocation.value = location
                        sensorDataManager?.updateLocation(location)

                        Log.d(
                            "Location",
                            "Lat: ${location.latitude}, Lng: ${location.longitude}, Speed: ${location.speed * 3.6f} km/h"
                        )
                    }
                }
            }
        }

        // High accuracy location request as per paper requirements
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // Update every second
        ).apply {
            setMinUpdateDistanceMeters(0.5f) // Update every 0.5 meters
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(false)
        }.build()

        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )

        // Start sensor collection at 150Hz as mentioned in paper
        sensorDataManager?.startSensorCollection()

        _isTracking.value = true

        // Get initial location immediately
        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
            location?.let {
                viewModelScope.launch {
                    _currentLocation.value = it
                    sensorDataManager?.updateLocation(it)
                }
            }
        }

        Log.d("Tracking", "Started road monitoring with 150Hz sampling")
    }

    fun stopTracking() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
        sensorDataManager?.stopSensorCollection()
        _isTracking.value = false

        Log.d("Tracking", "Stopped road monitoring")
    }

    fun confirmEvent(isCorrect: Boolean) {
        _pendingEvent.value?.let { event ->
            if (isCorrect) {
                // Add to confirmed events with maximum confidence
                val confirmedEvent = event.copy(
                    confidence = 1.0f // User confirmation gives maximum confidence
                )
                _detectedEvents.value = _detectedEvents.value + confirmedEvent

                Log.d(
                    "EventConfirm",
                    "User confirmed: ${event.type} at ${event.latitude}, ${event.longitude}"
                )
            } else {
                Log.d("EventConfirm", "User rejected: ${event.type}")
            }
        }
        _showEventDialog.value = false
        _pendingEvent.value = null
    }

    fun clearEvents() {
        _detectedEvents.value = emptyList()
        _phase1Candidates.value = emptyList()
        sensorDataManager?.clearDetectedEvents()

        Log.d("Events", "Cleared all detected events")
    }

    // Configuration methods for different vehicle types and placements
    fun setVehicleConfiguration(vehicleType: VehicleType, phonePlacement: PhonePlacement) {
        sensorDataManager?.setVehicleType(vehicleType)
        sensorDataManager?.setPhonePlacement(phonePlacement)

        Log.d("Config", "Set vehicle: $vehicleType, placement: $phonePlacement")
    }

    override fun onCleared() {
        super.onCleared()
        stopTracking()
    }

    private fun abs(value: Long): Long = if (value < 0) -value else value
}