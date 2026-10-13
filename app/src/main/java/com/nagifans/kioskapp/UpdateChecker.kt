package com.nagifans.kioskapp

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// GitHub Releasesの最新リリースを確認し、新しい版があれば自動でインストールする
object UpdateChecker {
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/0okomao0/kiosk_app/releases/latest"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val INITIAL_DELAY_MS = 2 * 60 * 1000L
    private const val MAX_APK_BYTES = 150L * 1024 * 1024

    @Volatile private var running = false

    fun start(scope: CoroutineScope, context: Context, settings: Settings) {
        scope.launch(Dispatchers.IO) {
            delay(INITIAL_DELAY_MS)
            while (true) {
                if (settings.autoUpdate) check(context, settings)
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun checkInBackground(context: Context, settings: Settings) {
        Thread { check(context, settings) }.start()
    }

    private fun check(context: Context, settings: Settings) {
        if (running) return
        running = true
        try {
            settings.lastUpdateResult = checkAndInstall(context)
        } catch (e: Exception) {
            settings.lastUpdateResult = "更新確認に失敗: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            settings.lastUpdateCheck = System.currentTimeMillis()
            running = false
        }
    }

    private fun checkAndInstall(context: Context): String {
        val release = JSONObject(httpGetText(LATEST_RELEASE_URL))
        val tag = release.optString("tag_name").removePrefix("v")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        if (!isNewer(tag, installed)) return "最新です（$installed）"

        val assets = release.optJSONArray("assets") ?: return "リリースにAPKがありません"
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { apkUrl = a.optString("browser_download_url"); break }
        }
        if (apkUrl.isNullOrEmpty()) return "リリースにAPKがありません"

        val file = File(context.cacheDir, "update.apk")
        try {
            download(apkUrl, file)
            val archive = context.packageManager.getPackageArchiveInfo(file.path, 0)
                ?: return "ダウンロードしたAPKを読み取れません"
            val installedCode = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            if (archive.longVersionCode <= installedCode) return "リリースの版が現在以下です（$tag）"
            // 署名が異なる場合はAndroidがインストールを拒否する
            return "$tag をインストール中: " + AppUpdater.install(context, file)
        } finally {
            file.delete()
        }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "HTTPSのみ対応" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "KioskApp/${Build.MODEL}")
        }
    }

    private fun httpGetText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun download(url: String, dest: File) {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            var total = 0L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK_BYTES) throw java.io.IOException("APKが大きすぎます")
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally { c.disconnect() }
    }
}
