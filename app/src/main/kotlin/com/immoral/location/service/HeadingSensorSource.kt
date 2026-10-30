package com.immoral.location.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

/**
 * Device heading source used by 跟随手机朝向.
 *
 * Prefers `TYPE_ROTATION_VECTOR` (fused rotation-vector → azimuth, available on virtually every
 * device since API 9) and falls back to accelerometer + magnetometer when the vendor build lacks
 * the fused sensor. Values are throttled to [EMIT_INTERVAL_MS] (5 Hz) because the engine only
 * needs a heading for the joystick frame — pushing at the raw 50–100 Hz sensor rate would flood
 * the broadcast bridge for no visible benefit.
 */
class HeadingSensorSource(
    context: Context,
    private val onHeading: (Double) -> Unit,
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationVector = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)

    private var hasGravity = false
    private var hasGeomagnetic = false
    private var lastEmitAt = 0L
    private var started = false

    /** Latest azimuth in degrees clockwise from north, or `null` before the first reading. */
    var lastHeadingDeg: Double? = null
        private set

    /** @return `true` when at least one usable sensor was registered. */
    fun start(): Boolean {
        val manager = sensorManager ?: return false
        if (started) return true
        started = try {
            if (rotationVector != null) {
                manager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_UI)
                true
            } else if (accelerometer != null && magnetometer != null) {
                manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
                manager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
                true
            } else {
                Log.w(TAG, "no heading sensor available")
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "registerListener failed", t)
            false
        }
        return started
    }

    fun stop() {
        if (!started) return
        started = false
        hasGravity = false
        hasGeomagnetic = false
        try {
            sensorManager?.unregisterListener(this)
        } catch (t: Throwable) {
            // ignore
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                try {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    emit(orientation[0])
                } catch (t: Throwable) {
                    Log.w(TAG, "rotation vector decode failed", t)
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                hasGravity = true
                emitFromAccelMag()
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                hasGeomagnetic = true
                emitFromAccelMag()
            }
        }
    }

    private fun emitFromAccelMag() {
        if (!hasGravity || !hasGeomagnetic) return
        val ok = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
        if (!ok) return
        SensorManager.getOrientation(rotationMatrix, orientation)
        emit(orientation[0])
    }

    private fun emit(azimuthRad: Float) {
        val now = SystemClock.elapsedRealtime()
        if (lastHeadingDeg != null && now - lastEmitAt < EMIT_INTERVAL_MS) return
        lastEmitAt = now
        var deg = Math.toDegrees(azimuthRad.toDouble()) % 360.0
        if (deg < 0) deg += 360.0
        lastHeadingDeg = deg
        onHeading(deg)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "HeadingSensor"
        const val EMIT_INTERVAL_MS = 200L
    }
}
