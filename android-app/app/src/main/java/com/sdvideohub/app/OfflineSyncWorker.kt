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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.util.UUID
import java.util.concurrent.TimeUnit

class OfflineSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val credentials = SecureCredentials(applicationContext).load()
            ?: return failure("Sync skipped · sign in required")
        val cache = OfflineCache(applicationContext)
        return try {
            publishForeground("Preparing offline sync…", 0, 0)
            var manifest = fetchManifest(credentials)
            val plan = SyncPlanner.plan(cache.records(), manifest)
            val ordered = plan.downloads.sortedWith(compareBy(::syncPriority, ManifestEntry::url))
            val totalBytes = ordered.sumOf { it.size }.coerceAtLeast(1)
            var completedBytes = 0L
            val progressReporter = ProgressReporter(totalBytes)
            publishForeground("Syncing 0 / ${formatBytes(totalBytes)}", 0, 1000)
            ordered.forEach { entry ->
                if (isStopped) throw IOException("Sync stopped")
                var installed = entry
                try {
                    download(credentials, cache, entry) { copied -> progressReporter.report(completedBytes + copied) }
                } catch (_: SizeChanged) {
                    // Generated state can legitimately change after manifest creation.
                    // Refresh once; a second mismatch exits this run instead of looping.
                    val refreshed = fetchManifest(credentials)
                    installed = refreshed.firstOrNull { it.url == entry.url }
                        ?: throw IOException("Resource disappeared during sync: ${entry.url}")
                    manifest = refreshed
                    download(credentials, cache, installed) { copied -> progressReporter.report(completedBytes + copied) }
                }
                completedBytes += installed.size
                progressReporter.report(completedBytes, force = true)
            }
            // Never prune until every new/changed resource is safely installed.
            val verified = cache.records()
            val missing = manifest.filterNot { verified[it.url]?.let { record ->
                record.version == it.version && record.expectedSize == it.size && record.actualSize == it.size
            } == true }
            if (missing.isNotEmpty()) throw IOException("Verification failed for ${missing.size} cache item(s)")
            cache.prune(manifest.mapTo(linkedSetOf()) { it.url })
            Result.success(status(if (ordered.isEmpty()) "Up to date" else "Offline copy updated"))
        } catch (error: HttpFailure) {
            if (error.temporary) retry("Waiting to retry offline sync")
            else failure("Offline copy retained · ${error.message}")
        } catch (error: IOException) {
            retry("Waiting to retry offline sync · ${safeError(error)}")
        } catch (error: Exception) {
            failure("Offline copy retained · ${safeError(error)}")
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

    private fun download(credentials: Credentials, cache: OfflineCache, entry: ManifestEntry, progress: (Long) -> Unit) {
        val destination = cache.file(entry.url)
        val temporary = File(destination.parentFile, destination.name + ".part")
        val connection = Network.open(credentials.baseUrl, entry.url, credentials)
        try {
            requireSuccess(connection, entry.url)
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        progress(copied)
                    }
                    output.fd.sync()
                }
            }
            if (temporary.length() != entry.size) throw SizeChanged()
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
    private fun safeError(error: Exception): String =
        (error.message?.substringBefore('\n')?.take(120) ?: error.javaClass.simpleName)
    private inner class ProgressReporter(private val total: Long) {
        private var lastPublishedAt = 0L
        fun report(current: Long, force: Boolean = false) {
            val now = System.nanoTime()
            if (!force && TimeUnit.NANOSECONDS.toMillis(now - lastPublishedAt) < 350) return
            lastPublishedAt = now
            val progress = ((current * 1000) / total).toInt().coerceIn(0, 1000)
            publishForeground("Syncing ${formatBytes(current)} / ${formatBytes(total)}", progress, 1000)
        }
    }
    private fun syncPriority(entry: ManifestEntry): Int = when {
        entry.url == "/" -> 0
        entry.url == "/api/state" -> 1
        entry.url.startsWith("/thumb/") -> 2
        !entry.url.startsWith("/media/") -> 3
        entry.mime.contains("json") -> 3
        else -> 4
    }
    private fun formatBytes(value: Long): String = when {
        value >= 1_000_000_000 -> "%.1f GB".format(value / 1_000_000_000.0)
        value >= 1_000_000 -> "%.1f MB".format(value / 1_000_000.0)
        value >= 1_000 -> "%.1f KB".format(value / 1_000.0)
        else -> "$value B"
    }
    private class HttpFailure(message: String, val temporary: Boolean) : IOException(message)
    private class SizeChanged : IOException()

    companion object {
        const val UNIQUE_WORK_NAME = "offline-cache-sync"
        const val KEY_MESSAGE = "message"
        private const val CHANNEL_ID = "offline-sync"
        private const val NOTIFICATION_ID = 41

        fun enqueue(context: Context): UUID {
            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request,
            )
            return request.id
        }
    }
}
