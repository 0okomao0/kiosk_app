package com.nagifans.kioskapp

import kotlinx.coroutines.flow.MutableStateFlow

object KioskState {
    val url = MutableStateFlow(Settings.DEFAULT_URL)
    val reloadTick = MutableStateFlow(0)
    val brightness = MutableStateFlow(0)
}