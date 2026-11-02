package ru.lega.radio.player

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Детектор встряхивания телефона.
 *
 * Задача: поймать **осознанное** встряхивание и не сработать от того, что
 * телефон положили на тумбочку. Поэтому:
 *  - порог высокий (нужно тряхнуть ощутимо);
 *  - требуется несколько пиков подряд в коротком окне;
 *  - после срабатывания — пауза, чтобы одно движение не дало серию событий.
 *
 * Регистрируется только пока идёт таймер сна.
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val sensor: Sensor? = sensorManager?.let {
        it.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    /** Для акселерометра гравитацию вычитаем сами. */
    private val needsGravityRemoval = sensor?.type == Sensor.TYPE_ACCELEROMETER
    private val gravity = FloatArray(3)

    private var windowStartMs = 0L
    private var peaksInWindow = 0
    private var lastShakeMs = 0L
    private var listening = false

    val supported: Boolean
        get() = sensor != null && sensorManager != null

    fun start() {
        if (listening || !supported) return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        listening = true
        Log.i(TAG, "Датчик встряхивания включён")
    }

    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
        Log.i(TAG, "Датчик встряхивания выключен")
    }

    override fun onSensorChanged(event: SensorEvent) {
        var x = event.values[0]
        var y = event.values[1]
        var z = event.values[2]

        if (needsGravityRemoval) {
            val alpha = 0.8f
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z
            x -= gravity[0]
            y -= gravity[1]
            z -= gravity[2]
        }

        val magnitude = sqrt(x * x + y * y + z * z)
        val now = System.currentTimeMillis()

        if (magnitude < THRESHOLD) return

        // Новое окно, если предыдущее «остыло».
        if (now - windowStartMs > WINDOW_MS) {
            windowStartMs = now
            peaksInWindow = 0
        }
        peaksInWindow++

        if (peaksInWindow >= REQUIRED_PEAKS && now - lastShakeMs > COOLDOWN_MS) {
            lastShakeMs = now
            peaksInWindow = 0
            windowStartMs = 0
            Log.i(TAG, "Встряхивание распознано (%.1f м/с²)".format(magnitude))
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "ShakeDetector"

        /** «Ощутимо»: спокойные движения дают 3–8, встряхивание — 20+. */
        const val THRESHOLD = 18f

        /** Пики должны уложиться в это окно. */
        const val WINDOW_MS = 350L

        /** Сколько пиков нужно, чтобы считать это встряхиванием. */
        const val REQUIRED_PEAKS = 2

        /** Пауза между срабатываниями. */
        const val COOLDOWN_MS = 2_000L
    }
}
