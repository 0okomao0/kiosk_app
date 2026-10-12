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
        const val DEFAULT_BACKGROUND_COLOR = "#002b36"
        const val APPEARANCE_DARK = "dark"
        const val APPEARANCE_LIGHT = "light"
        val BACKGROUND_COLORS = setOf("#002b36", "#073642", "#000000", "#202124", "#10243A", "#18332F")
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

    var backgroundColor: String
        get() = prefs.getString("backgroundColor", DEFAULT_BACKGROUND_COLOR) ?: DEFAULT_BACKGROUND_COLOR
        set(value) = prefs.edit().putString("backgroundColor", value).apply()

    var appearanceMode: String
        get() = prefs.getString("appearanceMode", APPEARANCE_DARK) ?: APPEARANCE_DARK
        set(value) = prefs.edit().putString("appearanceMode", value).apply()

    var latitude: String
        get() = prefs.getString("lat", "35.68") ?: "35.68"
        set(value) = prefs.edit().putString("lat", value).apply()

    var longitude: String
        get() = prefs.getString("lon", "139.69") ?: "139.69"
        set(value) = prefs.edit().putString("lon", value).apply()
}