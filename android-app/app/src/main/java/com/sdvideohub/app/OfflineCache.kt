package com.sdvideohub.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Android facade over the process-wide repository used by playback and WorkManager. */
class OfflineCache(context: Context) {
    private val credentials = SecureCredentials(context).load()
    private val identity = context.getSharedPreferences("offline-cache-origin-v1", Context.MODE_PRIVATE)
    private val cacheIdentity = credentials?.let { "${it.baseUrl.trimEnd('/')}\n${it.username}" }
    private val legacyOrigin = identity.getString("legacy-origin", null)
    private val cacheRoot = when {
        cacheIdentity == null || legacyOrigin == null || legacyOrigin == cacheIdentity -> File(context.filesDir, "offline-cache")
        else -> File(context.filesDir, "offline-cache-${identityKey(cacheIdentity)}")
    }
    init {
        if (cacheIdentity != null && legacyOrigin == null) identity.edit().putString("legacy-origin", cacheIdentity).apply()
    }
    internal val repository = CacheRepository.open(cacheRoot)
    fun file(url: String) = repository.file(url)
    fun records() = repository.recordsForPlanning()
    fun isComplete(url: String) = repository.complete(url)
    fun install(entry: ManifestEntry, temporary: File) = repository.install(entry, temporary)
    fun prune(valid: Set<String>) = repository.prune(valid)

    companion object {
    fun identityKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.trimEnd('/').toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
    }
}

/**
 * One repository/lock/index snapshot per canonical cache root. Reads are O(1) and only
 * stat the requested file. Index writes are serialized and atomically publish snapshots.
 */
class CacheRepository private constructor(val root: File) {
    private val store = CacheIndexStore(root)
    private val snapshot = AtomicReference<Map<String, CacheRecord>>(store.read().toMap())
    private val verified = ConcurrentHashMap<String, Boolean>()
    private val files = ConcurrentHashMap<String, File>()

    init { bootstrapVerification() }

    fun metadata(url: String): CacheRecord? = snapshot.get()[canonicalLookup(url)]
    fun urls(): Set<String> = snapshot.get().keys
    fun file(url: String): File = files.computeIfAbsent(url) { cacheFile(root, it) }

    fun complete(url: String): Boolean {
        val key = canonicalLookup(url)
        val record = snapshot.get()[key] ?: return false
        verified[key]?.let { return it }
        val source = file(key)
        val actual = source.takeIf(File::isFile)?.length()
        val expected = record.expectedSize ?: actual
        val complete = expected != null && actual == expected
        if (record.expectedSize == null && complete) updateLegacySize(key, expected!!)
        verified[key] = complete
        return complete
    }

    fun open(url: String): CachedResource? {
        val key = canonicalLookup(url)
        if (!complete(key)) return null
        val record = snapshot.get()[key] ?: return null
        return CachedResource(key, file(key), record.copy(actualSize = record.expectedSize))
    }

    /** Manifest planning is the only intentional all-entry verification pass. */
    fun recordsForPlanning(): Map<String, CacheRecord> = snapshot.get().mapValues { (url, record) ->
        val actual = file(url).takeIf(File::isFile)?.length()
        val expected = record.expectedSize ?: actual
        record.copy(expectedSize = expected, actualSize = actual)
    }

    fun completeKnownUrls(): Set<String> = snapshot.get().asSequence()
        .filter { (url, _) -> verified[url] == true }
        .map { it.key }.toSet()

    fun filterCachedState(text: String): String {
        val json = JSONObject(text)
        val files = json.optJSONArray("files")
        if (files != null) for (index in 0 until files.length()) {
            val url = files.optJSONObject(index)?.optString("video_url").orEmpty()
            if (url.isNotBlank()) complete(url) // one lazy stat per referenced legacy/current file
        }
        return CachedStateFilter.filter(text, completeKnownUrls())
    }

