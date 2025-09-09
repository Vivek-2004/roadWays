package com.drive.road

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

data class SensorReading(
    val timestamp: Long = System.currentTimeMillis(),
    val accelerometerX: Float = 0f,
    val accelerometerY: Float = 0f,
    val accelerometerZ: Float = 0f,
    val reorientedZ: Float = 0f,
    val gyroscopeX: Float = 0f,
    val gyroscopeY: Float = 0f,
    val gyroscopeZ: Float = 0f,
    val speed: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

data class RoadEvent(
    val type: EventType,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val confidence: Float,
    val zAxisValue: Float,
    val speed: Float
)

enum class EventType {
    SPEED_BREAKER,
    POTHOLE,
    BROKEN_PATCH,
    NORMAL
}

class SensorDataManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _sensorData = MutableStateFlow(SensorReading())
    val sensorData: StateFlow<SensorReading> = _sensorData.asStateFlow()

    private val _detectedEvents = MutableStateFlow<List<RoadEvent>>(emptyList())
    val detectedEvents: StateFlow<List<RoadEvent>> = _detectedEvents.asStateFlow()

    // Auto-threshold parameters (from the paper formula)
    private var T0_speedBreaker = 1.8f  // Initial threshold for speed breaker (g units)
    private var T0_pothole = 0.714f     // Initial threshold for pothole (g units)
    private val B = 20f                 // Base point (km/h)
    private val L = 20f                 // Lower limit (km/h)
    private val S = 0.015f              // Scaling factor

    // Moving average for speed
    private val speedHistory = mutableListOf<Float>()
    private val maxHistorySize = 10

    // Event detection variables
    private var lastEventTime = 0L
    private val minTimeBetweenEvents = 500L // milliseconds
    private val eventWindow = mutableListOf<SensorReading>()
    private val windowSize = 30 // 0.5 seconds at 60Hz

    // Gravity filter components
    private val gravity = FloatArray(3)
    private val alpha = 0.8f

    // Current location
    private var currentLocation: Location? = null

    fun startSensorCollection() {
        // Register sensors at 60Hz (16666 microseconds)
        sensorManager.registerListener(this, accelerometer, 16666)
        sensorManager.registerListener(this, gyroscope, 16666)
    }

    fun stopSensorCollection() {
        sensorManager.unregisterListener(this)
    }

    fun updateLocation(location: Location) {
        currentLocation = location
        val speedKmh = location.speed * 3.6f // Convert m/s to km/h
        updateSpeedHistory(speedKmh)
    }

    private fun updateSpeedHistory(speed: Float) {
        speedHistory.add(speed)
        if (speedHistory.size > maxHistorySize) {
            speedHistory.removeAt(0)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Apply low-pass filter to remove gravity
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

                val linearAccelX = event.values[0] - gravity[0]
                val linearAccelY = event.values[1] - gravity[1]
                val linearAccelZ = event.values[2] - gravity[2]

                // Auto-reorientation using Euler angles (from paper)
                val reorientedZ = autoReorient(linearAccelX, linearAccelY, linearAccelZ)

                val reading = _sensorData.value.copy(
                    timestamp = System.currentTimeMillis(),
                    accelerometerX = linearAccelX,
                    accelerometerY = linearAccelY,
                    accelerometerZ = linearAccelZ,
                    reorientedZ = reorientedZ,
                    speed = speedHistory.lastOrNull() ?: 0f,
                    latitude = currentLocation?.latitude ?: 0.0,
                    longitude = currentLocation?.longitude ?: 0.0
                )

                _sensorData.value = reading

                // Add to event window
                eventWindow.add(reading)
                if (eventWindow.size > windowSize) {
                    eventWindow.removeAt(0)
                }

                // Detect events
                detectRoadEvent(reading)
            }

            Sensor.TYPE_GYROSCOPE -> {
                _sensorData.value = _sensorData.value.copy(
                    gyroscopeX = event.values[0],
                    gyroscopeY = event.values[1],
                    gyroscopeZ = event.values[2]
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }

    private fun autoReorient(ax: Float, ay: Float, az: Float): Float {
        // Auto-orientation based on Euler angles (from paper Section 5.1.1)
        val theta = atan2(ay.toDouble(), az.toDouble())
        val beta = atan2(-ax.toDouble(), sqrt((ay * ay + az * az).toDouble()))

        // Calculate reoriented Z-axis acceleration
        val reorientedZ = (-ax * sin(beta) + ay * cos(beta) * sin(theta) + az * cos(beta) * cos(theta)).toFloat()

        return reorientedZ
    }

    private fun calculateDynamicThreshold(speed: Float, isSpeedBreaker: Boolean): Float {
        // Implement the formula from the paper: Tt = T0 + ((1/t * Σ Vi - L) × S) if Σ Vi > B, else T0
        val avgSpeed = if (speedHistory.isNotEmpty()) {
            speedHistory.average().toFloat()
        } else {
            speed
        }

        val T0 = if (isSpeedBreaker) T0_speedBreaker else T0_pothole

        return if (avgSpeed > B) {
            T0 + ((avgSpeed - L) * S)
        } else {
            T0
        }
    }

    private fun detectRoadEvent(reading: SensorReading) {
        val currentTime = System.currentTimeMillis()

        // Avoid detecting events too frequently
        if (currentTime - lastEventTime < minTimeBetweenEvents) {
            return
        }

        val zAxis = abs(reading.reorientedZ)
        val speedBreakerThreshold = calculateDynamicThreshold(reading.speed, true)
        val potholeThreshold = calculateDynamicThreshold(reading.speed, false)

        // Detect based on Z-axis patterns (from paper)
        val eventType = when {
            reading.reorientedZ > speedBreakerThreshold -> {
                // High -> Low pattern indicates speed breaker
                if (checkSpeedBreakerPattern()) EventType.SPEED_BREAKER else null
            }
            reading.reorientedZ < -potholeThreshold -> {
                // Low -> High pattern indicates pothole
                if (checkPotholePattern()) EventType.POTHOLE else null
            }
            checkBrokenPatchPattern() -> EventType.BROKEN_PATCH
            else -> null
        }

        eventType?.let { type ->
            val event = RoadEvent(
                type = type,
                latitude = reading.latitude,
                longitude = reading.longitude,
                timestamp = currentTime,
                confidence = calculateConfidence(type, zAxis),
                zAxisValue = reading.reorientedZ,
                speed = reading.speed
            )

            _detectedEvents.value = _detectedEvents.value + event
            lastEventTime = currentTime
        }
    }

    private fun checkSpeedBreakerPattern(): Boolean {
        if (eventWindow.size < 10) return false

        // Look for high->low pattern in Z-axis
        val recent = eventWindow.takeLast(10)
        val maxZ = recent.maxOf { it.reorientedZ }
        val minZ = recent.minOf { it.reorientedZ }
        val maxIndex = recent.indexOfFirst { it.reorientedZ == maxZ }
        val minIndex = recent.indexOfLast { it.reorientedZ == minZ }

        return maxIndex < minIndex && (maxZ - minZ) > 0.5f
    }

    private fun checkPotholePattern(): Boolean {
        if (eventWindow.size < 10) return false

        // Look for low->high pattern in Z-axis
        val recent = eventWindow.takeLast(10)
        val maxZ = recent.maxOf { it.reorientedZ }
        val minZ = recent.minOf { it.reorientedZ }
        val minIndex = recent.indexOfFirst { it.reorientedZ == minZ }
        val maxIndex = recent.indexOfLast { it.reorientedZ == maxZ }

        return minIndex < maxIndex && (maxZ - minZ) > 0.5f
    }

    private fun checkBrokenPatchPattern(): Boolean {
        if (eventWindow.size < windowSize) return false

        // Check for continuous high variation in Z-axis (broken patch signature)
        val variance = calculateVariance(eventWindow.map { it.reorientedZ })
        val avgSpeed = speedHistory.average()

        // Broken patch: high variance at low speed for extended time
        return variance > 0.3f && avgSpeed < 30f && avgSpeed > 5f
    }

    private fun calculateVariance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        return values.map { (it - mean) * (it - mean) }.average().toFloat()
    }

    private fun calculateConfidence(type: EventType, zAxisValue: Float): Float {
        // Simple confidence calculation based on how much the value exceeds threshold
        return when (type) {
            EventType.SPEED_BREAKER -> {
                val threshold = calculateDynamicThreshold(_sensorData.value.speed, true)
                min(1f, (zAxisValue / threshold - 1f) * 2f + 0.5f)
            }
            EventType.POTHOLE -> {
                val threshold = calculateDynamicThreshold(_sensorData.value.speed, false)
                min(1f, (abs(zAxisValue) / threshold - 1f) * 2f + 0.5f)
            }
            EventType.BROKEN_PATCH -> 0.7f // Default confidence for broken patches
            EventType.NORMAL -> 0f
        }
    }

    fun clearDetectedEvents() {
        _detectedEvents.value = emptyList()
    }
}