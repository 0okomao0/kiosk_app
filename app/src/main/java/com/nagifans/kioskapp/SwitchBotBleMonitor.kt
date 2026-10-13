package com.nagifans.kioskapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.os.Handler
import android.os.Looper
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import org.json.JSONArray
import org.json.JSONObject

object SwitchBotBleMonitor {
    private const val SWITCHBOT_COMPANY_ID = 0x0969
    private const val BATTERY_SERVICE_UUID = "0000fd3d-0000-1000-8000-00805f9b34fb"
    private const val MAX_AGE_MS = 5 * 60 * 1000L

    private const val TYPE_METER = 0x54
    // Androidはフィルターなしの長時間スキャンを30分で抑制するため、定期的に張り直す
    private const val RESTART_INTERVAL_MS = 10 * 60 * 1000L
    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val restartTask = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            stop()
            start(ctx)
            // 起動に失敗した場合も次回再試行する
            handler.removeCallbacks(this)
            handler.postDelayed(this, RESTART_INTERVAL_MS)
        }
    }
    private const val TYPE_OUTDOOR_METER = 0x77

    private data class Beacon(
        val id: String,
        val address: String,
        val rssi: Int,
        val receivedAtMs: Long,
        val temperatureC: Double,
        val humidity: Int,
        val battery: Int
    )

    private val lock = Any()
    private val beacons = linkedMapOf<String, Beacon>()
    @Volatile private var scanStatus = "not_started"
    // 空なら近くのすべての温湿度計を対象にする
    @Volatile private var allowedAddresses: Set<String> = emptySet()
    private var scanner: BluetoothLeScanner? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val record = result.scanRecord ?: return
            val payload = record.getManufacturerSpecificData(SWITCHBOT_COMPANY_ID) ?: return
            val serviceData = record.getServiceData(ParcelUuid.fromString(BATTERY_SERVICE_UUID)) ?: return
            if (payload.size < 11 || serviceData.size < 3) return
            val type = serviceData[0].toInt() and 0x7F
            if (type != TYPE_METER && type != TYPE_OUTDOOR_METER) return

            val address = normalizeAddress(result.device.address)
            val allowed = allowedAddresses
            if (allowed.isNotEmpty() && address !in allowed) return

            // payload[8]=温度小数、[9]=符号+整数、[10]=湿度
            val decimal = (payload[8].toInt() and 0x0F) / 10.0
            val whole = payload[9].toInt() and 0x7F
            val positive = (payload[9].toInt() and 0x80) != 0
            val temperature = (whole + decimal) * if (positive) 1 else -1
            val humidity = payload[10].toInt() and 0x7F
            if (humidity > 100) return
            val beacon = Beacon(
                id = address.takeLast(4),
                address = address,
                rssi = result.rssi,
                receivedAtMs = System.currentTimeMillis(),
                temperatureC = temperature,
                humidity = humidity,
                battery = serviceData[2].toInt() and 0x7F
            )
            synchronized(lock) {
                beacons[address] = beacon
                removeExpiredLocked(beacon.receivedAtMs)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanStatus = "scan_error:$errorCode"
            scanner = null
        }
    }

    fun hasScanPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    fun normalizeAddress(raw: String): String = raw.filter { it.isLetterOrDigit() }.uppercase()

    fun setAllowedAddresses(addresses: Set<String>) {
        allowedAddresses = addresses
        synchronized(lock) {
            if (addresses.isNotEmpty()) beacons.keys.retainAll(addresses)
        }
    }

    fun applySettings(context: Context, settings: Settings) {
        setAllowedAddresses(settings.sensorAddresses)
        if (settings.sensorEnabled) start(context) else disable()
    }

    fun disable() {
        stop()
        synchronized(lock) { beacons.clear() }
        scanStatus = "disabled"
    }

    fun permissionRequired() {
        scanStatus = "permission_required"
    }

    fun start(context: Context) {
        synchronized(lock) {
            if (scanStatus == "scanning") return
            if (!hasScanPermission(context)) {
                scanStatus = "permission_required"
                return
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                if (!locationManager.isLocationEnabled) {
                    scanStatus = "location_off"
                    return
                }
            }

            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (adapter == null) {
                scanStatus = "unavailable"
                return
            }
            if (!adapter.isEnabled) {
                scanStatus = "bluetooth_off"
                return
            }
            val activeScanner = adapter.bluetoothLeScanner
            if (activeScanner == null) {
                scanStatus = "unavailable"
                return
            }

            try {
                activeScanner.startScan(
                    listOf(ScanFilter.Builder().setManufacturerData(SWITCHBOT_COMPANY_ID, ByteArray(0)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                    callback
                )
                appContext = context.applicationContext
                handler.removeCallbacks(restartTask)
                handler.postDelayed(restartTask, RESTART_INTERVAL_MS)
                scanner = activeScanner
                scanStatus = "scanning"
            } catch (e: SecurityException) {
                scanStatus = "permission_required"
            } catch (e: IllegalStateException) {
                scanStatus = "scan_error:${e.message ?: "Bluetooth scanner unavailable"}"
            }
        }
    }

    fun stop() {
        handler.removeCallbacks(restartTask)
        synchronized(lock) {
            val activeScanner = scanner ?: return
            try {
                activeScanner.stopScan(callback)
                scanner = null
                if (scanStatus == "scanning") scanStatus = "stopped"
            } catch (e: SecurityException) {
                scanner = null
                scanStatus = "scan_error:permission revoked"
            }
        }
    }

    fun knownIds(): List<String> = synchronized(lock) { beacons.values.map { it.id }.sorted() }

    fun detectedAddresses(): List<String> = synchronized(lock) { beacons.keys.sorted() }

    fun readings(names: Map<String, String>): JSONArray {
        val now = System.currentTimeMillis()
        val devices = synchronized(lock) {
            removeExpiredLocked(now)
            beacons.values.sortedBy { it.id }
        }
        val array = JSONArray()
        devices.forEach { b ->
            array.put(
                JSONObject()
                    .put("id", b.id)
                    .put("name", names[b.id].orEmpty())
                    .put("temperatureC", b.temperatureC)
                    .put("humidity", b.humidity)
                    .put("battery", b.battery)
                    .put("rssi", b.rssi)
                    .put("ageSec", ((now - b.receivedAtMs) / 1000).coerceAtLeast(0))
            )
        }
        return array
    }

    fun json(names: Map<String, String>): JSONObject {
        val sensors = readings(names)
        return JSONObject()
            .put("status", scanStatus)
            .put("sensors", sensors)
    }

    private fun removeExpiredLocked(now: Long) {
        beacons.entries.removeAll { now - it.value.receivedAtMs > MAX_AGE_MS }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
}
