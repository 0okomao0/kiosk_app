package com.nagifans.kioskapp

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

class SleepScheduler(private val context: Context, private val settings: Settings) {

    fun start(scope: CoroutineScope): Job = scope.launch {
        var last: Boolean? = null
        while (true) {
            if (settings.sleepEnabled) {
                val off = isOffNow()
                if (off != last) {
                    if (off) ScreenControl.off(context)
                    else if (last != null) ScreenControl.on(context)
                    last = off
                }
            } else {
                last = null
            }
            delay(20_000)
        }
    }

    private fun isOffNow(): Boolean {
        val start = runCatching { LocalTime.parse(settings.sleepStart) }.getOrNull() ?: return false
        val end = runCatching { LocalTime.parse(settings.sleepEnd) }.getOrNull() ?: return false
        val now = LocalTime.now()
        return when {
            start == end -> false
            start < end -> now >= start && now < end
            else -> now >= start || now < end // 日をまたぐ(例 23:00〜06:00)
        }
    }
}