package com.nagifans.kioskapp

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// 受信した温湿度を設定されたHTTPSエンドポイントへ定期的にPOSTする
object SensorUploader {
    private const val INTERVAL_MS = 60_000L

    fun start(scope: CoroutineScope, settings: Settings) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                if (settings.uploadEnabled) uploadOnce(settings)
                delay(INTERVAL_MS)
            }
        }
    }

    private fun uploadOnce(settings: Settings) {
        val result = try {
            send(settings)
        } catch (e: Exception) {
            "送信に失敗: ${e.message ?: e.javaClass.simpleName}"
        }
        settings.lastUploadAt = System.currentTimeMillis()
        settings.lastUploadResult = result
    }

    private fun send(settings: Settings): String {
        val url = settings.uploadUrl
        if (!url.startsWith("https://")) return "送信先URLはhttps://で始めてください"
        val sensors = SwitchBotBleMonitor.readings(settings.sensorNames)
        if (sensors.length() == 0) return "送信するセンサー値がありません"

        val body = JSONObject()
            .put("device", Build.MODEL)
            .put("sensors", sensors)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(body.size)
            c.setRequestProperty("Content-Type", "application/json")
            if (settings.uploadToken.isNotEmpty()) {
                c.setRequestProperty("Authorization", "Bearer ${settings.uploadToken}")
            }
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            return if (code in 200..299) "送信しました（${sensors.length()}台）" else "HTTP $code"
        } finally {
            c.disconnect()
        }
    }
}
