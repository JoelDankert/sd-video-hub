package com.sdvideohub.app

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager

class MainActivity : Activity() {
    private lateinit var secure: SecureCredentials
    private lateinit var cache: OfflineCache
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private var webView: WebView? = null
    private var localServer: LoopbackHttpServer? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var systemUiBeforeFullscreen: SystemUiState? = null
    private val fullscreenSession = FullscreenSessionState()
    private val activeSyncIds = mutableSetOf<String>()
    private var modernBackRegistered = false
    private val modernBackCallback = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { handleWebBack() } else null
    private val syncObserver = Observer<List<WorkInfo>> { work ->
        val snapshots = work.map { SyncWorkSnapshot(it.id.toString(), it.state.toSyncWorkState()) }
        val selected = SyncWorkSelector.select(snapshots, activeSyncIds) ?: return@Observer
        val info = work.first { it.id.toString() == selected.id }
        if (selected.state in setOf(SyncWorkState.ENQUEUED, SyncWorkState.RUNNING, SyncWorkState.BLOCKED)) {
            activeSyncIds += selected.id
        } else if (selected.id !in activeSyncIds) {
            return@Observer
        }
        val reported = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                info.progress.getString(OfflineSyncWorker.KEY_MESSAGE) ?: "Waiting for network"
            WorkInfo.State.RUNNING ->
                info.progress.getString(OfflineSyncWorker.KEY_MESSAGE) ?: "Preparing offline sync…"
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED ->
                info.outputData.getString(OfflineSyncWorker.KEY_MESSAGE).orEmpty()
            WorkInfo.State.CANCELLED -> ""
        }
        showSyncStatus(reported)
        if (info.state == WorkInfo.State.SUCCEEDED) {
            activeSyncIds -= selected.id
            reloadWebFromCache()
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); secure = SecureCredentials(this); cache = OfflineCache(this)
        val saved = secure.load()
        if (saved != null) {
            showLogin(saved)
            activeSyncIds += OfflineSyncWorker.enqueue(this).toString()
        } else {
            showLogin(saved)
        }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(OfflineSyncWorker.UNIQUE_WORK_NAME)
            .observeForever(syncObserver)
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
            login.isEnabled = false
            secure.save(credentials)
            cache = OfflineCache(this)
            showWeb(credentials)
            statusText(if (hasCachedShell()) "Offline copy" else "Connecting…")
            requestNotificationPermissionIfNeeded()
            activeSyncIds += OfflineSyncWorker.enqueue(this).toString()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWeb(credentials: Credentials) {
        localServer?.close()
        val server = LoopbackHttpServer(cache.repository, credentials.baseUrl, credentials) { message ->
            Log.d("SDH/Reliability", message)
        }.start()
        localServer = server
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val web = WebView(this).apply {
            setBackgroundColor(Color.rgb(17, 18, 20))
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (view == null || callback == null || !fullscreenSession.begin()) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    val insets = ViewCompat.getRootWindowInsets(window.decorView)
                    systemUiBeforeFullscreen = SystemUiState(
                        statusBarsVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true,
                        navigationBarsVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true,
                        behavior = WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior,
                    )
                    customView = view
                    customViewCallback = callback
                    view.setBackgroundColor(Color.BLACK)
                    root.addView(view, FrameLayout.LayoutParams(-1, -1))
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }

                override fun onHideCustomView() {
                    hideCustomView()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest): Boolean {
                    val uri = request.url
                    return uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port != server.port
                }
            }
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
        val cookie = "${LoopbackHttpServer.COOKIE_NAME}=${server.secret}; Path=/; HttpOnly; SameSite=Strict"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setCookie(server.origin, cookie) {
            web.post { web.loadUrl(server.origin + "/") }
        }
    }

    private fun hasCachedShell() = cache.isComplete("/") && cache.isComplete("/api/state")

    private fun reloadWebFromCache() {
        secure.load() ?: return
        val web = webView ?: return
        if (!hasCachedShell()) return
        val server = localServer ?: return
        // Refresh the site's data, not its document: a successful background sync must
        // never tear down the active player or native fullscreen session.
        web.evaluateJavascript(WebPageScripts.REFRESH) { refreshed ->
            if (refreshed == "false" && webView === web && localServer === server && customView == null) {
                web.loadUrl(server.origin + "/")
            }
        }
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
    override fun onStart() {
        super.onStart()
        registerModernBackCallback()
    }

    override fun onStop() {
        unregisterModernBackCallback()
        super.onStop()
    }

    override fun onDestroy() {
        hideCustomView()
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(OfflineSyncWorker.UNIQUE_WORK_NAME)
            .removeObserver(syncObserver)
        localServer?.close()
        localServer = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView == null) super.onBackPressed() else handleWebBack()
    }

    private fun handleWebBack() {
        if (fullscreenSession.isActive) {
            hideCustomView()
            return
        }
        val web = webView ?: return
        val current = web.url?.let(Uri::parse)
        val server = localServer
        val trusted = current != null && server != null && current.scheme == "http" &&
            current.host == "127.0.0.1" && current.port == server.port
        if (!trusted) return
        web.evaluateJavascript(
            "(()=>{const v=document.getElementById('viewer');if(v&&!v.hidden&&typeof closeViewer==='function'){closeViewer();return true}return false})()",
            null,
        )
        // Both a visible viewer and the site root consume Back; the Activity stays open.
    }

    private fun hideCustomView() {
        if (!fullscreenSession.end()) return
        val view = customView
        val callback = customViewCallback
        val previousUi = systemUiBeforeFullscreen
        customView = null
        customViewCallback = null
        systemUiBeforeFullscreen = null
        if (view?.parent === root) root.removeView(view)
        previousUi?.restore(WindowCompat.getInsetsController(window, window.decorView))
        callback?.onCustomViewHidden()
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

    private fun WorkInfo.State.toSyncWorkState() = when (this) {
        WorkInfo.State.ENQUEUED -> SyncWorkState.ENQUEUED
        WorkInfo.State.RUNNING -> SyncWorkState.RUNNING
        WorkInfo.State.BLOCKED -> SyncWorkState.BLOCKED
        WorkInfo.State.SUCCEEDED -> SyncWorkState.SUCCEEDED
        WorkInfo.State.FAILED -> SyncWorkState.FAILED
        WorkInfo.State.CANCELLED -> SyncWorkState.CANCELLED
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_REQUEST = 701
    }
}
