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

object SyncPlanner {
    fun plan(local: Map<String, String>, remote: List<ManifestEntry>): SyncPlan {
        val remoteUrls = remote.mapTo(linkedSetOf()) { it.url }
        return SyncPlan(
            downloads = remote.filter { local[it.url] != it.version },
            validUrls = remoteUrls,
        )
    }
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
