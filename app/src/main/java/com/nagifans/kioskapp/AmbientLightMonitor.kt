package com.nagifans.kioskapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

object AmbientLightMonitor : SensorEventListener {
    private const val DARK_BELOW_LUX = 70f
    private const val LIGHT_ABOVE_LUX = 120f

    @Volatile
    var lux: Float? = null
        private set

    @Volatile
    var isAvailable: Boolean = false
        private set

    private var sensorManager: SensorManager? = null
    private var settings: Settings? = null

    fun start(context: Context, appSettings: Settings) {
        stop()
        settings = appSettings
        val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_LIGHT)
        sensorManager = manager
        isAvailable = sensor != null
        lux = null
        if (sensor != null && !manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            isAvailable = false
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        settings = null
        lux = null
        isAvailable = false
    }

    fun applyCurrentReading(appSettings: Settings) {
        lux?.let { updateAppearance(appSettings, it) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val reading = event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return
        lux = reading
        settings?.let { updateAppearance(it, reading) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateAppearance(appSettings: Settings, reading: Float) {
        if (!appSettings.automaticAppearance) return

        val nextMode = when {
            reading < DARK_BELOW_LUX -> Settings.APPEARANCE_DARK
            reading > LIGHT_ABOVE_LUX -> Settings.APPEARANCE_LIGHT
            else -> return
        }
        appSettings.appearanceMode = nextMode
    }
}
