package com.nagifans.kioskapp

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager

object ScreenControl {
    fun off(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        try { dpm.lockNow() } catch (e: SecurityException) { }
    }

    @Suppress("DEPRECATION")
    fun on(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "kioskapp:wake"
        ).acquire(3_000)
    }
}