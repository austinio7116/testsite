package com.wifisurvey.analyzer.survey

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.cos
import kotlin.math.sin

data class WalkState(
    val running: Boolean = false,
    val x: Float = 0f,
    val y: Float = 0f,
    /** Device heading in radians, already rotated into plan space. */
    val headingRad: Float = 0f,
    val steps: Int = 0,
    val distanceMeters: Float = 0f,
    val hasStepDetector: Boolean = false,
    val hasCompass: Boolean = false
)

/**
 * Pedestrian dead reckoning: a step detector says *when* you moved, the
 * rotation vector says *which way*. Each step advances the cursor by the
 * configured stride.
 *
 * This drifts — a few percent over a long walk, worse if you shuffle or hold
 * the phone off-axis — so it is offered as an assist for placing samples, not
 * as ground truth. Tapping the plan directly always wins.
 */
class WalkTracker(context: Context) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val stepDetector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val rotationVector: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val _state = MutableStateFlow(
        WalkState(
            hasStepDetector = stepDetector != null,
            hasCompass = rotationVector != null
        )
    )
    val state: StateFlow<WalkState> = _state.asStateFlow()

    /** Metres advanced per detected step. */
    var strideMeters: Float = 0.72f

    /** Plan bounds, so the cursor cannot wander off the floor plan. */
    var boundsWidth: Float = 6f
    var boundsHeight: Float = 5f

    /** Raw device azimuth, before the plan-north offset is applied. */
    private var rawAzimuth: Float = 0f

    /** Azimuth that corresponds to "up" on the floor plan. */
    private var planNorthOffset: Float = 0f

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    /** Called on every step so the caller can auto-sample. */
    var onStep: ((Float, Float) -> Unit)? = null

    fun start(startX: Float, startY: Float) {
        rawAzimuth = 0f
        stepDetector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotationVector?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        _state.value = _state.value.copy(
            running = true,
            x = startX,
            y = startY,
            steps = 0,
            distanceMeters = 0f
        )
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        _state.value = _state.value.copy(running = false)
    }

    /** Treat the direction the phone currently points as "up" on the plan. */
    fun calibrateHeadingToPlanUp() {
        planNorthOffset = rawAzimuth
        _state.value = _state.value.copy(headingRad = 0f)
    }

    /** Drop the cursor somewhere specific without stopping tracking. */
    fun moveTo(x: Float, y: Float) {
        _state.value = _state.value.copy(x = x, y = y)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, e.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                rawAzimuth = orientation[0]
                if (_state.value.running) {
                    _state.value = _state.value.copy(headingRad = rawAzimuth - planNorthOffset)
                }
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                if (!_state.value.running) return
                val current = _state.value
                val heading = current.headingRad
                // Plan space: +y runs "down" the page, so a forward step at
                // heading 0 decreases y.
                val nx = (current.x + strideMeters * sin(heading)).coerceIn(0f, boundsWidth)
                val ny = (current.y - strideMeters * cos(heading)).coerceIn(0f, boundsHeight)
                _state.value = current.copy(
                    x = nx,
                    y = ny,
                    steps = current.steps + 1,
                    distanceMeters = current.distanceMeters + strideMeters
                )
                onStep?.invoke(nx, ny)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
