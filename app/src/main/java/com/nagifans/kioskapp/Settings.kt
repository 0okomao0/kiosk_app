package com.nagifans.kioskapp

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("kiosk", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        set(value) = prefs.edit().putString("url", value).apply()

    var pin: String
        get() = prefs.getString("pin", DEFAULT_PIN) ?: DEFAULT_PIN
        set(value) = prefs.edit().putString("pin", value).apply()

    var brightness: Int
        get() = prefs.getInt("brightness", 0)
        set(value) = prefs.edit().putInt("brightness", value).apply()

    companion object {
        const val DEFAULT_URL = "https://appassets.androidplatform.net/assets/index.html"
        const val DEFAULT_PIN = "1234"
        const val APPEARANCE_DARK = "dark"
        const val APPEARANCE_LIGHT = "light"
    }
    var sleepEnabled: Boolean
        get() = prefs.getBoolean("sleepEnabled", false)
        set(value) = prefs.edit().putBoolean("sleepEnabled", value).apply()

    var sleepStart: String
        get() = prefs.getString("sleepStart", "23:00") ?: "23:00"
        set(value) = prefs.edit().putString("sleepStart", value).apply()

    var sleepEnd: String
        get() = prefs.getString("sleepEnd", "06:00") ?: "06:00"
        set(value) = prefs.edit().putString("sleepEnd", value).apply()
    var memo: String
        get() = prefs.getString("memo", "") ?: ""
        set(value) = prefs.edit().putString("memo", value).apply()

    var appearanceMode: String
        get() = prefs.getString("appearanceMode", APPEARANCE_DARK) ?: APPEARANCE_DARK
        set(value) = prefs.edit().putString("appearanceMode", value).apply()

    var automaticAppearance: Boolean
        get() = prefs.getBoolean("automaticAppearance", false)
        set(value) = prefs.edit().putBoolean("automaticAppearance", value).apply()

    var sensorNames: Map<String, String>
        get() = try {
            val o = org.json.JSONObject(prefs.getString("sensorNames", "{}") ?: "{}")
            o.keys().asSequence().associateWith { o.optString(it) }
        } catch (e: org.json.JSONException) { emptyMap() }
        set(value) = prefs.edit().putString("sensorNames", org.json.JSONObject(value).toString()).apply()

    var darkBelowLux: Int
        get() = prefs.getInt("darkBelowLux", 70)
        set(value) = prefs.edit().putInt("darkBelowLux", value).apply()

    var lightAboveLux: Int
        get() = prefs.getInt("lightAboveLux", 120)
        set(value) = prefs.edit().putInt("lightAboveLux", value).apply()

    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(value) = prefs.edit().putBoolean("autoUpdate", value).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(value) = prefs.edit().putLong("lastUpdateCheck", value).apply()

    var lastUpdateResult: String
        get() = prefs.getString("lastUpdateResult", "") ?: ""
        set(value) = prefs.edit().putString("lastUpdateResult", value).apply()

    var uploadEnabled: Boolean
        get() = prefs.getBoolean("uploadEnabled", false)
        set(value) = prefs.edit().putBoolean("uploadEnabled", value).apply()

    var uploadUrl: String
        get() = prefs.getString("uploadUrl", "") ?: ""
        set(value) = prefs.edit().putString("uploadUrl", value).apply()

    var uploadToken: String
        get() = prefs.getString("uploadToken", "") ?: ""
        set(value) = prefs.edit().putString("uploadToken", value).apply()

    var lastUploadAt: Long
        get() = prefs.getLong("lastUploadAt", 0L)
        set(value) = prefs.edit().putLong("lastUploadAt", value).apply()

    var lastUploadResult: String
        get() = prefs.getString("lastUploadResult", "") ?: ""
        set(value) = prefs.edit().putString("lastUploadResult", value).apply()

    var sensorEnabled: Boolean
        get() = prefs.getBoolean("sensorEnabled", true)
        set(value) = prefs.edit().putBoolean("sensorEnabled", value).apply()

    var sensorAddresses: Set<String>
        get() = (prefs.getString("sensorAddresses", "") ?: "").split(',').filter { it.isNotEmpty() }.toSet()
        set(value) = prefs.edit().putString("sensorAddresses", value.joinToString(",")).apply()

    var latitude: String
        get() = prefs.getString("lat", "35.68") ?: "35.68"
        set(value) = prefs.edit().putString("lat", value).apply()

    var longitude: String
        get() = prefs.getString("lon", "139.69") ?: "139.69"
        set(value) = prefs.edit().putString("lon", value).apply()
}