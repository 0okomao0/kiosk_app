package com.nagifans.kioskapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// MediaTek端末のthermal_zoneを読む。公式のHardwarePropertiesはこの端末では権限で拒否される
object Thermal {
    private const val CPU_ZONE = "mtktscpu"

    fun read(): Pair<Double?, JSONObject> {
        val zones = JSONArray()
        var cpu: Double? = null
        val dirs = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }
        dirs?.forEach { dir ->
            val type = try { File(dir, "type").readText().trim() } catch (e: Exception) { dir.name }
            val raw = try { File(dir, "temp").readText().trim().toLongOrNull() } catch (e: Exception) { null }
            if (raw != null) {
                val celsius = if (raw > 1000 || raw < -1000) raw / 1000.0 else raw.toDouble()
                zones.put(JSONObject().put("type", type).put("tempC", celsius))
                if (type == CPU_ZONE) cpu = celsius
            }
        }
        val info = JSONObject().put("zones", zones).put("total", dirs?.size ?: 0)
        return cpu to info
    }
}
