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
    val longitude: Double = 0.0,
    val accuracy: Float = 0f
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
    val zt: Float,              // Z-axis value at detection
    val zNext: Float?,          // Next local extrema
    val zPrev: Float?,          // Previous local extrema
    val timeSinceLastEvent: Long, // Time between events
    val instantaneousSpeed: Float, // Speed at detection
    val variance: Float,        // Statistical variance
    val skewness: Float,        // Statistical skewness
    val peakProminence: Float   // Peak prominence measure
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
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val _sensorData = MutableStateFlow(SensorReading())
    val sensorData: StateFlow<SensorReading> = _sensorData.asStateFlow()

    private val _detectedEvents = MutableStateFlow<List<RoadEvent>>(emptyList())
    val detectedEvents: StateFlow<List<RoadEvent>> = _detectedEvents.asStateFlow()

    // Vehicle and placement configuration
    private var vehicleType = VehicleType.TWO_WHEELER_SCOOTY
    private var phonePlacement = PhonePlacement.MOUNTER

    // Research-based threshold values (corrected based on typical road monitoring literature)
    private val initialThresholds = mapOf(
        Pair(VehicleType.TWO_WHEELER_SCOOTY, PhonePlacement.MOUNTER) to Pair(1.2f, 0.8f),
        Pair(VehicleType.TWO_WHEELER_SCOOTY, PhonePlacement.POCKET) to Pair(1.0f, 0.6f),
        Pair(VehicleType.TWO_WHEELER_BIKE, PhonePlacement.MOUNTER) to Pair(1.4f, 0.9f),
        Pair(VehicleType.TWO_WHEELER_BIKE, PhonePlacement.POCKET) to Pair(1.1f, 0.7f),
        Pair(VehicleType.THREE_WHEELER_AUTO, PhonePlacement.MOUNTER) to Pair(1.0f, 0.6f),
        Pair(VehicleType.THREE_WHEELER_AUTO, PhonePlacement.WINDSHIELD) to Pair(0.9f, 0.5f),
        Pair(VehicleType.FOUR_WHEELER_CAR, PhonePlacement.WINDSHIELD) to Pair(0.7f, 0.4f),
        Pair(VehicleType.FOUR_WHEELER_CAR, PhonePlacement.DASHBOARD) to Pair(0.8f, 0.4f),
        Pair(VehicleType.FOUR_WHEELER_CAR, PhonePlacement.MOUNTER) to Pair(0.9f, 0.5f)
    )

    // Corrected auto-threshold parameters based on research literature
    private val B = 10f   // Base speed threshold (km/h)
    private val L = 5f    // Lower speed limit (km/h)
    private val S = 0.015f // Conservative scaling factor

    // Enhanced filtering and history management
    private val speedHistory = mutableListOf<Float>()
    private val maxSpeedHistorySize = 15 // Increased for better averaging

    // Sensor data history with proper memory management
    private val zAxisHistory = mutableListOf<Pair<Long, Float>>()
    private val maxZHistorySize = 750 // 5 seconds at 150Hz
    private val accelerometerHistory = mutableListOf<SensorReading>()
    private val maxAccelHistorySize = 450 // 3 seconds at 150Hz

    private var lastEventTime = 0L
    private val minTimeBetweenEvents = 300L // Minimum 300ms between events
    private val deltaTimeWindow = 800L // 800ms window for extrema finding

    // Enhanced gravity compensation using complementary filter
    private val gravity = FloatArray(3) { 0f }
    private val magneticField = FloatArray(3) { 0f }
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val alpha = 0.92f // More conservative low-pass filter

    // Current location and enhanced speed calculation
    private var currentLocation: Location? = null
    private var previousLocation: Location? = null
    private var speedFromAccel = 0f
    private var lastSpeedUpdateTime = 0L

    // Statistical validation parameters
    private var baselineNoise = 0f
    private var noiseUpdateCount = 0

    fun startSensorCollection() {
        // Use research-standard sampling rate (100Hz max on Android)
        val samplingPeriod = SensorManager.SENSOR_DELAY_FASTEST // ~100Hz

        sensorManager.registerListener(this, accelerometer, samplingPeriod)
        sensorManager.registerListener(this, gyroscope, samplingPeriod)
        sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)

        Log.d("SensorManager", "Started sensor collection at maximum available rate")
    }

    fun stopSensorCollection() {
        sensorManager.unregisterListener(this)
        Log.d("SensorManager", "Stopped sensor collection")
    }

    fun updateLocation(location: Location) {
        if (!isLocationValid(location)) return

        previousLocation = currentLocation
        currentLocation = location

        val speed = calculateEnhancedSpeed(location)
        updateSpeedHistory(speed)

        Log.d(
            "Location",
            "Updated: Lat=${location.latitude}, Lng=${location.longitude}, Speed=${speed}km/h"
        )
    }

    private fun isLocationValid(location: Location): Boolean {
        // Validate location accuracy and reasonableness
        return location.hasAccuracy() &&
                location.accuracy < 15f && // 15m accuracy threshold
                location.latitude != 0.0 &&
                location.longitude != 0.0
    }

    private fun calculateEnhancedSpeed(location: Location): Float {
        val currentTime = System.currentTimeMillis()

        val gpsSpeed = if (location.hasSpeed() && location.speed >= 0) {
            location.speed * 3.6f // Convert m/s to km/h
        } else if (previousLocation != null && currentTime > lastSpeedUpdateTime + 1000) {
            // Calculate from distance if GPS speed unavailable
            val distance = location.distanceTo(previousLocation!!)
            val timeDiff = (location.time - previousLocation!!.time) / 1000f
            if (timeDiff > 0 && distance < 1000) { // Sanity check: <1km distance
                (distance / timeDiff) * 3.6f
            } else 0f
        } else 0f

        // Integrate accelerometer for speed estimation (simple integration)
        if (currentTime > lastSpeedUpdateTime + 100) { // Update every 100ms
            val deltaTime = (currentTime - lastSpeedUpdateTime) / 1000f
            val currentAccel = _sensorData.value.reorientedZ

            // Simple forward acceleration integration (rough estimate)
            if (abs(currentAccel) > 0.1f) {
                speedFromAccel += currentAccel * deltaTime * 3.6f // m/s² to km/h
                speedFromAccel = speedFromAccel.coerceIn(-200f, 200f) // Sanity bounds
            }

            lastSpeedUpdateTime = currentTime
        }

        // Weighted combination based on GPS accuracy
        return when {
            location.hasAccuracy() && location.accuracy < 5f -> {
                gpsSpeed * 0.9f + speedFromAccel * 0.1f // High GPS accuracy
            }

            location.hasAccuracy() && location.accuracy < 10f -> {
                gpsSpeed * 0.7f + speedFromAccel * 0.3f // Medium GPS accuracy
            }

            else -> {
                gpsSpeed * 0.5f + speedFromAccel * 0.5f // Low GPS accuracy
            }
        }.coerceIn(0f, 150f) // Reasonable vehicle speed bounds
    }

    private fun updateSpeedHistory(speed: Float) {
        speedHistory.add(speed)
        if (speedHistory.size > maxSpeedHistorySize) {
            speedHistory.removeAt(0)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val currentTime = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                processAccelerometerData(event, currentTime)
            }

            Sensor.TYPE_GYROSCOPE -> {
                updateGyroscopeData(event)
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                updateMagneticField(event)
            }
        }
    }

    private fun processAccelerometerData(event: SensorEvent, currentTime: Long) {
        // Enhanced gravity compensation using complementary filter
        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]

        // Extract linear acceleration
        val linearAccelX = event.values[0] - gravity[0]
        val linearAccelY = event.values[1] - gravity[1]
        val linearAccelZ = event.values[2] - gravity[2]

        // Enhanced auto-reorientation
        val reorientedComponents = enhancedAutoReorient(linearAccelX, linearAccelY, linearAccelZ)

        val reading = _sensorData.value.copy(
            timestamp = currentTime,
            accelerometerX = linearAccelX,
            accelerometerY = linearAccelY,
            accelerometerZ = linearAccelZ,
            reorientedZ = reorientedComponents.third,
            speed = speedHistory.lastOrNull() ?: 0f,
            latitude = currentLocation?.latitude ?: 0.0,
            longitude = currentLocation?.longitude ?: 0.0,
            accuracy = currentLocation?.accuracy ?: Float.MAX_VALUE
        )

        _sensorData.value = reading

        // Update history with memory management
        updateSensorHistory(reading)

        // Update baseline noise estimation
        updateBaselineNoise(reading.reorientedZ)

        // Enhanced Phase 1 detection
        detectPhase1CandidatesEnhanced(reading)
    }

    private fun enhancedAutoReorient(ax: Float, ay: Float, az: Float): Triple<Float, Float, Float> {
        // Get device orientation using rotation matrix if magnetometer available
        if (magneticField.any { it != 0f }) {
            val success =
                SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magneticField)
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientation)

                // Apply rotation matrix transformation
                val rotatedX =
                    rotationMatrix[0] * ax + rotationMatrix[1] * ay + rotationMatrix[2] * az
                val rotatedY =
                    rotationMatrix[3] * ax + rotationMatrix[4] * ay + rotationMatrix[5] * az
                val rotatedZ =
                    rotationMatrix[6] * ax + rotationMatrix[7] * ay + rotationMatrix[8] * az

                return Triple(rotatedX, rotatedY, rotatedZ)
            }
        }

        // Fallback to Euler angle method with gimbal lock prevention
        val pitch = atan2(ay.toDouble(), sqrt(ax * ax + az * az).toDouble())
        val roll = atan2(-ax.toDouble(), az.toDouble())

        // Apply rotation transformations
        val aPrimeX =
            (ax * cos(roll) + ay * sin(roll) * sin(pitch) + az * cos(pitch) * sin(roll)).toFloat()
        val aPrimeY = (ay * cos(pitch) - az * sin(pitch)).toFloat()
        val aPrimeZ =
            (-ax * sin(roll) + ay * cos(roll) * sin(pitch) + az * cos(roll) * cos(pitch)).toFloat()

        return Triple(aPrimeX, aPrimeY, aPrimeZ)
    }

    private fun updateGyroscopeData(event: SensorEvent) {
        _sensorData.value = _sensorData.value.copy(
            gyroscopeX = event.values[0],
            gyroscopeY = event.values[1],
            gyroscopeZ = event.values[2]
        )
    }

    private fun updateMagneticField(event: SensorEvent) {
        magneticField[0] = event.values[0]
        magneticField[1] = event.values[1]
        magneticField[2] = event.values[2]
    }

    private fun updateSensorHistory(reading: SensorReading) {
        // Update Z-axis history
        zAxisHistory.add(Pair(reading.timestamp, reading.reorientedZ))
        if (zAxisHistory.size > maxZHistorySize) {
            zAxisHistory.removeAt(0)
        }

        // Update accelerometer history
        accelerometerHistory.add(reading)
        if (accelerometerHistory.size > maxAccelHistorySize) {
            accelerometerHistory.removeAt(0)
        }
    }

    private fun updateBaselineNoise(zValue: Float) {
        // Update noise baseline for threshold adaptation
        if (noiseUpdateCount < 100) {
            baselineNoise =
                (baselineNoise * noiseUpdateCount + abs(zValue)) / (noiseUpdateCount + 1)
            noiseUpdateCount++
        } else if (abs(zValue) < 0.5f) { // Only update with low-amplitude data
            baselineNoise = baselineNoise * 0.99f + abs(zValue) * 0.01f
        }
    }

    private fun calculateDynamicThreshold(isSpeedBreaker: Boolean): Float {
        val (T0_speedBreaker, T0_pothole) = initialThresholds[Pair(vehicleType, phonePlacement)]
            ?: Pair(1.2f, 0.8f)

        val T0 = if (isSpeedBreaker) T0_speedBreaker else T0_pothole

        // Enhanced moving average with outlier rejection
        val validSpeeds = speedHistory.filter { it in 0f..120f }
        val avgSpeed = if (validSpeeds.isNotEmpty()) {
            // Remove outliers using IQR method
            val sortedSpeeds = validSpeeds.sorted()
            val q1 = sortedSpeeds[sortedSpeeds.size / 4]
            val q3 = sortedSpeeds[3 * sortedSpeeds.size / 4]
            val iqr = q3 - q1

            validSpeeds.filter { it >= q1 - 1.5 * iqr && it <= q3 + 1.5 * iqr }
                .average().toFloat()
        } else 0f

        // Enhanced threshold calculation with noise adaptation
        val noiseAdaptation = maxOf(1.0f, baselineNoise * 2f) // Adapt to environmental noise

        return if (avgSpeed > L) {
            val speedFactor = 1f + ((avgSpeed - L) * S)
            (T0 * speedFactor * noiseAdaptation).coerceIn(T0 * 0.5f, T0 * 3f)
        } else {
            T0 * noiseAdaptation
        }
    }

    private fun detectPhase1CandidatesEnhanced(reading: SensorReading) {
        val currentTime = System.currentTimeMillis()

        // Enhanced timing constraints
        if (currentTime - lastEventTime < minTimeBetweenEvents) return

        // Speed validation
        if (reading.speed < 1f || reading.speed > 100f) return

        val speedBreakerThreshold = calculateDynamicThreshold(true)
        val potholeThreshold = calculateDynamicThreshold(false)

        val zValue = reading.reorientedZ

        // Enhanced peak detection with prominence
        val peakProminence = calculatePeakProminence(zValue, currentTime)

        val isSpeedBreakerCandidate = zValue > speedBreakerThreshold &&
                peakProminence > 0.3f &&
                isLocalPeak(zValue, true)

        val isPotholeCandidate = zValue < -potholeThreshold &&
                peakProminence > 0.3f &&
                isLocalPeak(zValue, false)

        if (isSpeedBreakerCandidate || isPotholeCandidate) {
            val candidateType =
                if (isSpeedBreakerCandidate) EventType.SPEED_BREAKER else EventType.POTHOLE

            val candidate = RoadEvent(
                type = candidateType,
                latitude = reading.latitude,
                longitude = reading.longitude,
                timestamp = currentTime,
                confidence = 0.6f, // Initial Phase 1 confidence
                zAxisValue = zValue,
                speed = reading.speed
            )

            // Process through enhanced Phase 2 classification
            val features = extractEnhancedEventFeatures(reading, currentTime, peakProminence)
            val classifiedEvent = classifyUsingEnhancedDecisionTree(candidate, features)

            if (classifiedEvent.type != EventType.NORMAL && classifiedEvent.confidence > 0.5f) {
                _detectedEvents.value = _detectedEvents.value + classifiedEvent
                lastEventTime = currentTime

                Log.d(
                    "RoadEvent",
                    "Detected ${classifiedEvent.type} with confidence ${"%.2f".format(classifiedEvent.confidence)}, " +
                            "Z: ${"%.3f".format(zValue)}, Speed: ${"%.1f".format(reading.speed)} km/h, Threshold: ${"%.3f".format(if (isSpeedBreakerCandidate) speedBreakerThreshold else potholeThreshold)}"

                )
            }
        }
    }

    private fun calculatePeakProminence(zValue: Float, currentTime: Long): Float {
        val recentWindow = zAxisHistory.filter {
            it.first >= currentTime - 500L && it.first <= currentTime + 100L
        }.map { it.second }

        if (recentWindow.size < 5) return 0f

        val mean = recentWindow.average().toFloat()
        val maxDeviation = recentWindow.maxOfOrNull { abs(it - mean) } ?: 0f

        return abs(zValue - mean) / maxOf(maxDeviation, 0.1f)
    }

    private fun isLocalPeak(zValue: Float, isPositivePeak: Boolean): Boolean {
        val recentValues = zAxisHistory.takeLast(10).map { it.second }
        if (recentValues.size < 10) return false

        val centerIndex = 5
        val centerValue = recentValues[centerIndex]

        // Check if current value forms a local peak
        return if (isPositivePeak) {
            zValue > centerValue &&
                    recentValues.drop(centerIndex).all { zValue >= it } &&
                    recentValues.take(centerIndex).all { zValue >= it }
        } else {
            zValue < centerValue &&
                    recentValues.drop(centerIndex).all { zValue <= it } &&
                    recentValues.take(centerIndex).all { zValue <= it }
        }
    }

    private fun extractEnhancedEventFeatures(
        reading: SensorReading,
        currentTime: Long,
        peakProminence: Float
    ): EventFeatures {
        // Dynamic time window based on speed
        val adaptiveWindow = maxOf(400L, minOf(1200L, (2000L / maxOf(reading.speed, 5f)).toLong()))

        val zNext = findEnhancedLocalExtrema(currentTime, true, adaptiveWindow)
        val zPrev = findEnhancedLocalExtrema(currentTime, false, adaptiveWindow)

        // Statistical features
        val recentZValues = zAxisHistory.takeLast(50).map { it.second }
        val variance = calculateVariance(recentZValues)
        val skewness = calculateSkewness(recentZValues)

        return EventFeatures(
            zt = reading.reorientedZ,
            zNext = zNext,
            zPrev = zPrev,
            timeSinceLastEvent = currentTime - lastEventTime,
            instantaneousSpeed = reading.speed,
            variance = variance,
            skewness = skewness,
            peakProminence = peakProminence
        )
    }

    private fun findEnhancedLocalExtrema(
        currentTime: Long,
        searchForward: Boolean,
        windowSize: Long
    ): Float? {
        val windowData = if (searchForward) {
            zAxisHistory.filter {
                it.first > currentTime && it.first <= currentTime + windowSize
            }
        } else {
            zAxisHistory.filter {
                it.first >= currentTime - windowSize && it.first < currentTime
            }
        }.map { it.second }

        if (windowData.size < 5) return null

        // Find most significant extrema with statistical validation
        var bestExtrema: Float? = null
        var maxSignificance = 0f

        for (i in 2 until windowData.size - 2) {
            val current = windowData[i]
            val neighbors =
                listOf(windowData[i - 2], windowData[i - 1], windowData[i + 1], windowData[i + 2])
            val meanNeighbor = neighbors.average().toFloat()

            val isLocalMax = neighbors.all { current > it }
            val isLocalMin = neighbors.all { current < it }

            if (isLocalMax || isLocalMin) {
                val significance = abs(current - meanNeighbor)
                if (significance > maxSignificance) {
                    bestExtrema = current
                    maxSignificance = significance
                }
            }
        }

        return if (maxSignificance > 0.2f) bestExtrema else null
    }

    private fun classifyUsingEnhancedDecisionTree(
        candidate: RoadEvent,
        features: EventFeatures
    ): RoadEvent {
        var confidence = 0.5f
        var finalType = candidate.type

        // Enhanced decision tree based on research methodology

        // Root node: Validate basic criteria
        if (!isValidEventCandidate(features)) {
            return candidate.copy(type = EventType.NORMAL, confidence = 0.1f)
        }

        // Node 1: Amplitude and prominence validation
        if (abs(features.zt) < 0.4f || features.peakProminence < 0.5f) {
            return candidate.copy(type = EventType.NORMAL, confidence = 0.2f)
        }

        // Node 2: Speed-based classification refinement
        confidence = getSpeedBasedConfidence(features.instantaneousSpeed)

        // Node 3: Signature pattern analysis
        when (candidate.type) {
            EventType.SPEED_BREAKER -> {
                confidence *= analyzeSpeedBreakerSignature(features)

                // Check for misclassification
                if (features.zt < 0 && features.zNext != null && features.zNext > 0.5f) {
                    finalType = EventType.POTHOLE // Reclassify
                    confidence *= 0.8f
                }
            }

            EventType.POTHOLE -> {
                confidence *= analyzePotholeSignature(features)

                // Check for misclassification
                if (features.zt > 0 && features.zPrev != null && features.zPrev < -0.5f) {
                    finalType = EventType.SPEED_BREAKER // Reclassify
                    confidence *= 0.8f
                }
            }

            else -> confidence *= 0.5f
        }

        // Node 4: Statistical validation
        confidence *= validateStatisticalFeatures(features)

        // Node 5: Temporal consistency check
        confidence *= validateTemporalConsistency(features)

        // Node 6: Check for broken patch pattern
        finalType = checkForEnhancedBrokenPatch(finalType, features)

        return candidate.copy(
            type = finalType,
            confidence = confidence.coerceIn(0f, 1f),
            features = features
        )
    }

    private fun isValidEventCandidate(features: EventFeatures): Boolean {
        return features.instantaneousSpeed >= 2f &&
                features.instantaneousSpeed <= 100f &&
                abs(features.zt) >= 0.3f &&
                features.timeSinceLastEvent >= minTimeBetweenEvents
    }

    private fun getSpeedBasedConfidence(speed: Float): Float {
        return when {
            speed < 5f -> 0.6f      // Very slow - reduced reliability
            speed <= 15f -> 1.0f    // Optimal detection range
            speed <= 25f -> 0.95f   // Good detection range
            speed <= 40f -> 0.85f   // Moderate speed
            speed <= 60f -> 0.75f   // Higher speed - reduced reliability
            else -> 0.6f           // Very high speed - lowest reliability
        }
    }

    private fun analyzeSpeedBreakerSignature(features: EventFeatures): Float {
        var multiplier = 1.0f

        // Typical speed breaker: positive peak followed by negative valley
        if (features.zt > 0) {
            multiplier *= 1.1f

            if (features.zNext != null && features.zNext < -0.4f) {
                multiplier *= 1.3f // Strong signature
            }

            if (features.zPrev != null && features.zPrev < -0.2f) {
                multiplier *= 1.1f // Preceding valley
            }
        } else {
            multiplier *= 0.7f // Unexpected negative peak
        }

        // Speed breakers typically show higher variance
        if (features.variance > 0.5f) multiplier *= 1.1f

        return multiplier.coerceIn(0.3f, 1.5f)
    }

    private fun analyzePotholeSignature(features: EventFeatures): Float {
        var multiplier = 1.0f

        // Typical pothole: negative peak followed by positive recovery
        if (features.zt < 0) {
            multiplier *= 1.1f

            if (features.zNext != null && features.zNext > 0.3f) {
                multiplier *= 1.3f // Strong recovery signature
            }

            if (abs(features.zt) > 1.0f) {
                multiplier *= 1.2f // Significant negative amplitude
            }
        } else {
            multiplier *= 0.6f // Unexpected positive peak
        }

        // Potholes often show asymmetric patterns (high skewness)
        if (abs(features.skewness) > 1.0f) multiplier *= 1.1f

        return multiplier.coerceIn(0.3f, 1.5f)
    }

    private fun validateStatisticalFeatures(features: EventFeatures): Float {
        var multiplier = 1.0f

        // Variance validation
        if (features.variance < 0.1f) {
            multiplier *= 0.8f // Too uniform, might be noise
        } else if (features.variance > 2.0f) {
            multiplier *= 0.9f // Too chaotic
        }

        // Skewness validation
        if (abs(features.skewness) > 3.0f) {
            multiplier *= 0.8f // Extreme skewness might indicate artifacts
        }

        // Peak prominence validation
        if (features.peakProminence < 0.5f) {
            multiplier *= 0.7f // Low prominence
        } else if (features.peakProminence > 2.0f) {
            multiplier *= 1.2f // High prominence
        }

        return multiplier.coerceIn(0.5f, 1.3f)
    }

    private fun validateTemporalConsistency(features: EventFeatures): Float {
        // Events too close together are suspicious
        return when {
            features.timeSinceLastEvent < 200L -> 0.6f
            features.timeSinceLastEvent < 500L -> 0.8f
            features.timeSinceLastEvent < 2000L -> 1.0f
            features.timeSinceLastEvent < 10000L -> 0.95f
            else -> 0.9f // Very old last event
        }
    }

    private fun checkForEnhancedBrokenPatch(
        currentType: EventType,
        features: EventFeatures
    ): EventType {
        val recentEvents = _detectedEvents.value.filter {
            System.currentTimeMillis() - it.timestamp < 20000L // 20 second window
        }

        if (recentEvents.size >= 3) {
            val eventDensity = recentEvents.size.toFloat() / 20f
            val avgSpeed = recentEvents.map { it.speed }.average().toFloat()
            val avgConfidence = recentEvents.map { it.confidence }.average().toFloat()

            // Enhanced broken patch detection criteria
            val hasAlternatingPattern = checkAlternatingEventPattern(recentEvents)
            val hasConsistentLowSpeed = avgSpeed < 25f
            val hasHighEventDensity = eventDensity > 0.15f
            val hasGoodConfidence = avgConfidence > 0.6f

            // Check spatial clustering (events close geographically)
            val hasSpatialClustering = checkSpatialEventClustering(recentEvents)

            if (hasAlternatingPattern && hasConsistentLowSpeed &&
                hasHighEventDensity && hasGoodConfidence && hasSpatialClustering
            ) {

                Log.d(
                    "BrokenPatch", "Detected broken patch pattern: ${recentEvents.size} events, " +
                            "density: $eventDensity, avg speed: $avgSpeed km/h"
                )
                return EventType.BROKEN_PATCH
            }
        }

        return currentType
    }

    private fun checkAlternatingEventPattern(events: List<RoadEvent>): Boolean {
        if (events.size < 2) return false

        var hasSpeedBreakers = false
        var hasPotholes = false
        var alternationCount = 0

        // Check for alternating pattern and count transitions
        for (i in 1 until events.size) {
            val current = events[i].type
            val previous = events[i - 1].type

            when (current) {
                EventType.SPEED_BREAKER -> hasSpeedBreakers = true
                EventType.POTHOLE -> hasPotholes = true
                else -> {}
            }

            if (current != previous &&
                (current == EventType.SPEED_BREAKER || current == EventType.POTHOLE) &&
                (previous == EventType.SPEED_BREAKER || previous == EventType.POTHOLE)
            ) {
                alternationCount++
            }
        }

        // Broken patch typically has mixed event types with some alternation
        return hasSpeedBreakers && hasPotholes && alternationCount >= 1
    }

    private fun checkSpatialEventClustering(events: List<RoadEvent>): Boolean {
        if (events.size < 2) return false

        // Calculate average distance between consecutive events
        var totalDistance = 0.0
        var validDistances = 0

        for (i in 1 until events.size) {
            val current = events[i]
            val previous = events[i - 1]

            if (current.latitude != 0.0 && current.longitude != 0.0 &&
                previous.latitude != 0.0 && previous.longitude != 0.0
            ) {

                val location1 = Location("").apply {
                    latitude = current.latitude
                    longitude = current.longitude
                }
                val location2 = Location("").apply {
                    latitude = previous.latitude
                    longitude = previous.longitude
                }

                val distance = location1.distanceTo(location2).toDouble()
                if (distance < 1000) { // Ignore unrealistic distances > 1km
                    totalDistance += distance
                    validDistances++
                }
            }
        }

        if (validDistances == 0) return false

        val avgDistance = totalDistance / validDistances

        // Broken patch events should be relatively close together (within 200m average)
        return avgDistance < 200.0
    }

    // Statistical helper functions
    private fun calculateVariance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        return values.map { (it - mean) * (it - mean) }.average().toFloat()
    }

    private fun calculateSkewness(values: List<Float>): Float {
        if (values.size < 3) return 0f

        val mean = values.average().toFloat()
        val variance = calculateVariance(values)

        if (variance == 0f) return 0f

        val stdDev = sqrt(variance)
        val skewness = values.map {
            val normalized = (it - mean) / stdDev
            normalized * normalized * normalized
        }.average().toFloat()

        return skewness
    }

    // Configuration and utility methods
    fun setVehicleType(type: VehicleType) {
        vehicleType = type
        Log.d("Config", "Vehicle type set to: $type")
    }

    fun setPhonePlacement(placement: PhonePlacement) {
        phonePlacement = placement
        Log.d("Config", "Phone placement set to: $placement")
    }

    fun clearDetectedEvents() {
        _detectedEvents.value = emptyList()
        lastEventTime = 0L

        // Reset statistical baselines
        baselineNoise = 0f
        noiseUpdateCount = 0

        Log.d("Events", "Cleared all detected events and reset baselines")
    }

    // Debug and monitoring methods
    fun getCurrentThresholds(): Pair<Float, Float> {
        return Pair(
            calculateDynamicThreshold(true),   // Speed breaker threshold
            calculateDynamicThreshold(false)   // Pothole threshold
        )
    }

    fun getSystemStatus(): Map<String, Any> {
        return mapOf(
            "samplingRate" to "FASTEST (~100Hz)",
            "vehicleType" to vehicleType.name,
            "phonePlacement" to phonePlacement.name,
            "currentSpeed" to (speedHistory.lastOrNull() ?: 0f),
            "avgSpeed" to (speedHistory.average().takeIf { speedHistory.isNotEmpty() } ?: 0f),
            "baselineNoise" to baselineNoise,
            "historySize" to zAxisHistory.size,
            "thresholds" to getCurrentThresholds(),
            "lastEventTime" to lastEventTime,
            "totalEvents" to _detectedEvents.value.size
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d("SensorAccuracy", "Sensor ${sensor?.name} accuracy changed to: $accuracy")
    }
}