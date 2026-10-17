package com.sdvideohub.app

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.work.WorkInfo
import androidx.work.WorkManager

class MainActivity : Activity() {
    private lateinit var secure: SecureCredentials
    private lateinit var cache: OfflineCache
    private lateinit var root: FrameLayout
    private lateinit var status: TextView
    private var webView: WebView? = null
    @Volatile private var localServer: LoopbackHttpServer? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var systemUiBeforeFullscreen: SystemUiState? = null
    private val fullscreenSession = FullscreenSessionState()
    private val activeSyncIds = mutableSetOf<String>()
    private var modernBackRegistered = false
    private var pendingSafDownload: NativeDownload? = null
    private var pendingSafIdentity: String? = null
    @Volatile private var activeCredentials: Credentials? = null
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
        state?.getBundle(SAF_PENDING_STATE)?.let { pending ->
            val path = pending.getString("path")
            val kind = pending.getString("kind")?.let { runCatching { DownloadKind.valueOf(it) }.getOrNull() }
            val name = pending.getString("name")
            val identity = pending.getString("identity")
            if (path != null && kind != null && name != null && identity != null) {
                pendingSafDownload = NativeDownload(path, kind, name)
                pendingSafIdentity = identity
            }
        }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.remove(SAF_PENDING_STATE)
        val request = pendingSafDownload ?: return
        val identity = pendingSafIdentity ?: return
        outState.putBundle(SAF_PENDING_STATE, Bundle().apply {
            putString("path", request.path)
            putString("kind", request.kind.name)
            putString("name", request.suggestedName)
            putString("identity", identity)
        })
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
        activeCredentials = credentials
        localServer?.close()
        val server = LoopbackHttpServer(cache.repository, credentials.baseUrl, credentials) { message ->
            Log.d("SDH/Reliability", message)
        }.start()
        localServer = server
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val web = WebView(this).apply {
            setBackgroundColor(Color.rgb(17, 18, 20))
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(NativePageBridge(server.origin), "SDHNative")
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val stored = settingsJson(credentials)
                val script = SettingsBootstrap.script(stored).replace("__SDH_ORIGIN__", org.json.JSONObject.quote(server.origin))
                WebViewCompat.addDocumentStartJavaScript(this, script, setOf(server.origin))
            }
            setDownloadListener { url, _, contentDisposition, _, _ ->
                val request = DownloadRouting.fromLoopbackUrl(url, server.origin) ?: return@setDownloadListener
                val named = request.copy(suggestedName = DownloadRouting.contentDispositionFilename(contentDisposition, URLUtil.guessFileName(url, contentDisposition, "video/mp4")))
                queueDownload(named)
            }
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
                    DownloadRouting.fromLoopbackUrl(uri.toString(), server.origin)?.let {
                        queueDownload(it)
                        return true
                    }
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

    private inner class NativePageBridge(private val trustedOrigin: String) {
        private fun trusted(origin: String): Boolean = origin == trustedOrigin && localServer?.origin == trustedOrigin
        // JavaScript-interface callbacks run off the UI thread: never call WebView.getUrl() here.
        // Navigation stays restricted to this loopback origin by the WebViewClient above.

        @JavascriptInterface fun captureDownload(origin: String, url: String): Boolean {
            if (!trusted(origin)) return false
            val request = DownloadRouting.fromLoopbackUrl(url, trustedOrigin) ?: return false
            runOnUiThread { queueDownload(request) }
            return true
        }

        @JavascriptInterface fun loadSettings(origin: String): String {
            val credentials = activeCredentials ?: return "{}"
            return if (trusted(origin)) settingsJson(credentials) else "{}"
        }

        @JavascriptInterface fun saveSetting(origin: String, key: String, value: String) {
            if (!trusted(origin) || key !in SettingsBootstrap.keys || value !in setOf("true", "false")) return
            val credentials = activeCredentials ?: return
            settingPreferences(credentials).edit().putString(key, value).commit()
        }
    }

    private fun settingPreferences(credentials: Credentials) = getSharedPreferences(
        "site-settings-" + OfflineCache.identityKey("${credentials.baseUrl.trimEnd('/')}\n${credentials.username}"), Context.MODE_PRIVATE,
    )

    private fun settingsJson(credentials: Credentials): String {
        val prefs = settingPreferences(credentials)
        val json = org.json.JSONObject()
        SettingsBootstrap.keys.forEach { key -> prefs.getString(key, null)?.let { json.put(key, it) } }
        return json.toString()
    }

    private fun queueDownload(request: NativeDownload) {
        val credentials = activeCredentials ?: return
        if (Build.VERSION.SDK_INT < 29) {
            pendingSafDownload = request
            pendingSafIdentity = VideoDownloadWorker.identity(credentials)
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "video/mp4"
                putExtra(Intent.EXTRA_TITLE, request.suggestedName)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, SAF_DOWNLOAD_REQUEST)
            return
        }
        observeDownload(VideoDownloadWorker.enqueue(this, request, credentials))
        Toast.makeText(this, "Download queued", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SAF_DOWNLOAD_REQUEST) return
        val request = pendingSafDownload
        val expectedIdentity = pendingSafIdentity
        pendingSafDownload = null
        pendingSafIdentity = null
        if (resultCode != RESULT_OK) return
        val target = data?.data
        if (request == null || expectedIdentity == null || target == null) {
            Toast.makeText(this, "Download could not be resumed. Please try again.", Toast.LENGTH_LONG).show()
            return
        }
        // The picker may recreate this Activity without showWeb() ever being called.
        val credentials = secure.load()
        if (credentials == null || VideoDownloadWorker.identity(credentials) != expectedIdentity) {
            Toast.makeText(this, "Download cancelled: saved account changed or is unavailable. Please try again.", Toast.LENGTH_LONG).show()
            return
        }
        runCatching { contentResolver.takePersistableUriPermission(target, Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        observeDownload(VideoDownloadWorker.enqueue(this, request, credentials, target))
        Toast.makeText(this, "Download queued", Toast.LENGTH_SHORT).show()
    }

    private fun observeDownload(id: java.util.UUID) {
        val live = WorkManager.getInstance(this).getWorkInfoByIdLiveData(id)
        lateinit var observer: Observer<WorkInfo?>
        observer = Observer { info ->
            if (info?.state?.isFinished == true) {
                val message = info.outputData.getString(VideoDownloadWorker.KEY_MESSAGE)
                    ?: if (info.state == WorkInfo.State.SUCCEEDED) "Video saved" else "Download failed"
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                live.removeObserver(observer)
            }
        }
        live.observeForever(observer)
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
        private const val SAF_DOWNLOAD_REQUEST = 702
        private const val SAF_PENDING_STATE = "pendingSafDownload"
    }
}