    @Synchronized fun install(entry: ManifestEntry, temporary: File) {
        require(temporary.isFile && temporary.length() == entry.size) { "Size mismatch for ${entry.url}" }
        val destination = file(entry.url)
        val staged = File(root, destination.name + ".install")
        if (staged.exists() && !staged.delete()) throw IOException("Cannot replace staged cache file")
        if (!temporary.renameTo(staged)) {
            temporary.copyTo(staged, overwrite = true)
            temporary.delete()
        }
        val next = snapshot.get().toMutableMap().apply {
            put(entry.url, CacheRecord(entry.version, entry.mime, entry.size, entry.size))
        }
        // Data is installed before the index points at it; the old generation remains
        // usable until this rename succeeds.
        try {
            Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        store.write(next)
        snapshot.set(next.toMap())
        verified[entry.url] = true
    }

    @Synchronized fun prune(valid: Set<String>) {
        val current = snapshot.get()
        val next = current.filterKeys(valid::contains)
        if (next.size == current.size) return
        store.write(next)
        snapshot.set(next.toMap())
        current.keys.filterNot(valid::contains).forEach { url ->
            file(url).delete()
            verified.remove(url)
        }
    }

    @Synchronized private fun updateLegacySize(url: String, size: Long) {
        val current = snapshot.get()
        val old = current[url] ?: return
        if (old.expectedSize != null) return
        val next = current.toMutableMap().apply { put(url, old.copy(expectedSize = size, actualSize = size)) }
        store.write(next)
        snapshot.set(next.toMap())
    }

    /** One startup pass makes migrated size-less videos visible before any card can touch them. */
    @Synchronized private fun bootstrapVerification() {
        val current = snapshot.get()
        var changed = false
        val next = current.toMutableMap()
        current.forEach { (url, record) ->
            val actual = file(url).takeIf(File::isFile)?.length()
            val expected = record.expectedSize ?: actual
            val complete = expected != null && actual == expected
            verified[url] = complete
            if (complete && record.expectedSize == null) {
                next[url] = record.copy(expectedSize = expected, actualSize = actual)
                changed = true
            }
        }
        if (changed) {
            store.write(next)
            snapshot.set(next.toMap())
        }
    }

    private fun canonicalLookup(url: String): String {
        if (snapshot.get().containsKey(url)) return url
        val withoutQuery = url.substringBefore('?')
        return if (snapshot.get().containsKey(withoutQuery)) withoutQuery else url
    }

    companion object {
        private val repositories = ConcurrentHashMap<String, CacheRepository>()
        fun open(root: File): CacheRepository {
            root.mkdirs()
            val key = root.canonicalPath
            return repositories.computeIfAbsent(key) { CacheRepository(root.canonicalFile) }
        }
        internal fun forgetForTests(root: File) { repositories.remove(root.canonicalPath) }
    }
}

data class CachedResource(val url: String, val file: File, val metadata: CacheRecord)

internal fun cacheFile(root: File, url: String): File {
    val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(StandardCharsets.UTF_8))
    val chars = CharArray(digest.size * 2)
    val hex = "0123456789abcdef"
    digest.forEachIndexed { i, byte ->
        val value = byte.toInt() and 0xff
        chars[i * 2] = hex[value ushr 4]
        chars[i * 2 + 1] = hex[value and 0x0f]
    }
    return File(root, String(chars))
}

internal fun effectiveCacheRecords(index: Map<String, CacheRecord>, fileForUrl: (String) -> File): Map<String, CacheRecord> =
    index.mapValues { (url, record) ->
        val actual = fileForUrl(url).takeIf(File::isFile)?.length()
        record.copy(expectedSize = record.expectedSize ?: actual, actualSize = actual)
    }

/** A crash-safe index which never rotates an invalid primary over a good backup. */
class CacheIndexStore(private val root: File) {
    private val primary = File(root, "index.json")
    private val backup = File(root, "index.json.bak")
    private val temporary = File(root, "index.json.tmp")

    @Synchronized fun read(): MutableMap<String, CacheRecord> =
        parse(primary) ?: parse(backup) ?: mutableMapOf()

    @Synchronized fun write(data: Map<String, CacheRecord>) {
        root.mkdirs()
        val json = JSONObject()
        data.forEach { (url, value) ->
            val record = JSONObject().put("version", value.version).put("mime", value.mime)
            value.expectedSize?.let { record.put("size", it) }
            json.put(url, record)
        }
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        val primaryValid = parse(primary) != null
        if (primaryValid) {
            if (backup.exists() && !backup.delete()) throw IOException("Cannot replace cache index backup")
            if (!primary.renameTo(backup)) throw IOException("Cannot back up cache index")
        } else if (primary.exists() && !primary.delete()) {
            throw IOException("Cannot discard invalid cache index")
        }
        if (!temporary.renameTo(primary)) throw IOException("Cannot publish cache index")
    }

    private fun parse(file: File): MutableMap<String, CacheRecord>? {
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            json.keys().asSequence().associateWith { key ->
                json.getJSONObject(key).let {
                    CacheRecord(it.getString("version"), it.getString("mime"),
                        if (it.has("size")) it.getLong("size") else null, null)
                }
            }.toMutableMap()
        } catch (_: Exception) { null }
    }
}

class BoundedInputStream(private val source: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int = if (remaining <= 0) -1 else source.read().also { if (it >= 0) remaining-- }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0) return -1
        return source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            .also { if (it > 0) remaining -= it }
    }
    override fun close() = source.close()
}

object Network {
    init {
        // The desktop JDK otherwise silently drops Origin; Android permits this header.
        // Keeping it enabled makes the integration path faithfully exercise backend CSRF checks.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
    }
    fun authorization(credentials: Credentials) = "Basic " + Base64.getEncoder()
        .encodeToString("${credentials.username}:${credentials.password}".toByteArray(StandardCharsets.UTF_8))

    fun open(base: String, path: String, credentials: Credentials, range: String? = null): HttpURLConnection {
        require(path.startsWith("/") && !path.startsWith("//")) { "Unsafe upstream path" }
        val connection = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 1500
        connection.readTimeout = 6000
        connection.setRequestProperty("Authorization", authorization(credentials))
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (range != null) connection.setRequestProperty("Range", range)
        return connection
    }
}
