package com.nagifans.kioskapp

import android.content.Context
import android.net.ConnectivityManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.net.Inet4Address

fun kioskClient(context: Context, settings: Settings): WebViewClient {
    val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    return object : WebViewClient() {
        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            android.util.Log.e("KioskWeb", "${request.url} -> ${error.errorCode} ${error.description}")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            android.util.Log.e("KioskWeb", "${request.url} -> HTTP ${errorResponse.statusCode}")
        }
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val uri = request.url
            if (uri.host == WebViewAssetLoader.DEFAULT_DOMAIN && uri.path == "/api/state.json") {
                return stateResponse(context, settings)
            }
            return loader.shouldInterceptRequest(uri)
        }
    }
}

private fun stateResponse(context: Context, settings: Settings): WebResourceResponse {
    val ip = localIp(context)
    val json = JSONObject()
        .put("memo", settings.memo)
        .put("lat", settings.latitude)
        .put("lon", settings.longitude)
        .put(
            "appearanceMode",
            settings.appearanceMode.takeIf {
                it == Settings.APPEARANCE_DARK || it == Settings.APPEARANCE_LIGHT
            } ?: Settings.APPEARANCE_DARK
        )
        .put("automaticAppearance", settings.automaticAppearance)
        .put("sensors", SwitchBotBleMonitor.readings(settings.sensorNames))
        .put("adminUrl", if (ip != null) "http://$ip:8080/" else "")
    return WebResourceResponse("application/json", "utf-8", json.toString().byteInputStream())
        .apply { responseHeaders = mapOf("Cache-Control" to "no-store") }
}

fun localIp(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return props.linkAddresses.map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}