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

enum class VehicleType {
    TWO_WHEELER_SCOOTY,
    TWO_WHEELER_BIKE,
    THREE_WHEELER_AUTO,
    FOUR_WHEELER_CAR
}

enum class PhonePlacement {
    MOUNTER,
    POCKET,
    WINDSHIELD,
    DASHBOARD
}

class SensorDataManager(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val _sensorData = MutableStateFlow(SensorReading())
    val sensorData: StateFlow<SensorReading> = _sensorData.asStateFlow()

    private val _detectedEvents = MutableStateFlow<List<RoadEvent>>(emptyList())
    val detectedEvents: StateFlow<List<RoadEvent>> = _detectedEvents.asStateFlow()

    // Phase 1 candidates before Phase 2 classification
    private val _candidateEvents = MutableStateFlow<List<RoadEvent>>(emptyList())

    // Vehicle and placement configuration (should be configurable by user)
    private var vehicleType = VehicleType.TWO_WHEELER_SCOOTY
    private var phonePlacement = PhonePlacement.MOUNTER

    // Initial thresholds based on Table 2 from paper
    private val initialThresholds = mapOf(
        Pair(VehicleType.TWO_WHEELER_SCOOTY, PhonePlacement.MOUNTER) to Pair(1.8f, 0.714f),
        Pair(VehicleType.TWO_WHEELER_SCOOTY, PhonePlacement.POCKET) to Pair(1.57f, 0.612f),
        Pair(VehicleType.TWO_WHEELER_BIKE, PhonePlacement.MOUNTER) to Pair(1.73f, 0.816f),
        Pair(VehicleType.TWO_WHEELER_BIKE, PhonePlacement.POCKET) to Pair(1.53f, 0.714f),
        Pair(VehicleType.THREE_WHEELER_AUTO, PhonePlacement.MOUNTER) to Pair(1.47f, 0.612f),
        Pair(VehicleType.FOUR_WHEELER_CAR, PhonePlacement.MOUNTER) to Pair(1.08f, 0.41f)
    )

    // Auto-threshold parameters from Equation (1)
    private val B = 20f  // Base point (km/h) - static threshold acts up to this speed
    private val L = 20f  // Lower limit (km/h) - threshold starts adapting from this point
    private val S = 0.05f // Scaling factor - rate of threshold increase per km/h above L

    // Moving average for speed calculation (paper mentions using moving average)
    private val speedHistory = mutableListOf<Float>()
    private val maxSpeedHistorySize = 10

    // Event detection history for feature extraction
    private val zAxisHistory = mutableListOf<Pair<Long, Float>>()
    private val maxZHistorySize = 900 // 6 seconds at 150Hz as mentioned in paper
    private var lastEventTime = 0L
    private val minTimeBetweenEvents = 200L // Minimum 200ms between events
    private val deltaTimeWindow = 1000L // 1 second window for finding extrema (∆ in paper)

    // Gravity compensation using low-pass filter
    private val gravity = FloatArray(3) { 0f }
    private val alpha = 0.8f

    // Current location and movement data
    private var currentLocation: Location? = null
    private var previousLocation: Location? = null

    fun startSensorCollection() {
        // Paper mentions 150Hz sampling rate
        val samplingPeriod = 1000000 / 150 // microseconds for 150Hz
        sensorManager.registerListener(this, accelerometer, samplingPeriod)
        sensorManager.registerListener(this, gyroscope, samplingPeriod)
    }

    fun stopSensorCollection() {
        sensorManager.unregisterListener(this)
    }

    fun updateLocation(location: Location) {
        previousLocation = currentLocation
        currentLocation = location

        // Calculate speed more accurately using GPS
        val speed = if (location.hasSpeed()) {
            location.speed * 3.6f // Convert m/s to km/h
        } else if (previousLocation != null) {
            // Calculate speed from location change
            val distance = location.distanceTo(previousLocation!!)
            val timeDiff = (location.time - previousLocation!!.time) / 1000f
            if (timeDiff > 0) (distance / timeDiff) * 3.6f else 0f
        } else {
            0f
        }

        updateSpeedHistory(speed)
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
                // Apply low-pass filter for gravity compensation
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

                // Remove gravity to get linear acceleration
                val linearAccelX = event.values[0] - gravity[0]
                val linearAccelY = event.values[1] - gravity[1]
                val linearAccelZ = event.values[2] - gravity[2]

                // Auto-reorientation using Euler angles (Section 5.1.1)
                val reorientedComponents = autoReorient(linearAccelX, linearAccelY, linearAccelZ)

                val reading = _sensorData.value.copy(
                    timestamp = System.currentTimeMillis(),
                    accelerometerX = linearAccelX,
                    accelerometerY = linearAccelY,
                    accelerometerZ = linearAccelZ,
                    reorientedZ = reorientedComponents.third, // Use reoriented Z-axis
                    speed = speedHistory.lastOrNull() ?: 0f,
                    latitude = currentLocation?.latitude ?: 0.0,
                    longitude = currentLocation?.longitude ?: 0.0
                )

                _sensorData.value = reading

                // Update Z-axis history for feature extraction
                updateZAxisHistory(reading.timestamp, reading.reorientedZ)

                // Phase 1: Auto-threshold based candidate detection
                detectPhase1Candidates(reading)
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

    /**
     * Auto-reorientation using Euler angles as described in Section 5.1.1
     * Returns (a'x, a'y, a'z) in vehicle reference frame
     */
    private fun autoReorient(ax: Float, ay: Float, az: Float): Triple<Float, Float, Float> {
        // Calculate Euler angles
        val theta = atan2(ay.toDouble(), az.toDouble()) // Pitch angle (Y-axis rotation)
        val beta = atan2(
            -ax.toDouble(),
            sqrt((ay * ay + az * az).toDouble())
        ) // Roll angle (X-axis rotation)

        // Apply rotation matrix transformations
        val aPrimeX =
            (ax * cos(beta) + ay * sin(beta) * sin(theta) + az * cos(theta) * sin(beta)).toFloat()
        val aPrimeY = (ay * cos(theta) - az * sin(theta)).toFloat()
        val aPrimeZ =
            (-ax * sin(beta) + ay * cos(beta) * sin(theta) + az * cos(beta) * cos(theta)).toFloat()

        return Triple(aPrimeX, aPrimeY, aPrimeZ)
    }

    private fun updateZAxisHistory(timestamp: Long, zValue: Float) {
        zAxisHistory.add(Pair(timestamp, zValue))

        // Keep only recent history (6 seconds as mentioned in paper)
        val cutoffTime = timestamp - (6000) // 6 seconds
        zAxisHistory.removeAll { it.first < cutoffTime }
    }

    /**
     * Calculate dynamic threshold using Equation (1) from the paper
     */
    private fun calculateDynamicThreshold(isSpeedBreaker: Boolean): Float {
        val (T0_speedBreaker, T0_pothole) = initialThresholds[Pair(vehicleType, phonePlacement)]
            ?: Pair(1.8f, 0.714f)

        val T0 = if (isSpeedBreaker) T0_speedBreaker else T0_pothole

        // Calculate moving average of speed
        val avgSpeed = if (speedHistory.isNotEmpty()) {
            speedHistory.sum() / speedHistory.size
        } else {
            0f
        }

        // Apply Equation (1): Tt = T0 + ((1/t * Σ Vi - L) × S) if Σ Vi > B, else T0
        return if (avgSpeed > B) {
            T0 + ((avgSpeed - L) * S)
        } else {
            T0
        }
    }

    /**
     * Phase 1: Auto-threshold based candidate detection (Section 5.1)
     */
    private fun detectPhase1Candidates(reading: SensorReading) {
        val currentTime = System.currentTimeMillis()

        // Avoid detecting events too frequently
        if (currentTime - lastEventTime < minTimeBetweenEvents) {
            return
        }

        val speedBreakerThreshold = calculateDynamicThreshold(true)
        val potholeThreshold = calculateDynamicThreshold(false)

        val zValue = reading.reorientedZ

        // Detect candidates based on threshold crossing
        val isSpeedBreakerCandidate = zValue > speedBreakerThreshold
        val isPotholeCandidate = zValue < -potholeThreshold

        if (isSpeedBreakerCandidate || isPotholeCandidate) {
            val candidateType =
                if (isSpeedBreakerCandidate) EventType.SPEED_BREAKER else EventType.POTHOLE

            val candidate = RoadEvent(
                type = candidateType,
                latitude = reading.latitude,
                longitude = reading.longitude,
                timestamp = currentTime,
                confidence = 0.5f, // Phase 1 confidence
                zAxisValue = zValue,
                speed = reading.speed
            )

            // Add to candidates for Phase 2 processing
            _candidateEvents.value = _candidateEvents.value + candidate

            // Immediately process through Phase 2
            val features = extractEventFeatures(reading, currentTime)
            val classifiedEvent = classifyUsingDecisionTree(candidate, features)

            if (classifiedEvent.type != EventType.NORMAL) {
                _detectedEvents.value = _detectedEvents.value + classifiedEvent
                lastEventTime = currentTime

                Log.d(
                    "RoadEvent",
                    "Detected ${classifiedEvent.type} with confidence ${classifiedEvent.confidence}"
                )
            }
        }
    }

    /**
     * Extract features for Phase 2 classification as described in Table 3
     */
    private fun extractEventFeatures(reading: SensorReading, currentTime: Long): EventFeatures {
        // Find Z_Next_t: next local extrema within time window ∆
        val zNext = findLocalExtrema(currentTime, true)

        // Find Z_Prev_t: previous local extrema within time window ∆
        val zPrev = findLocalExtrema(currentTime, false)

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

    /**
     * Find local extrema (maxima or minima) within the time window ∆
     */
    private fun findLocalExtrema(currentTime: Long, searchForward: Boolean): Float? {
        val windowData = if (searchForward) {
            // Search forward in time
            zAxisHistory.filter {
                it.first > currentTime && it.first <= currentTime + deltaTimeWindow
            }
        } else {
            // Search backward in time
            zAxisHistory.filter {
                it.first >= currentTime - deltaTimeWindow && it.first < currentTime
            }
        }.map { it.second }

        if (windowData.size < 3) return null

        // Find most significant local extrema
        var maxExtrema: Float? = null
        var maxAbsValue = 0f

        for (i in 1 until windowData.size - 1) {
            val current = windowData[i]
            val prev = windowData[i - 1]
            val next = windowData[i + 1]

            // Check if it's a local maximum or minimum
            val isLocalMax = current > prev && current > next
            val isLocalMin = current < prev && current < next

            if (isLocalMax || isLocalMin) {
                if (abs(current) > maxAbsValue) {
                    maxExtrema = current
                    maxAbsValue = abs(current)
                }
            }
        }

        return maxExtrema
    }

    /**
     * Phase 2: Decision tree classifier as described in Figure 10
     */
    private fun classifyUsingDecisionTree(
        candidate: RoadEvent,
        features: EventFeatures
    ): RoadEvent {
        // Root node: Check if it's a real event or false positive
        if (!isRealEvent(features)) {
            return candidate.copy(type = EventType.NORMAL, confidence = 0.1f)
        }

        var finalType = candidate.type
        var confidence = 0.6f

        when (candidate.type) {
            EventType.SPEED_BREAKER -> {
                // Check speed breaker signature: positive peak followed by negative
                if (features.zNext != null && features.zNext < 0 && features.zt > 0) {
                    finalType = EventType.SPEED_BREAKER
                    confidence = 0.9f
                } else if (features.zPrev != null && features.zPrev < 0 &&
                    features.timeSinceLastEvent < 3000
                ) {
                    // Ambiguous case: might be pothole detected as speed breaker
                    finalType = EventType.POTHOLE
                    confidence = 0.7f
                } else {
                    confidence = 0.6f
                }
            }

            EventType.POTHOLE -> {
                // Check pothole signature: negative peak followed by positive
                if (features.zNext != null && features.zNext > 0 && features.zt < 0) {
                    finalType = EventType.POTHOLE
                    confidence = 0.9f
                } else {
                    confidence = 0.6f
                }
            }

            else -> {
                // Keep original classification
            }
        }

        // Check for broken patch: multiple consecutive events
        finalType = checkForBrokenPatch(finalType, features)

        return candidate.copy(
            type = finalType,
            confidence = confidence,
            features = features
        )
    }

    /**
     * Check if event is real or caused by noise/interference
     */
    private fun isRealEvent(features: EventFeatures): Boolean {
        // Event should have significant amplitude
        val hasSignificantAmplitude = abs(features.zt) > 0.2f

        // Should not be too frequent (avoid noise)
        val hasProperTiming = features.timeSinceLastEvent > minTimeBetweenEvents

        // Speed should be reasonable for vehicle movement
        val hasReasonableSpeed =
            features.instantaneousSpeed > 1f && features.instantaneousSpeed < 150f

        return hasSignificantAmplitude && hasProperTiming && hasReasonableSpeed
    }

    /**
     * Secondary PoC detection: Identify broken patches from consecutive events
     */
    private fun checkForBrokenPatch(currentType: EventType, features: EventFeatures): EventType {
        // Paper mentions Tp (time between events) and Sp (speed) features
        // If events are very close in time and speed is consistently low, it might be a broken patch

        if (features.timeSinceLastEvent < 3000 && // Events within 3 seconds
            features.instantaneousSpeed < 15f
        ) {  // Low speed indicating careful driving

            // Check if we have multiple recent events (indicating broken patch)
            val recentEvents = _detectedEvents.value.filter {
                System.currentTimeMillis() - it.timestamp < 10000 // Last 10 seconds
            }

            if (recentEvents.size >= 3) {
                return EventType.BROKEN_PATCH
            }
        }

        return currentType
    }

    fun clearDetectedEvents() {
        _detectedEvents.value = emptyList()
        _candidateEvents.value = emptyList()
        lastEventTime = 0L
    }

    // Configuration methods for vehicle and placement
    fun setVehicleType(type: VehicleType) {
        vehicleType = type
    }

    fun setPhonePlacement(placement: PhonePlacement) {
        phonePlacement = placement
    }
}