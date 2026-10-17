package com.sdvideohub.app

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import java.io.FilterInputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var secure: SecureCredentials
    private lateinit var cache: OfflineCache
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private var webView: WebView? = null
    private var modernBackRegistered = false
    private val modernBackCallback = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { handleWebBack() } else null
    private var receiverRegistered = false
    private val syncStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showSyncStatus(intent?.getStringExtra(OfflineSyncService.EXTRA_MESSAGE).orEmpty())
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); secure = SecureCredentials(this); cache = OfflineCache(this)
        val saved = secure.load()
        showLogin(saved)
        if (saved != null && hasValidatedNetwork()) OfflineSyncService.start(this)
    }

    private fun field(hint: String, value: String = "") = EditText(this).apply {
        this.hint = hint; setText(value); setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
        setBackgroundColor(Color.rgb(42, 43, 45)); setPadding(24, 12, 24, 12)
    }
    private fun showLogin(saved: Credentials?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(32, 32, 32, 32); setBackgroundColor(Color.rgb(17,18,20)) }
        val base = field("Server URL", saved?.baseUrl ?: "http://10.66.66.200:8787")
        val user = field("Username", saved?.username ?: "")
        val password = field("Password", saved?.password ?: "").apply { inputType = 0x81 }
        val login = Button(this).apply { text = "Login" }
        status = TextView(this).apply { setTextColor(Color.LTGRAY); gravity = Gravity.CENTER; visibility = View.GONE; setPadding(8, 18, 8, 0) }
        listOf(base, user, password, login, status).forEach { view -> box.addView(view, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 8, 0, 8) }) }
        setContentView(box)
        login.setOnClickListener {
            val url = base.text.toString().trim().trimEnd('/')
            val credentials = Credentials(url, user.text.toString(), password.text.toString())
            if (!(url.startsWith("http://") || url.startsWith("https://")) || Uri.parse(url).host.isNullOrBlank()) {
                status.text = "Enter a valid http(s) server URL"; status.visibility = View.VISIBLE; return@setOnClickListener
            }
            login.isEnabled = false; status.text = "Signing in…"; status.visibility = View.VISIBLE
            if (saved == credentials && cache.isComplete("/") && !hasValidatedNetwork()) {
                showWeb(credentials)
                statusText("Offline copy")
                return@setOnClickListener
            }
            worker.execute {
                try {
                    fetchManifest(credentials)
                    // Parsing the authenticated response completes login validation;
                    // the service deliberately fetches it again as the sync owner.
                    secure.save(credentials)
                    runOnUiThread {
                        showWeb(credentials)
                        statusText("Starting offline sync…")
                        requestNotificationPermissionIfNeeded()
                        OfflineSyncService.start(this)
                    }
                } catch (error: Exception) {
                    if (saved == credentials && cache.file("/").isFile) {
                        runOnUiThread { showWeb(credentials); statusText("Offline copy") }
                    } else runOnUiThread { login.isEnabled = true; status.text = "Login failed: ${error.message ?: "server unavailable"}" }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(credentials: Credentials) {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = WebChromeClient()
            webViewClient = CacheClient(credentials)
        }
        webView = web
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0xB0000000.toInt()); setPadding(18, 10, 18, 10); textSize = 12f }
        val statusMargin = (12 * resources.displayMetrics.density).toInt()
        val statusLayout = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(statusMargin, statusMargin, statusMargin, statusMargin)
        }
        root.addView(status, statusLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusLayout.bottomMargin = statusMargin + systemBars.bottom
            status.layoutParams = statusLayout
            insets
        }
        setContentView(root)
        registerModernBackCallback()
        web.loadUrl(credentials.baseUrl + "/", mapOf("Authorization" to Network.authorization(credentials)))
    }

    private fun statusText(value: String) = runOnUiThread { status.text = value; status.visibility = if (value.isBlank()) View.GONE else View.VISIBLE }
    private fun showSyncStatus(value: String) {
        if (value.isBlank()) return
        val notificationsHidden = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        statusText(if (notificationsHidden) "$value · notifications hidden" else value)
        if (!notificationsHidden && (value == "Up to date" || value == "Offline copy updated")) {
            status.postDelayed({ statusText("") }, 1800)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val preferences = getSharedPreferences("notification-permission", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("asked", false)) {
            preferences.edit().putBoolean("asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        } else {
            showSyncStatus("Sync continues in background")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            showSyncStatus("Sync continues in background")
        }
    }
    private fun fetchManifest(credentials: Credentials): List<ManifestEntry> {
        val connection = Network.open(credentials.baseUrl, "/api/offline-manifest", credentials)
        try {
            if (connection.responseCode != 200) throw IllegalStateException(if (connection.responseCode == 401) "invalid credentials" else "HTTP ${connection.responseCode}")
            return ManifestCodec.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private inner class CacheClient(private val credentials: Credentials) : WebViewClient() {
        override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler, host: String?, realm: String?) = handler.proceed(credentials.username, credentials.password)
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
            if (request.method != "GET") return null
            val uri = request.url
            val base = Uri.parse(credentials.baseUrl)
            if (uri.scheme != base.scheme || uri.host != base.host || effectivePort(uri) != effectivePort(base)) return null
            val path = uri.encodedPath + (uri.encodedQuery?.let { "?$it" } ?: "")
            val range = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
            when (RequestRouting.select(path, hasValidatedNetwork(), cache.isComplete(path))) {
                RequestRoute.NATIVE -> return null
                RequestRoute.CACHE_ONLY -> return cache.response(path, range)
                RequestRoute.NETWORK_THEN_CACHE -> Unit
            }
            try {
                val connection = Network.open(credentials.baseUrl, path, credentials, range)
                val code = connection.responseCode
                if (code in 200..299) {
                    val mime = connection.contentType?.substringBefore(';') ?: "application/octet-stream"
                    val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(", ") }
                    val stream = object : FilterInputStream(connection.inputStream) { override fun close() { super.close(); connection.disconnect() } }
                    return WebResourceResponse(mime, connection.contentEncoding, code, connection.responseMessage ?: "OK", headers, stream)
                }
                connection.disconnect()
            } catch (_: Exception) { }
            return cache.response(path, range)
        }
        private fun effectivePort(uri: Uri) = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
    }

    override fun onStart() {
        super.onStart()
        registerModernBackCallback()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this, syncStatusReceiver, IntentFilter(OfflineSyncService.ACTION_STATUS),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        val syncStatus = getSharedPreferences(OfflineSyncService.STATUS_PREFERENCES, Context.MODE_PRIVATE)
        showSyncStatus(syncStatus.getString(OfflineSyncService.KEY_MESSAGE, "").orEmpty())
    }

    override fun onStop() {
        unregisterModernBackCallback()
        if (receiverRegistered) {
            unregisterReceiver(syncStatusReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView == null) super.onBackPressed() else handleWebBack()
    }

    private fun handleWebBack() {
        val web = webView ?: return
        val current = web.url?.let(Uri::parse)
        val expected = secure.load()?.baseUrl?.let(Uri::parse)
        val trusted = current != null && expected != null && current.scheme == expected.scheme &&
            current.host == expected.host && effectivePort(current) == effectivePort(expected)
        if (!trusted) return
        web.evaluateJavascript(
            "(()=>{const v=document.getElementById('viewer');if(v&&!v.hidden&&typeof closeViewer==='function'){closeViewer();return true}return false})()",
            null,
        )
        // Both a visible viewer and the site root consume Back; the Activity stays open.
    }

    private fun registerModernBackCallback() {
        if (Build.VERSION.SDK_INT >= 33 && webView != null && !modernBackRegistered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, modernBackCallback!!,
            )
            modernBackRegistered = true
        }
    }

    private fun unregisterModernBackCallback() {
        if (Build.VERSION.SDK_INT >= 33 && modernBackRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(modernBackCallback!!)
            modernBackRegistered = false
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun effectivePort(uri: Uri) = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 701
    }
}
