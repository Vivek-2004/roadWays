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

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var sensorDataManager: SensorDataManager? = null
    private var vibrator: Vibrator? = null

    @RequiresApi(Build.VERSION_CODES.O)
    fun initialize(context: Context) {
        sensorDataManager = SensorDataManager(context)
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        // Observe sensor data
        viewModelScope.launch {
            sensorDataManager?.sensorData?.collect { reading ->
                _sensorData.value = reading

                Log.d("sensorData", reading.toString())

                // Update history (keep last 120 readings for 2 seconds at 60Hz)
                val history = _accelerometerHistory.value.toMutableList()
                history.add(reading)
                if (history.size > 120) {
                    history.removeAt(0)
                }
                _accelerometerHistory.value = history
            }
        }

        // Observe detected events
        viewModelScope.launch {
            sensorDataManager?.detectedEvents?.collect { events ->
                val newEvents = events.filter { event ->
                    !_detectedEvents.value.any {
                        it.timestamp == event.timestamp && it.type == event.type
                    }
                }

                if (newEvents.isNotEmpty()) {
                    val latestEvent = newEvents.last()
                    _pendingEvent.value = latestEvent
                    _showEventDialog.value = true

                    // Vibrate to alert user
                    vibrator?.vibrate(
                        VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)
                    )

                    // Auto-dismiss dialog after 5 seconds if no response
                    viewModelScope.launch {
                        delay(5000)
                        if (_showEventDialog.value && _pendingEvent.value == latestEvent) {
                            confirmEvent(false)
                        }
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
                    }
                }
            }
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // Update every second
        ).apply {
            setMinUpdateDistanceMeters(1f) // Update every meter
            setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            setWaitForAccurateLocation(false)
        }.build()

        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )

        // Start sensor collection
        sensorDataManager?.startSensorCollection()

        _isTracking.value = true

        // Get initial location
        fusedLocationClient?.lastLocation?.addOnSuccessListener { location ->
            location?.let {
                viewModelScope.launch {
                    _currentLocation.value = it
                    sensorDataManager?.updateLocation(it)
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
    }

    fun confirmEvent(isCorrect: Boolean) {
        _pendingEvent.value?.let { event ->
            if (isCorrect) {
                // Add to confirmed events
                _detectedEvents.value = _detectedEvents.value + event.copy(
                    confidence = 1.0f // Max confidence for user-confirmed events
                )
            }
        }
        _showEventDialog.value = false
        _pendingEvent.value = null
    }

    fun clearEvents() {
        _detectedEvents.value = emptyList()
        sensorDataManager?.clearDetectedEvents()
    }

    override fun onCleared() {
        super.onCleared()
        stopTracking()
    }
}