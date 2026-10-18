package com.nagifans.kioskapp

import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import java.security.MessageDigest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

class AdminServer(
    private val context: Context,
    private val settings: Settings,
    port: Int = 8080
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
                .apply { addHeader("WWW-Authenticate", "Basic realm=\"Kiosk\"") }
        }
        if (session.uri == "/metrics.json") {
            return newFixedLengthResponse(Response.Status.OK, "application/json", Metrics.json(context))
                .apply { addHeader("Cache-Control", "no-store") }
        }

        if (session.method == Method.POST) {
            // ブラウザ保存済みの認証を悪用した他サイトからのPOSTを拒否する
            val origin = session.headers["origin"]
            val host = session.headers["host"]
            if (origin != null && origin.substringAfter("://") != host) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
            }
            val params = HashMap<String, String>()
            try { session.parseBody(params) } catch (e: Exception) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad request")
            }
            when (session.uri) {
                "/save" -> {
                    val url = session.parms["url"]?.trim().orEmpty()
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        settings.url = url
                        KioskState.url.value = url
                    }
                    val pin = session.parms["pin"].orEmpty()
                    if (pin.length >= 4) settings.pin = pin
                }
                "/reload" -> KioskState.reloadTick.value += 1
                "/brightness" -> {
                    val value = (session.parms["value"]?.toIntOrNull() ?: 0).coerceIn(0, 100)
                    settings.brightness = value
                    KioskState.brightness.value = value
                }
                "/screen-off" -> ScreenControl.off(context)
                "/screen-on" -> ScreenControl.on(context)
                "/schedule" -> {
                    val timeRegex = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
                    val start = session.parms["start"].orEmpty()
                    val end = session.parms["end"].orEmpty()
                    if (timeRegex.matches(start) && timeRegex.matches(end)) {
                        settings.sleepStart = start
                        settings.sleepEnd = end
                        settings.sleepEnabled = session.parms["enabled"] == "on"
                    }
                }
                "/reboot" -> reboot()
                "/memo" -> settings.memo = session.parms["memo"].orEmpty()
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .takeCodePoints(40)
                "/memo/clear" -> settings.memo = ""
                "/appearance" -> {
                    val mode = session.parms["mode"].orEmpty()
                    if (mode == Settings.APPEARANCE_DARK || mode == Settings.APPEARANCE_LIGHT) {
                        settings.appearanceMode = mode
                    }
                    settings.automaticAppearance = session.parms["automatic"] == "on"
                    val dark = session.parms["darkBelow"]?.trim()?.toIntOrNull()
                    val light = session.parms["lightAbove"]?.trim()?.toIntOrNull()
                    if (dark != null && light != null && dark in 0..100000 && light in 0..100000 && dark <= light) {
                        settings.darkBelowLux = dark
                        settings.lightAboveLux = light
                    }
                    AmbientLightMonitor.applyCurrentReading(settings)
                }
                "/sensors" -> {
                    settings.sensorEnabled = session.parms["enabled"] == "on"
                    settings.sensorAddresses = session.parms["addresses"].orEmpty()
                        .split(Regex("[\\s,;]+"))
                        .map { SwitchBotBleMonitor.normalizeAddress(it) }
                        .filter { it.length == 12 && it.all { c -> c in '0'..'9' || c in 'A'..'F' } }
                        .toSet()
                    val names = settings.sensorNames.toMutableMap()
                    SwitchBotBleMonitor.knownIds().forEach { id ->
                        val name = session.parms["name_$id"]?.trim()?.takeCodePoints(8) ?: return@forEach
                        if (name.isEmpty()) names.remove(id) else names[id] = name
                    }
                    settings.sensorNames = names
                    SwitchBotBleMonitor.applySettings(context, settings)
                }
                "/upload" -> {
                    val url = session.parms["url"]?.trim().orEmpty()
                    if (url.isEmpty() || url.startsWith("https://")) settings.uploadUrl = url
                    val token = session.parms["token"].orEmpty()
                    if (token.isNotEmpty()) settings.uploadToken = token
                    if (session.parms["clearToken"] != null) settings.uploadToken = ""
                    settings.uploadEnabled = session.parms["enabled"] == "on"
                }
                "/update-settings" -> {
                    settings.autoUpdate = session.parms["auto"] == "on"
                    if (session.parms["checkNow"] != null) UpdateChecker.checkInBackground(context, settings)
                }
                "/update" -> {
                    val path = params["apk"]
                    settings.lastUpdateResult = if (path == null) {
                        "APKが選択されていません"
                    } else {
                        val file = java.io.File(path)
                        try {
                            AppUpdater.install(context, file)
                        } catch (e: Exception) {
                            "更新を開始できません: ${e.message}"
                        } finally {
                            file.delete()
                        }
                    }
                }
                "/location" -> {
                    val lat = session.parms["lat"]?.trim()?.toDoubleOrNull()
                    val lon = session.parms["lon"]?.trim()?.toDoubleOrNull()
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        settings.latitude = lat.toString()
                        settings.longitude = lon.toString()
                    }
                }
            }
            return redirect()
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
    }

    private fun lastCheckText(): String {
        val t = settings.lastUpdateCheck
        if (t == 0L) return "未確認"
        return java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.JAPAN).format(java.util.Date(t))
    }

    private fun uploadTimeText(): String {
        val t = settings.lastUploadAt
        if (t == 0L) return "未送信"
        return java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.JAPAN).format(java.util.Date(t))
    }

    private fun sensorNameInputs(): String {
        val ids = SwitchBotBleMonitor.knownIds()
        if (ids.isEmpty()) return "<p>センサーを受信後に名前を付けられます</p>"
        val names = settings.sensorNames
        val detected = SwitchBotBleMonitor.detectedAddresses()
            .joinToString("<br>") { esc(it.chunked(2).joinToString(":")) }
        return "<p>受信中のMAC:<br>$detected</p>" + ids.joinToString("") {
            "<label>…${esc(it)}の名前</label><input name=\"name_${esc(it)}\" maxlength=\"8\" value=\"${esc(names[it].orEmpty())}\">"
        }
    }

    private fun redirect() =
        newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, MIME_PLAINTEXT, "")
            .apply { addHeader("Location", "/") }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val header = session.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ")) return false
        val decoded = try {
            String(Base64.decode(header.removePrefix("Basic "), Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) { return false }
        val password = decoded.substringAfter(":", "")
        return MessageDigest.isEqual(password.toByteArray(), settings.pin.toByteArray())
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")
    private fun String.takeCodePoints(max: Int): String =
        substring(0, offsetByCodePoints(0, minOf(max, codePointCount(0, length))))
    private fun admin() = ComponentName(context, AdminReceiver::class.java)
    private fun dpm() = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun reboot() {
        try { dpm().reboot(admin()) } catch (e: SecurityException) { }
    }
    private fun page() = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Kiosk設定</title>
        <style>body{font-family:sans-serif;max-width:560px;margin:2em auto;padding:0 1em}
        input{width:100%;padding:.6em;margin:.3em 0 1em;box-sizing:border-box}
        button{padding:.7em 1.2em}</style></head><body>
        <h2>Kiosk設定</h2>
        <form method="post" action="/save">
          <label>表示URL</label>
          <input name="url" value="${esc(settings.url)}">
          <label>新しいPIN(4文字以上、変更時のみ)</label>
          <input name="pin" type="password" autocomplete="new-password">
          <button type="submit">保存</button>
        </form>
        <form method="post" action="/reload" style="margin-top:1.5em">
          <button type="submit">ページを再読み込み</button>
        </form>
        <hr style="margin:2em 0">
        <h3>一言メモ</h3>
        <form method="post" action="/memo">
          <input type="text" name="memo" maxlength="40" value="${esc(settings.memo)}" placeholder="画面右上に一行で表示 (40文字まで)">
          <button type="submit">保存</button>
          <button type="submit" formaction="/memo/clear" formnovalidate>クリア</button>
        </form>

        <hr style="margin:2em 0">
        <h3>表示モード</h3>
        <form method="post" action="/appearance">
          <select name="mode" style="width:100%;padding:.6em;margin:.3em 0 1em">
            <option value="dark" ${if (settings.appearanceMode == Settings.APPEARANCE_DARK) "selected" else ""}>Solarized Dark</option>
            <option value="light" ${if (settings.appearanceMode == Settings.APPEARANCE_LIGHT) "selected" else ""}>Solarized Light</option>
          </select>
          <label><input type="checkbox" name="automatic" style="width:auto" ${if (settings.automaticAppearance) "checked" else ""}> 周囲の明るさによって表示モードを変更</label>
          <label>Darkに切り替える照度（この値未満, lx）</label>
          <input name="darkBelow" type="number" min="0" max="100000" value="${settings.darkBelowLux}">
          <label>Lightに切り替える照度（この値超, lx）</label>
          <input name="lightAbove" type="number" min="0" max="100000" value="${settings.lightAboveLux}">
          <p>（2つの値の間では現在のモードを維持します。Dark側の値はLight側以下にしてください）</p>
          <button type="submit">表示モードを保存</button>
        </form>

        <hr style="margin:2em 0">
        <h3>天気の位置</h3>
        <form method="post" action="/location">
          <label>緯度</label>
          <input name="lat" value="${esc(settings.latitude)}">
          <label>経度</label>
          <input name="lon" value="${esc(settings.longitude)}">
          <button type="submit">保存</button>
        </form>
        <hr style="margin:2em 0">
        <h3>SwitchBot温湿度計（メモが空のとき右上に表示）</h3>
        <form method="post" action="/sensors">
          <label><input type="checkbox" name="enabled" style="width:auto" ${if (settings.sensorEnabled) "checked" else ""}> 温湿度の取得・表示を有効にする</label><br>
          <label>対象のBLE MACアドレス（1行に1つ。空欄なら近くの温湿度計すべて）</label>
          <textarea name="addresses" rows="3" placeholder="AA:BB:CC:DD:EE:FF">${esc(settings.sensorAddresses.joinToString("\n") { it.chunked(2).joinToString(":") })}</textarea>
          ${sensorNameInputs()}
          <button type="submit">保存</button>
        </form>
        <hr style="margin:2em 0">
        <h3>温湿度の外部送信（1分ごとにPOST）</h3>
        <form method="post" action="/upload">
          <label><input type="checkbox" name="enabled" style="width:auto" ${if (settings.uploadEnabled) "checked" else ""}> 送信を有効にする</label>
          <label>送信先URL（https://のみ）</label>
          <input name="url" value="${esc(settings.uploadUrl)}" placeholder="https://example.com/ingest">
          <label>トークン（${if (settings.uploadToken.isEmpty()) "未設定" else "設定済み"}。変更時のみ入力）</label>
          <input name="token" type="password" autocomplete="new-password">
          <p>最終送信: ${esc(uploadTimeText())} / 結果: ${esc(settings.lastUploadResult.ifEmpty { "なし" })}</p>
          <button type="submit">保存</button>
          <button type="submit" name="clearToken" value="1">トークンを削除</button>
        </form>
        <hr style="margin:2em 0">
        <h3>アプリ更新</h3>
        <form method="post" action="/update-settings">
          <label><input type="checkbox" name="auto" style="width:auto" ${if (settings.autoUpdate) "checked" else ""}> GitHub Releasesから自動更新（6時間ごとに確認）</label>
          <p>最終確認: ${esc(lastCheckText())} / 結果: ${esc(settings.lastUpdateResult.ifEmpty { "なし" })}</p>
          <button type="submit">保存</button>
          <button type="submit" name="checkNow" value="1">保存して今すぐ確認</button>
        </form>
        <p>手動更新（APKをアップロード）</p>
        <form method="post" action="/update" enctype="multipart/form-data" onsubmit="return confirm('APKをインストールします。成功するとアプリが再起動します。')">
          <input type="file" name="apk" accept=".apk,application/vnd.android.package-archive">
          <button type="submit">アップロードして更新</button>
        </form>
        <hr style="margin:2em 0">
        <h3>デバイス状態：30秒ごとに更新</h3>
          <table id="metrics" style="width:100%;border-collapse:collapse"></table>
          <details style="margin-top:.5em"><summary>温度センサー一覧（詳細）</summary><p id="zones"></p></details>
        <script>
        const rows = [
          ['電池', m => m.batteryPercent + '% ' + (m.charging ? '(充電中)' : '') + ' / ' + (m.batteryTempC === null ? '温度取得不可' : m.batteryTempC + '℃')],
          ['CPU温度', m => m.cpuTempC === null ? '取得不可' : m.cpuTempC.toFixed(1) + '℃'],
          ['メモリ空き', m => m.memAvailMb + ' / ' + m.memTotalMb + ' MB'],
          ['ストレージ空き', m => m.storageFreeMb + ' / ' + m.storageTotalMb + ' MB'],
          ['Wi-Fi電波', m => m.wifiRssiDbm + ' dBm'],
          ['周囲の明るさ', m => !m.ambientLightSensorAvailable ? 'センサーなし' : m.ambientLightLux === null ? '測定中...' : m.ambientLightLux.toFixed(1) + ' lx'],
          ['SwitchBot BLE', m => {
            const status = m.switchBotBle.status;
            if (status === 'disabled') return '無効';
            if (status === 'scanning') return '広告をスキャン中';
            if (status === 'permission_required') return '権限が必要です（Androidのアプリ設定を確認）';
            if (status === 'location_off') return '端末の位置情報をONにしてください';
            if (status === 'bluetooth_off') return 'BluetoothがOFFです';
            if (status === 'unavailable') return 'BLEを利用できません';
            if (status.startsWith('scan_error:')) return 'スキャンエラー: ' + status.substring(11);
            return status;
          }],
          ['SwitchBot温湿度', m => m.switchBotBle.sensors.length === 0 ? '直近5分の受信なし' :
            m.switchBotBle.sensors.map(s =>
              (s.name || '…' + s.id) + ': ' + s.temperatureC.toFixed(1) + '℃ / ' + s.humidity + '% / 電池' + s.battery + '% / ' + s.ageSec + '秒前'
            ).join(' | ')],
          ['IP', m => m.ip],
          ['画面', m => m.screenOn ? 'ON' : 'OFF'],
          ['稼働時間', m => Math.floor(m.uptimeSec / 3600) + '時間' + Math.floor(m.uptimeSec % 3600 / 60) + '分'],
          ['アプリ / Android', m => m.appVersion + ' / ' + m.androidVersion],
          ['機種', m => m.model],
          ['表示URL', m => m.url]
        ];
        async function refreshMetrics() {
          try {
            const m = await (await fetch('/metrics.json', { cache: 'no-store' })).json();
            const t = document.getElementById('metrics');
            t.textContent = '';
            rows.forEach(r => {
              const tr = t.insertRow();
              tr.style.borderBottom = '1px solid #ddd';
              tr.insertCell().textContent = r[0];
              tr.insertCell().textContent = r[1](m);
            });
            const zones = document.getElementById('zones');
            zones.textContent = m.thermalZones.length === 0
              ? '読めません（' + m.thermalZoneTotal + '個中0個）'
              : m.thermalZones.map(z => z.type + ' ' + z.tempC.toFixed(1) + '℃').join(' / ');
          } catch (e) {}
        }
        refreshMetrics();
        setInterval(refreshMetrics, 30000);
        </script>
        <hr style="margin:2em 0">
        <h3>明るさ</h3>
        <form method="post" action="/brightness">
          <label>画面の明るさ(0=端末設定に任せる, 1〜100=固定%)</label>
          <input name="value" type="number" min="0" max="100" value="${settings.brightness}">
          <p>（0の場合はAndroidの画面輝度設定に従うため端末側で自動輝度が有効であることを確認すること）</p>
          <button type="submit">適用</button>
        </form>

        <hr style="margin:2em 0">
        <h3>夜間消灯</h3>
        <form method="post" action="/schedule">
          <label><input type="checkbox" name="enabled" style="width:auto" ${if (settings.sleepEnabled) "checked" else ""}> 有効にする</label><br>
          <label>消灯時刻</label>
          <input name="start" type="time" value="${esc(settings.sleepStart)}" required>
          <label>点灯時刻</label>
          <input name="end" type="time" value="${esc(settings.sleepEnd)}" required>
          <button type="submit">保存</button>
        </form>

        <hr style="margin:2em 0">
        <h3>端末操作</h3>
        <div style="display:flex;gap:1em;flex-wrap:wrap">
          <form method="post" action="/screen-off"><button>画面OFF</button></form>
          <form method="post" action="/screen-on"><button>画面ON</button></form>
          <form method="post" action="/reboot" onsubmit="return confirm('再起動しますか？')"><button>再起動</button></form>
        </div>
        </body></html>
    """.trimIndent()
}