package com.sdvideohub.app

import org.json.JSONObject

data class ManifestEntry(val url: String, val mime: String, val size: Long, val version: String)

object ManifestCodec {
    fun parse(text: String): List<ManifestEntry> {
        val root = JSONObject(text)
        require(root.getInt("schema") == 1) { "Unsupported manifest schema" }
        val array = root.getJSONArray("entries")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val url = item.getString("url")
            require(url.startsWith("/") && !url.startsWith("//") && !url.contains("..")) { "Unsafe manifest URL" }
            ManifestEntry(url, item.getString("mime"), item.getLong("size"), item.getString("version"))
        }.also { entries -> require(entries.map { it.url }.distinct().size == entries.size) { "Duplicate manifest URL" } }
    }

}

data class SyncPlan(val downloads: List<ManifestEntry>, val validUrls: Set<String>)

data class CacheRecord(
    val version: String,
    val mime: String,
    val expectedSize: Long?,
    val actualSize: Long?,
) {
    fun isComplete(): Boolean = expectedSize != null && actualSize == expectedSize
}

object SyncPlanner {
    fun plan(local: Map<String, CacheRecord>, remote: List<ManifestEntry>): SyncPlan {
        val remoteUrls = remote.mapTo(linkedSetOf()) { it.url }
        return SyncPlan(
            downloads = remote.filter { entry ->
                val cached = local[entry.url]
                cached == null || cached.version != entry.version ||
                    cached.expectedSize != entry.size || cached.actualSize != entry.size
            },
            validUrls = remoteUrls,
        )
    }
}

object CachedStateFilter {
    fun filter(text: String, completeUrls: Set<String>): String {
        val root = JSONObject(text)
        val files = root.optJSONArray("files") ?: return root.toString()
        val filtered = org.json.JSONArray()
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            val videoUrl = file.optString("video_url")
            if (videoUrl.isNotBlank() && completeUrls.contains(videoUrl)) filtered.put(file)
        }
        root.put("files", filtered)
        return root.toString()
    }
}

enum class RequestRoute { NATIVE, CACHE_ONLY, NETWORK_THEN_CACHE }

object RequestRouting {
    fun select(path: String, networkValidated: Boolean, cached: Boolean, forceCache: Boolean = false): RequestRoute {
        val pathname = path.substringBefore('?')
        if (pathname.startsWith("/media/")) {
            if (cached) return RequestRoute.CACHE_ONLY
            return if (networkValidated) RequestRoute.NATIVE else RequestRoute.CACHE_ONLY
        }
        if (forceCache && cached) return RequestRoute.CACHE_ONLY
        if (!networkValidated) return RequestRoute.CACHE_ONLY
        val isNativeDownload = pathname.startsWith("/flight-download/") ||
            path.substringAfter('?', "") == "download"
        return if (isNativeDownload) RequestRoute.NATIVE else RequestRoute.NETWORK_THEN_CACHE
    }
}

enum class SyncWorkState { ENQUEUED, RUNNING, BLOCKED, SUCCEEDED, FAILED, CANCELLED }

data class SyncWorkSnapshot(val id: String, val state: SyncWorkState)

object SyncWorkSelector {
    private val active = setOf(SyncWorkState.ENQUEUED, SyncWorkState.RUNNING, SyncWorkState.BLOCKED)

    fun select(work: List<SyncWorkSnapshot>, previouslyActiveIds: Set<String> = emptySet()): SyncWorkSnapshot? =
        work.firstOrNull { it.state in active }
            ?: work.firstOrNull { it.id in previouslyActiveIds }
            ?: work.firstOrNull()
}

data class ByteRange(val start: Long, val endInclusive: Long) {
    val length get() = endInclusive - start + 1
}

object RangeLogic {
    fun parse(header: String?, size: Long): ByteRange? {
        if (header.isNullOrBlank()) return null
        require(size > 0 && header.startsWith("bytes=") && !header.substring(6).contains(','))
        val parts = header.substring(6).split('-', limit = 2)
        require(parts.size == 2)
        val range = if (parts[0].isBlank()) {
            val suffix = parts[1].toLong(); require(suffix > 0)
            ByteRange((size - suffix).coerceAtLeast(0), size - 1)
        } else {
            val start = parts[0].toLong()
            val end = if (parts[1].isBlank()) size - 1 else parts[1].toLong().coerceAtMost(size - 1)
            ByteRange(start, end)
        }
        require(range.start in 0 until size && range.endInclusive >= range.start)
        return range
    }
}
