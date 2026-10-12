package com.nagifans.kioskapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {
    private var server: AdminServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableKioskMode()
        val settings = Settings(applicationContext)
        KioskState.url.value = settings.url
        KioskState.brightness.value = settings.brightness
        server = AdminServer(applicationContext, settings).also {
            try { it.start() } catch (e: java.io.IOException) { /* ポート使用中などは無視 */ }
        }
        SleepScheduler(applicationContext, settings).start(lifecycleScope)
        setContent { KioskApp(settings) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    private fun enableKioskMode() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(packageName)) {
            val admin = ComponentName(this, AdminReceiver::class.java)
            dpm.setLockTaskPackages(admin, arrayOf(packageName))
            dpm.setKeyguardDisabled(admin, true)
            startLockTask()
        }
    }

    fun disableKioskMode() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        stopLockTask()
        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.clearDeviceOwnerApp(packageName)
        }
        finish()
    }
    fun applyBrightness(percent: Int) {
        val attrs = window.attributes
        attrs.screenBrightness = if (percent <= 0) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            percent / 100f
        }
        window.attributes = attrs
    }
}
@Composable
fun KioskApp(settings: Settings) {
    val url by KioskState.url.collectAsState()
    val reloadTick by KioskState.reloadTick.collectAsState()
    var showPin by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val brightness by KioskState.brightness.collectAsState()
    LaunchedEffect(brightness) {
        (context as? MainActivity)?.applyBrightness(brightness)
    }
    LaunchedEffect(reloadTick) {
        if (reloadTick > 0) webView?.reload()
    }

    BackHandler(enabled = true) {
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    this.settings.javaScriptEnabled = true
                    this.settings.domStorageEnabled = true
                    webViewClient = kioskClient(context, settings)
                    @Suppress("DEPRECATION")
                    this.settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
                    webView = this
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                if (view.tag != url) {
                    view.tag = url
                    view.loadUrl(url)
                }
            }
        )

        SecretTapZone(
            modifier = Modifier.align(Alignment.TopEnd),
            onTriggered = { showPin = true }
        )
    }

    if (showPin) {
        PinDialog(
            correctPin = settings.pin,
            onSuccess = { showPin = false; showSettings = true },
            onDismiss = { showPin = false }
        )
    }

    if (showSettings) {
        SettingsDialog(
            currentUrl = url,
            onSave = { newUrl, newPin ->
                settings.url = newUrl
                KioskState.url.value = newUrl
                if (newPin.isNotBlank()) settings.pin = newPin
                showSettings = false
            },
            onExit = {
                (context as? MainActivity)?.disableKioskMode()
                showSettings = false
            },
            onDismiss = { showSettings = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SecretTapZone(modifier: Modifier, onTriggered: () -> Unit) {
    var count by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }

    Box(
        modifier = modifier
            .size(96.dp)
            .pointerInput(Unit) {
                detectTapGestures {
                    val now = SystemClock.elapsedRealtime()
                    count = if (now - lastTap > 2000) 1 else count + 1
                    lastTap = now
                    if (count >= 5) {
                        count = 0
                        onTriggered()
                    }
                }
            }
    )
}

@Composable
fun PinDialog(correctPin: String, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PIN") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; error = false },
                singleLine = true,
                isError = error,
                label = { Text(if (error) "PINが違います" else "PINを入力") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
        },
        confirmButton = {
            TextButton(onClick = { if (input == correctPin) onSuccess() else error = true }) {
                Text("OK")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
fun SettingsDialog(
    currentUrl: String,
    onSave: (url: String, newPin: String) -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    var newPin by remember { mutableStateOf("") }
    val urlValid = url.startsWith("https://") || url.startsWith("http://")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    singleLine = true,
                    isError = !urlValid,
                    label = { Text("表示URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
//                OutlinedTextField(
//                    value = newPin,
//                    onValueChange = { newPin = it },
//                    singleLine = true,
//                    label = { Text("新しいPIN(変更する場合のみ)") },
//                    visualTransformation = PasswordVisualTransformation(),
//                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
//                )
                TextButton(onClick = onExit) { Text("キオスク解除") }
            }
        },
        confirmButton = {
            TextButton(enabled = urlValid, onClick = { onSave(url, newPin) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        }
    )
}
