package com.sdvideohub.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.FilterInputStream
import java.net.HttpURLConnection
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var secure: SecureCredentials
    private lateinit var cache: OfflineCache
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    @Volatile private var active: Credentials? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); secure = SecureCredentials(this); cache = OfflineCache(this)
        showLogin(secure.load())
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
            worker.execute {
                try {
                    val manifest = fetchManifest(credentials)
                    secure.save(credentials); active = credentials
                    runOnUiThread { showWeb(credentials); statusText("Synchronizing…") }
                    synchronize(credentials, manifest)
                } catch (error: Exception) {
                    if (saved == credentials && cache.file("/").isFile) {
                        active = credentials
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
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply { setTextColor(Color.WHITE); setBackgroundColor(0xB0000000.toInt()); setPadding(18, 10, 18, 10); textSize = 12f }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(12, 12, 12, 12) })
        setContentView(root)
        web.loadUrl(credentials.baseUrl + "/", mapOf("Authorization" to Network.authorization(credentials)))
    }

    private fun statusText(value: String) = runOnUiThread { status.text = value; status.visibility = if (value.isBlank()) View.GONE else View.VISIBLE }
    private fun fetchManifest(credentials: Credentials): List<ManifestEntry> {
        val connection = Network.open(credentials.baseUrl, "/api/offline-manifest", credentials)
        try {
            if (connection.responseCode != 200) throw IllegalStateException(if (connection.responseCode == 401) "invalid credentials" else "HTTP ${connection.responseCode}")
            return ManifestCodec.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun synchronize(credentials: Credentials, manifest: List<ManifestEntry> = fetchManifest(credentials)) {
        try {
            val changed = ManifestCodec.changed(cache.versions(), manifest)
            changed.forEachIndexed { index, entry ->
                statusText("Sync ${index + 1}/${changed.size}")
                val connection = Network.open(credentials.baseUrl, entry.url, credentials)
                val temporary = File(cache.file(entry.url).parentFile, cache.file(entry.url).name + ".part")
                try {
                    if (connection.responseCode != 200) throw IllegalStateException("${entry.url}: HTTP ${connection.responseCode}")
                    connection.inputStream.use { input -> temporary.outputStream().use(input::copyTo) }
                    cache.install(entry, temporary)
                } finally { connection.disconnect(); if (temporary.exists()) temporary.delete() }
            }
            cache.prune(manifest.map { it.url }.toSet())
            statusText(if (changed.isEmpty()) "Up to date" else "Offline copy updated")
            status.postDelayed({ statusText("") }, 1800)
        } catch (error: Exception) { statusText("Offline copy retained · sync unavailable") }
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

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
