package com.sdvideohub.app

import android.content.Context
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

class OfflineCache(context: Context) {
    private val root = File(context.filesDir, "offline-cache").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    @Synchronized private fun index(): MutableMap<String, Pair<String, String>> = try {
        val json = JSONObject(indexFile.readText())
        json.keys().asSequence().associateWith { key -> json.getJSONObject(key).let { it.getString("version") to it.getString("mime") } }.toMutableMap()
    } catch (_: Exception) { mutableMapOf() }
    private fun name(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    fun file(url: String) = File(root, name(url))
    fun versions() = index().mapValues { it.value.first }

    @Synchronized fun install(entry: ManifestEntry, temporary: File) {
        require(temporary.length() == entry.size) { "Size mismatch for ${entry.url}" }
        val destination = file(entry.url)
        if (!temporary.renameTo(destination)) { temporary.copyTo(destination, overwrite = true); temporary.delete() }
        val data = index(); data[entry.url] = entry.version to entry.mime; writeIndex(data)
    }
    @Synchronized fun prune(valid: Set<String>) {
        val data = index()
        data.keys.filterNot(valid::contains).forEach { url -> file(url).delete(); data.remove(url) }
        writeIndex(data)
    }
    private fun writeIndex(data: Map<String, Pair<String, String>>) {
        val json = JSONObject(); data.forEach { (url, value) -> json.put(url, JSONObject().put("version", value.first).put("mime", value.second)) }
        val tmp = File(root, "index.json.tmp"); tmp.writeText(json.toString()); if (!tmp.renameTo(indexFile)) { tmp.copyTo(indexFile, true); tmp.delete() }
    }
    fun response(url: String, rangeHeader: String?): WebResourceResponse? {
        val data = index()
        val cacheUrl = if (data.containsKey(url)) url else url.substringBefore('?')
        val metadata = data[cacheUrl] ?: return null
        val source = file(cacheUrl); if (!source.isFile) return null
        val range = try { RangeLogic.parse(rangeHeader, source.length()) } catch (_: Exception) {
            return WebResourceResponse("text/plain", "UTF-8", 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */${source.length()}"), InputStream.nullInputStream())
        }
        val mime = metadata.second.substringBefore(';')
        val stream: InputStream = if (range == null) FileInputStream(source) else BoundedInputStream(FileInputStream(source).apply { channel.position(range.start) }, range.length)
        val headers = mutableMapOf("Accept-Ranges" to "bytes", "Content-Length" to (range?.length ?: source.length()).toString())
        if (range != null) headers["Content-Range"] = "bytes ${range.start}-${range.endInclusive}/${source.length()}"
        return WebResourceResponse(mime, if (mime.startsWith("text/") || mime == "application/json") "UTF-8" else null,
            if (range == null) 200 else 206, if (range == null) "OK" else "Partial Content", headers, stream)
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
