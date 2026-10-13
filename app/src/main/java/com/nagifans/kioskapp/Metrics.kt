package com.nagifans.kioskapp

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONObject

object Metrics {
    @Suppress("DEPRECATION")
    fun json(context: Context): String {
        val o = JSONObject()

        val bat = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bat != null) {
            val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            o.put("batteryPercent", if (level >= 0 && scale > 0) level * 100 / scale else -1)
            val batteryTemp = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            o.put("batteryTempC", if (batteryTemp != 0.0) batteryTemp else JSONObject.NULL)
            o.put("charging", bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        o.put("memAvailMb", mi.availMem / 1_048_576)
        o.put("memTotalMb", mi.totalMem / 1_048_576)

        val (cpuTemp, thermalInfo) = Thermal.read()
        o.put("cpuTempC", cpuTemp ?: JSONObject.NULL)
        o.put("thermalZones", thermalInfo.getJSONArray("zones"))
        o.put("thermalZoneTotal", thermalInfo.getInt("total"))
        val st = StatFs(Environment.getDataDirectory().path)
        o.put("storageFreeMb", st.availableBytes / 1_048_576)
        o.put("storageTotalMb", st.totalBytes / 1_048_576)

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        o.put("wifiRssiDbm", wm.connectionInfo?.rssi ?: 0)
        o.put("ip", localIp(context) ?: "")

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        o.put("screenOn", pm.isInteractive)
        o.put("ambientLightSensorAvailable", AmbientLightMonitor.isAvailable)
        o.put("ambientLightLux", AmbientLightMonitor.lux?.toDouble() ?: JSONObject.NULL)
        o.put("switchBotBle", SwitchBotBleMonitor.json(Settings(context).sensorNames))
        o.put("uptimeSec", SystemClock.elapsedRealtime() / 1000)
        o.put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        o.put("androidVersion", android.os.Build.VERSION.RELEASE)
        o.put("model", android.os.Build.MODEL)
        o.put("url", KioskState.url.value)
        return o.toString()
    }
}