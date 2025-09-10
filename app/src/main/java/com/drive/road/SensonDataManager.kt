package com.drive.road

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

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
    val speed: Float,
    val features: EventFeatures? = null
)

data class EventFeatures(
    val zt: Float,           // Z-axis value at detection
    val zNext: Float?,       // Next local extrema
    val zPrev: Float?,       // Previous local extrema
    val timeSinceLastEvent: Long, // Tp - time between events
    val instantaneousSpeed: Float // Sp - speed at detection
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

    // Auto-threshold parameters (from paper Section 5.1.2)
    private var T0_speedBreaker = 1.8f  // Initial threshold for 2-wheeler speed breaker
    private var T0_pothole = 0.714f     // Initial threshold for 2-wheeler pothole
    private val B = 20f                 // Base point (km/h)
    private val L = 20f                 // Lower limit (km/h)
    private val S = 0.015f              // Scaling factor

    // Moving average for speed
    private val speedHistory = mutableListOf<Float>()
    private val maxSpeedHistorySize = 10

    // Event detection history
    private val zAxisHistory = mutableListOf<Pair<Long, Float>>()
    private val maxZHistorySize = 450 // 3 seconds at 150Hz
    private var lastEventTime = 0L
    private val minTimeBetweenEvents = 500L // milliseconds
    private val deltaTimeWindow = 1000L // 1 second window for finding extrema

    // Gravity filter
    private val gravity = FloatArray(3)
    private val alpha = 0.8f

    // Current location
    private var currentLocation: Location? = null

    fun startSensorCollection() {
        sensorManager.registerListener(this, accelerometer, 16666) // 60Hz
        sensorManager.registerListener(this, gyroscope, 16666)
    }

    fun stopSensorCollection() {
        sensorManager.unregisterListener(this)
    }

    fun updateLocation(location: Location) {
        currentLocation = location
        val speedKmh = location.speed * 3.6f
        updateSpeedHistory(speedKmh)
    }

    private fun updateSpeedHistory(speed: Float) {
        speedHistory.add(speed)
        if (speedHistory.size > maxSpeedHistorySize) {
            speedHistory.removeAt(0)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Apply gravity filter
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

                val linearAccelX = event.values[0] - gravity[0]
                val linearAccelY = event.values[1] - gravity[1]
                val linearAccelZ = event.values[2] - gravity[2]

                // Auto-reorientation (Paper Section 5.1.1)
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

                // Update Z-axis history
                updateZAxisHistory(reading.timestamp, reorientedZ)

                // Phase 1: Threshold-based candidate detection
                detectEventCandidates(reading)
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

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun autoReorient(ax: Float, ay: Float, az: Float): Float {
        // Euler angle-based reorientation (Paper Section 5.1.1)
        val theta = atan2(ay.toDouble(), az.toDouble())
        val beta = atan2(-ax.toDouble(), sqrt((ay * ay + az * az).toDouble()))

        // Calculate reoriented Z-axis: a'z = -ax*sin(β) + ay*cos(β)*sin(θ) + az*cos(β)*cos(θ)
        val reorientedZ =
            (-ax * sin(beta) + ay * cos(beta) * sin(theta) + az * cos(beta) * cos(theta)).toFloat()

        return reorientedZ
    }

    private fun updateZAxisHistory(timestamp: Long, zValue: Float) {
        zAxisHistory.add(Pair(timestamp, zValue))
        Log.d("zAxis", Pair(timestamp, zValue).toString())

        // Keep only recent history
        val cutoffTime = timestamp - (maxZHistorySize * 16) // ~3 seconds
        zAxisHistory.removeAll { it.first < cutoffTime }
    }

    private fun calculateDynamicThreshold(avgSpeed: Float, isSpeedBreaker: Boolean): Float {
        // Paper Equation (1): Tt = T0 + ((1/t * Σ Vi - L) × S) if Σ Vi > B, else T0
        val T0 = if (isSpeedBreaker) T0_speedBreaker else T0_pothole

        return if (avgSpeed > B) {
            T0 + ((avgSpeed - L) * S)
        } else {
            T0
        }
    }

    private fun detectEventCandidates(reading: SensorReading) {
        val currentTime = System.currentTimeMillis()

        // Avoid detecting events too frequently
        if (currentTime - lastEventTime < minTimeBetweenEvents) {
            return
        }

        val avgSpeed = if (speedHistory.isNotEmpty()) {
            speedHistory.average().toFloat()
        } else {
            reading.speed
        }

        val speedBreakerThreshold = calculateDynamicThreshold(avgSpeed, true)
        val potholeThreshold = calculateDynamicThreshold(avgSpeed, false)

        val zValue = reading.reorientedZ

        // Phase 1: Threshold crossing detection (candidate identification)
        val isSpeedBreakerCandidate = zValue > speedBreakerThreshold
        val isPotholeCandidate = zValue < -potholeThreshold

        if (isSpeedBreakerCandidate || isPotholeCandidate) {
            // Extract features for Phase 2 classification
            val features = extractEventFeatures(reading, currentTime)

            // Phase 2: Feature-based classification using decision tree logic
            val eventType = classifyEventUsingFeatures(zValue, features, isSpeedBreakerCandidate)

            if (eventType != EventType.NORMAL) {
                val event = RoadEvent(
                    type = eventType,
                    latitude = reading.latitude,
                    longitude = reading.longitude,
                    timestamp = currentTime,
                    confidence = calculateConfidence(eventType, zValue, features),
                    zAxisValue = zValue,
                    speed = reading.speed,
                    features = features
                )

                _detectedEvents.value = _detectedEvents.value + event
                lastEventTime = currentTime
            }
        }
    }

    private fun extractEventFeatures(reading: SensorReading, currentTime: Long): EventFeatures {
        // Extract features as described in Paper Table 3

        // Find ZNext_t: next local extrema within time window Δ
        val zNext = findNextLocalExtrema(currentTime)

        // Find ZPrev_t: previous local extrema within time window Δ
        val zPrev = findPreviousLocalExtrema(currentTime)

        // Calculate Tp: time since last event
        val timeSinceLastEvent = currentTime - lastEventTime

        return EventFeatures(
            zt = reading.reorientedZ,
            zNext = zNext,
            zPrev = zPrev,
            timeSinceLastEvent = timeSinceLastEvent,
            instantaneousSpeed = reading.speed
        )
    }

    private fun findNextLocalExtrema(currentTime: Long): Float? {
        // Look for local extrema in the next deltaTimeWindow
        val futureData = zAxisHistory.filter {
            it.first > currentTime && it.first <= currentTime + deltaTimeWindow
        }.map { it.second }

        if (futureData.size < 3) return null

        // Find local maxima or minima
        return findLocalExtrema(futureData)
    }

    private fun findPreviousLocalExtrema(currentTime: Long): Float? {
        // Look for local extrema in the previous deltaTimeWindow
        val pastData = zAxisHistory.filter {
            it.first >= currentTime - deltaTimeWindow && it.first < currentTime
        }.map { it.second }

        if (pastData.size < 3) return null

        return findLocalExtrema(pastData)
    }

    private fun findLocalExtrema(data: List<Float>): Float? {
        if (data.size < 3) return null

        // Find the most significant local extremum (largest absolute value)
        var maxExtrema: Float? = null
        var maxAbsValue = 0f

        for (i in 1 until data.size - 1) {
            val current = data[i]
            val prev = data[i - 1]
            val next = data[i + 1]

            // Check if it's a local maximum or minimum
            if ((current > prev && current > next) || (current < prev && current < next)) {
                if (abs(current) > maxAbsValue) {
                    maxExtrema = current
                    maxAbsValue = abs(current)
                }
            }
        }

        return maxExtrema
    }

    private fun classifyEventUsingFeatures(
        zt: Float,
        features: EventFeatures,
        isSpeedBreakerCandidate: Boolean
    ): EventType {
        // Implement decision tree logic from Paper Figure 10

        // Root node: Check if it's a real event or noise
        if (!isRealEvent(features)) {
            return EventType.NORMAL
        }

        // Speed breaker vs pothole classification
        if (isSpeedBreakerCandidate) {
            // Check for speed breaker signature: positive peak followed by negative
            if (features.zNext != null && features.zNext < 0 && zt > 0) {
                return EventType.SPEED_BREAKER
            }

            // Check if it's actually an ambiguous pothole detected as speed breaker
            if (features.zPrev != null && features.zPrev < 0 && features.timeSinceLastEvent < 3000) {
                return EventType.POTHOLE
            }

            return EventType.SPEED_BREAKER
        } else {
            // Pothole candidate: negative peak followed by positive
            if (features.zNext != null && features.zNext > 0 && zt < 0) {
                return EventType.POTHOLE
            }

            return EventType.POTHOLE
        }
    }

    private fun isRealEvent(features: EventFeatures): Boolean {
        // Check if the detected event is real or just noise
        // Real events should have significant amplitude and proper timing

        val hasSignificantAmplitude = abs(features.zt) > 0.3f
        val hasProperTiming = features.timeSinceLastEvent > 200L // At least 200ms apart
        val hasReasonableSpeed =
            features.instantaneousSpeed > 3f && features.instantaneousSpeed < 120f

        return hasSignificantAmplitude && hasProperTiming && hasReasonableSpeed
    }

    private fun calculateConfidence(
        type: EventType,
        zAxisValue: Float,
        features: EventFeatures
    ): Float {
        // Calculate confidence based on feature quality and thresholds
        val baseConfidence = when (type) {
            EventType.SPEED_BREAKER -> {
                val threshold = calculateDynamicThreshold(features.instantaneousSpeed, true)
                min(1f, abs(zAxisValue) / threshold)
            }

            EventType.POTHOLE -> {
                val threshold = calculateDynamicThreshold(features.instantaneousSpeed, false)
                min(1f, abs(zAxisValue) / threshold)
            }

            EventType.BROKEN_PATCH -> 0.7f
            EventType.NORMAL -> 0f
        }

        // Adjust confidence based on feature quality
        var confidence = baseConfidence

        // Boost confidence if we have clear next/prev extrema
        if (features.zNext != null || features.zPrev != null) {
            confidence = min(1f, confidence * 1.2f)
        }

        // Reduce confidence for very low speeds (unreliable)
        if (features.instantaneousSpeed < 10f) {
            confidence *= 0.8f
        }

        return max(0.1f, min(1f, confidence))
    }

    fun clearDetectedEvents() {
        _detectedEvents.value = emptyList()
        lastEventTime = 0L
    }
}