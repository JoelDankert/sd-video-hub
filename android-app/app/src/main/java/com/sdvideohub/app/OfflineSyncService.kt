package com.sdvideohub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class OfflineSyncService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground("Preparing offline sync…", 0, 0)
        if (!running.compareAndSet(false, true)) return START_REDELIVER_INTENT
        publish(STATE_RUNNING, "Preparing offline sync…")
        executor.execute { synchronize() }
        return START_REDELIVER_INTENT
    }

    private fun synchronize() {
        val cache = OfflineCache(this)
        try {
            val credentials = SecureCredentials(this).load()
                ?: throw MissingCredentialsException()
            val manifest = fetchManifest(credentials)
            val plan = SyncPlanner.plan(cache.versions(), manifest)
            val total = plan.downloads.size
            if (total == 0) {
                enterForeground("Offline copy is up to date", 0, 0)
            }
            plan.downloads.forEachIndexed { index, entry ->
                val current = index + 1
                enterForeground("Syncing item $current of $total", current, total)
                publish(STATE_RUNNING, "Sync $current/$total")
                download(credentials, cache, entry)
            }
            // Deletions are committed only after every replacement is safely installed.
            cache.prune(plan.validUrls)
            setPending(false)
            publish(STATE_SUCCESS, if (total == 0) "Up to date" else "Offline copy updated")
        } catch (_: MissingCredentialsException) {
            publish(STATE_FAILURE, "Sync skipped · sign in required")
        } catch (_: Exception) {
            publish(STATE_FAILURE, "Offline copy retained · sync unavailable")
        } finally {
            running.set(false)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fetchManifest(credentials: Credentials): List<ManifestEntry> {
        val connection = Network.open(credentials.baseUrl, "/api/offline-manifest", credentials)
        try {
            if (connection.responseCode != 200) {
                throw IllegalStateException("Manifest HTTP ${connection.responseCode}")
            }
            return ManifestCodec.parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun download(credentials: Credentials, cache: OfflineCache, entry: ManifestEntry) {
        val destination = cache.file(entry.url)
        val temporary = File(destination.parentFile, destination.name + ".part")
        val connection = Network.open(credentials.baseUrl, entry.url, credentials)
        try {
            if (connection.responseCode != 200) {
                throw IllegalStateException("${entry.url}: HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input -> temporary.outputStream().use(input::copyTo) }
            cache.install(entry, temporary)
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun enterForeground(text: String, progress: Int, total: Int) {
        val notification = notification(text, progress, total)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String, progress: Int, total: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(getString(R.string.sync_notification_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(if (total == 0) 0 else total, progress, total == 0)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.sync_channel_description) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun publish(state: String, message: String) {
        getSharedPreferences(STATUS_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_STATE, state).putString(KEY_MESSAGE, message).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_STATE, state).putExtra(EXTRA_MESSAGE, message))
    }

    private fun setPending(value: Boolean) {
        getSharedPreferences(STATUS_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PENDING, value).commit()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class MissingCredentialsException : Exception()

    companion object {
        const val ACTION_STATUS = "com.sdvideohub.app.OFFLINE_SYNC_STATUS"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val STATE_RUNNING = "running"
        const val STATE_SUCCESS = "success"
        const val STATE_FAILURE = "failure"
        const val STATUS_PREFERENCES = "offline-sync-status"
        const val KEY_STATE = "state"
        const val KEY_MESSAGE = "message"
        const val KEY_PENDING = "pending"
        private const val CHANNEL_ID = "offline-sync"
        private const val NOTIFICATION_ID = 41
        private val running = AtomicBoolean(false)

        fun start(context: Context) {
            context.getSharedPreferences(STATUS_PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PENDING, true).commit()
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, OfflineSyncService::class.java),
            )
        }
    }
}
