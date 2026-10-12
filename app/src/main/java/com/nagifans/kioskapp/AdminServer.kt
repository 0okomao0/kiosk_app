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
                "/background" -> {
                    val color = session.parms["color"].orEmpty()
                    if (color in Settings.BACKGROUND_COLORS) settings.backgroundColor = color
                }
                "/appearance" -> {
                    val mode = session.parms["mode"].orEmpty()
                    if (mode == Settings.APPEARANCE_DARK || mode == Settings.APPEARANCE_LIGHT) {
                        settings.appearanceMode = mode
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
          <button type="submit">表示モードを保存</button>
        </form>

        <hr style="margin:2em 0">
        <h3>背景色</h3>
        <p>背景色はダークモード時に使用します。</p>
        <form method="post" action="/background">
          <select name="color" style="width:100%;padding:.6em;margin:.3em 0 1em">
            <option value="#002b36" ${if (settings.backgroundColor == "#002b36") "selected" else ""}>Solarized Dark</option>
            <option value="#073642" ${if (settings.backgroundColor == "#073642") "selected" else ""}>Solarized Dark (少し明るめ)</option>
            <option value="#000000" ${if (settings.backgroundColor == "#000000") "selected" else ""}>黒</option>
            <option value="#202124" ${if (settings.backgroundColor == "#202124") "selected" else ""}>濃いグレー</option>
            <option value="#10243A" ${if (settings.backgroundColor == "#10243A") "selected" else ""}>濃い紺</option>
            <option value="#18332F" ${if (settings.backgroundColor == "#18332F") "selected" else ""}>濃い緑</option>
          </select>
          <button type="submit">背景色を保存</button>
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
        <h3>デバイス状態</h3>
        <table id="metrics" style="width:100%;border-collapse:collapse"></table>
        <script>
        const rows = [
          ['電池', m => m.batteryPercent + '% ' + (m.charging ? '(充電中)' : '') + ' / ' + m.batteryTempC + '℃'],
          ['メモリ空き', m => m.memAvailMb + ' / ' + m.memTotalMb + ' MB'],
          ['ストレージ空き', m => m.storageFreeMb + ' / ' + m.storageTotalMb + ' MB'],
          ['Wi-Fi電波', m => m.wifiRssiDbm + ' dBm'],
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
          } catch (e) {}
        }
        refreshMetrics();
        setInterval(refreshMetrics, 30000);
        </script>
        <hr style="margin:2em 0">
        <h3>明るさ</h3>
        <form method="post" action="/brightness">
          <label>明るさ(0=自動, 1〜100)</label>
          <input name="value" type="number" min="0" max="100" value="${settings.brightness}">
          <button type="submit">適用</button>
        </form>

        <hr style="margin:2em 0">
        <h3>夜間消灯</h3>
        <form method="post" action="/schedule">
          <label><input type="checkbox" name="enabled" style="width:auto" ${if (settings.sleepEnabled) "checked" else ""}> 有効にする</label>
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