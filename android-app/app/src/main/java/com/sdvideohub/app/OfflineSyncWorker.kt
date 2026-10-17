package com.sdvideohub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

class OfflineSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val credentials = SecureCredentials(applicationContext).load()
            ?: return failure("Sync skipped · sign in required")
        val cache = OfflineCache(applicationContext)
        return try {
            publishForeground("Preparing offline sync…", 0, 0)
            val manifest = fetchManifest(credentials)
            val plan = SyncPlanner.plan(cache.versions(), manifest)
            val total = plan.downloads.size
            plan.downloads.forEachIndexed { index, entry ->
                if (isStopped) throw IOException("Sync stopped")
                val current = index + 1
                publishForeground("Syncing item $current of $total", current, total)
                download(credentials, cache, entry)
            }
            // Never prune until every new/changed resource is safely installed.
            cache.prune(plan.validUrls)
            Result.success(status(if (total == 0) "Up to date" else "Offline copy updated"))
        } catch (error: HttpFailure) {
            if (error.temporary) retry("Waiting to retry offline sync")
            else failure("Offline copy retained · ${error.message}")
        } catch (_: IOException) {
            retry("Waiting to retry offline sync")
        } catch (_: Exception) {
            failure("Offline copy retained · sync unavailable")
        }
    }

    private fun fetchManifest(credentials: Credentials): List<ManifestEntry> {
        val connection = Network.open(credentials.baseUrl, "/api/offline-manifest", credentials)
        try {
            requireSuccess(connection, "manifest")
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
            requireSuccess(connection, entry.url)
            connection.inputStream.use { input -> temporary.outputStream().use(input::copyTo) }
            cache.install(entry, temporary)
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun requireSuccess(connection: HttpURLConnection, label: String) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val temporary = code == 408 || code == 429 || code in 500..599
            throw HttpFailure("$label HTTP $code", temporary)
        }
    }

    private fun publishForeground(message: String, progress: Int, total: Int) {
        setProgressAsync(status(message)).get()
        setForegroundAsync(foregroundInfo(message, progress, total)).get()
    }

    private fun foregroundInfo(message: String, progress: Int, total: Int): ForegroundInfo {
        createChannel()
        val openApp = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sync)
            .setContentTitle(applicationContext.getString(R.string.sync_notification_title))
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(if (total == 0) 0 else total, progress, total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = applicationContext.getString(R.string.sync_channel_description) }
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun retry(message: String): Result {
        setProgressAsync(status(message))
        return Result.retry()
    }

    private fun failure(message: String) = Result.failure(status(message))
    private fun status(message: String) = Data.Builder().putString(KEY_MESSAGE, message).build()
    private class HttpFailure(message: String, val temporary: Boolean) : IOException(message)

    companion object {
        const val UNIQUE_WORK_NAME = "offline-cache-sync"
        const val KEY_MESSAGE = "message"
        private const val CHANNEL_ID = "offline-sync"
        private const val NOTIFICATION_ID = 41

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request,
            )
        }
    }
}
