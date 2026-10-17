package com.sdvideohub.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.io.IOException
import java.util.concurrent.TimeUnit

class VideoDownloadWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val path = inputData.getString(KEY_PATH) ?: return Result.failure()
        val expectedIdentity = inputData.getString(KEY_IDENTITY) ?: return Result.failure()
        val credentials = SecureCredentials(applicationContext).load() ?: return failed("Download failed · sign in required")
        if (identity(credentials) != expectedIdentity) return failed("Download cancelled · server account changed")
        val fallback = DownloadRouting.safeFilename(inputData.getString(KEY_NAME), "video.mp4")
        var savedName = fallback
        var pending: Uri? = null
        return try {
            foreground("Downloading $fallback", 0, 0)
            val cached = if (inputData.getString(KEY_KIND) == DownloadKind.MEDIA.name) OfflineCache(applicationContext).repository.open(path.substringBefore('?')) else null
            if (cached != null) {
                val target = openDestination(fallback, cached.metadata.mime).also { pending = it.first }
                cached.file.inputStream().use { input -> target.second.use { output -> copy(input, output, cached.file.length()) } }
            } else {
                val connection = Network.open(credentials.baseUrl, path, credentials)
                try {
                    val code = connection.responseCode
                    if (code != 200) throw IOException("HTTP $code")
                    val name = DownloadRouting.contentDispositionFilename(connection.getHeaderField("Content-Disposition"), fallback)
                    savedName = name
                    val target = openDestination(name, connection.contentType ?: "video/mp4").also { pending = it.first }
                    connection.inputStream.use { input -> target.second.use { output -> copy(input, output, connection.contentLengthLong) } }
                } finally { connection.disconnect() }
            }
            if (Build.VERSION.SDK_INT >= 29 && pending != null) applicationContext.contentResolver.update(pending!!, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            foreground("Saved to Downloads/SD Video Hub", 100, 100)
            Result.success(Data.Builder().putString(KEY_MESSAGE, "Saved $savedName").build())
        } catch (error: Exception) {
            if (Build.VERSION.SDK_INT >= 29 && pending != null) applicationContext.contentResolver.delete(pending!!, null, null)
            failed("Download failed · ${error.message?.take(80) ?: "error"}")
        }
    }

    private fun openDestination(name: String, mime: String): Pair<Uri, java.io.OutputStream> {
        val resolver = applicationContext.contentResolver
        val saf = inputData.getString(KEY_SAF_URI)?.let(Uri::parse)
        if (saf != null) return saf to (resolver.openOutputStream(saf, "w") ?: throw IOException("Cannot open selected file"))
        if (Build.VERSION.SDK_INT < 29) throw IOException("Choose a destination in the app")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime.substringBefore(';'))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SD Video Hub")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Cannot create download")
        return try {
            uri to (resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write download"))
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun copy(input: java.io.InputStream, output: java.io.OutputStream, total: Long) {
        val buffer = ByteArray(64 * 1024); var copied = 0L; var last = 0L
        while (true) { if (isStopped) throw IOException("stopped"); val n=input.read(buffer); if(n<0) break; output.write(buffer,0,n); copied+=n
            val now=System.nanoTime(); if(TimeUnit.NANOSECONDS.toMillis(now-last)>400){last=now; val p=if(total>0)((copied*100)/total).toInt() else 0; foreground("Downloading ${bytes(copied)}",p,if(total>0)100 else 0)} }
        if (copied == 0L || (total >= 0 && copied != total)) throw IOException("Incomplete download ($copied/$total bytes)")
    }

    private fun foreground(text: String, progress: Int, max: Int) {
        if (Build.VERSION.SDK_INT >= 26) applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Video downloads",NotificationManager.IMPORTANCE_LOW))
        val intent=PendingIntent.getActivity(applicationContext,0,Intent(applicationContext,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=NotificationCompat.Builder(applicationContext,CHANNEL).setSmallIcon(R.drawable.ic_sync).setContentTitle("SD Video Hub").setContentText(text).setContentIntent(intent).setOnlyAlertOnce(true).setOngoing(progress<max||max==0).setProgress(max,progress,max==0).build()
        val info=if(Build.VERSION.SDK_INT>=29) ForegroundInfo(NOTIFICATION,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION,notification)
        setProgressAsync(Data.Builder().putString(KEY_MESSAGE,text).build()).get(); setForegroundAsync(info).get()
    }
    private fun failed(message:String)=Result.failure(Data.Builder().putString(KEY_MESSAGE,message).build())
    private fun bytes(value:Long)=if(value>=1_000_000) "%.1f MB".format(value/1_000_000.0) else "${value/1000} KB"

    companion object {
        const val KEY_PATH="path"; const val KEY_KIND="kind"; const val KEY_NAME="name"; const val KEY_IDENTITY="identity"; const val KEY_SAF_URI="safUri"; const val KEY_MESSAGE="message"
        private const val CHANNEL="video-downloads"; private const val NOTIFICATION=2212
        fun identity(c:Credentials)="${c.baseUrl.trimEnd('/')}\n${c.username}"
        fun enqueue(context:Context, request:NativeDownload, credentials:Credentials, saf:Uri?=null): java.util.UUID {
            val data=Data.Builder().putString(KEY_PATH,request.path).putString(KEY_KIND,request.kind.name).putString(KEY_NAME,request.suggestedName).putString(KEY_IDENTITY,identity(credentials)).apply { saf?.let { putString(KEY_SAF_URI,it.toString()) } }.build()
            val work=OneTimeWorkRequestBuilder<VideoDownloadWorker>().setInputData(data).setConstraints(Constraints.Builder().setRequiredNetworkType(if(request.kind==DownloadKind.MEDIA) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueue(work); return work.id
        }
    }
}
