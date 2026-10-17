package com.sdvideohub.app

import android.content.Context
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

class OfflineCache(context: Context) {
    private val root = File(context.filesDir, "offline-cache").apply { mkdirs() }
    private val indexStore = CacheIndexStore(root)
    private fun index() = indexStore.read()
    fun file(url: String) = cacheFile(root, url)
    fun records(): Map<String, CacheRecord> = effectiveCacheRecords(index(), ::file)
    fun isComplete(url: String): Boolean {
        val data = records()
        val cacheUrl = if (data.containsKey(url)) url else url.substringBefore('?')
        return data[cacheUrl]?.isComplete() == true
    }
    fun completeUrls(): Set<String> = records().filterValues(CacheRecord::isComplete).keys

    @Synchronized fun install(entry: ManifestEntry, temporary: File) {
        require(temporary.length() == entry.size) { "Size mismatch for ${entry.url}" }
        val destination = file(entry.url)
        if (!temporary.renameTo(destination)) { temporary.copyTo(destination, overwrite = true); temporary.delete() }
        val data = index()
        data[entry.url] = CacheRecord(entry.version, entry.mime, entry.size, entry.size)
        indexStore.write(data)
    }
    @Synchronized fun prune(valid: Set<String>) {
        val data = index()
        data.keys.filterNot(valid::contains).forEach { url -> file(url).delete(); data.remove(url) }
        indexStore.write(data)
    }
    fun response(url: String, rangeHeader: String?): WebResourceResponse? {
        val data = records()
        val cacheUrl = if (data.containsKey(url)) url else url.substringBefore('?')
        val metadata = data[cacheUrl] ?: return null
        val source = file(cacheUrl)
        if (!source.isFile || metadata.expectedSize == null || source.length() != metadata.expectedSize) return null
        if (cacheUrl == "/api/state" && rangeHeader.isNullOrBlank()) {
            val body = try {
                CachedStateFilter.filter(source.readText(), completeUrls()).toByteArray()
            } catch (_: Exception) { return null }
            return WebResourceResponse(
                "application/json", "UTF-8", 200, "OK",
                mapOf("Content-Length" to body.size.toString()), ByteArrayInputStream(body),
            )
        }
        val range = try { RangeLogic.parse(rangeHeader, source.length()) } catch (_: Exception) {
            return WebResourceResponse("text/plain", "UTF-8", 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */${source.length()}"), InputStream.nullInputStream())
        }
        val mime = metadata.mime.substringBefore(';')
        val stream: InputStream = if (range == null) FileInputStream(source) else BoundedInputStream(FileInputStream(source).apply { channel.position(range.start) }, range.length)
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Content-Length" to (range?.length ?: source.length()).toString())
        if (range != null) headers["Content-Range"] = "bytes ${range.start}-${range.endInclusive}/${source.length()}"
        return WebResourceResponse(mime, if (mime.startsWith("text/") || mime == "application/json") "UTF-8" else null,
            if (range == null) 200 else 206, if (range == null) "OK" else "Partial Content", headers, stream)
    }
}

internal fun cacheFile(root: File, url: String): File {
    val name = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return File(root, name)
}

internal fun effectiveCacheRecords(
    index: Map<String, CacheRecord>,
    fileForUrl: (String) -> File,
): Map<String, CacheRecord> = index.mapValues { (url, record) ->
    val actualSize = fileForUrl(url).takeIf(File::isFile)?.length()
    record.copy(
        expectedSize = record.expectedSize ?: actualSize,
        actualSize = actualSize,
    )
}

/** A crash-safe index. A complete previous generation is retained until the next write succeeds. */
class CacheIndexStore(private val root: File) {
    private val primary = File(root, "index.json")
    private val backup = File(root, "index.json.bak")
    private val temporary = File(root, "index.json.tmp")

    @Synchronized fun read(): MutableMap<String, CacheRecord> {
        parse(primary)?.let { return it }
        parse(backup)?.let { recovered ->
            write(recovered)
            return recovered
        }
        return mutableMapOf()
    }

    @Synchronized fun write(data: Map<String, CacheRecord>) {
        root.mkdirs()
        val json = JSONObject()
        data.forEach { (url, value) ->
            val record = JSONObject().put("version", value.version).put("mime", value.mime)
            value.expectedSize?.let { record.put("size", it) }
            json.put(url, record)
        }
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray())
            output.fd.sync()
        }
        if (primary.isFile) {
            if (backup.exists() && !backup.delete()) throw IOException("Cannot replace cache index backup")
            if (!primary.renameTo(backup)) throw IOException("Cannot back up cache index")
        }
        if (!temporary.renameTo(primary)) {
            backup.renameTo(primary)
            throw IOException("Cannot publish cache index")
        }
    }

    private fun parse(file: File): MutableMap<String, CacheRecord>? = try {
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { key ->
            json.getJSONObject(key).let {
                CacheRecord(
                    version = it.getString("version"),
                    mime = it.getString("mime"),
                    expectedSize = if (it.has("size")) it.getLong("size") else null,
                    actualSize = null,
                )
            }
        }.toMutableMap()
    } catch (_: Exception) {
        null
    }
}

private class BoundedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int = if (remaining <= 0) -1 else source.read().also { if (it >= 0) remaining-- }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        return source.read(buffer, offset, minOf(length.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
    }
    override fun close() = source.close()
}

object Network {
    fun authorization(credentials: Credentials) = "Basic " + Base64.getEncoder().encodeToString("${credentials.username}:${credentials.password}".toByteArray())
    fun open(base: String, path: String, credentials: Credentials, range: String? = null): HttpURLConnection {
        val connection = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000; connection.readTimeout = 30000
        connection.setRequestProperty("Authorization", authorization(credentials))
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (range != null) connection.setRequestProperty("Range", range)
        return connection
    }
}
