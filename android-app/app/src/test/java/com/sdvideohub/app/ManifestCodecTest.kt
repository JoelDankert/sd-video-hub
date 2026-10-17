package com.sdvideohub.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestCodecTest {
    @Test fun parsesAndDiffsManifest() {
        val entries = ManifestCodec.parse("""{"schema":1,"entries":[{"url":"/","mime":"text/html","size":4,"version":"new"},{"url":"/media/a.mp4","mime":"video/mp4","size":8,"version":"same"}]}""")
        assertEquals(2, entries.size)
        assertEquals(
            listOf("/"),
            SyncPlanner.plan(
                mapOf(
                    "/" to CacheRecord("old", "text/html", 4, 4),
                    "/media/a.mp4" to CacheRecord("same", "video/mp4", 8, 8),
                ),
                entries,
            ).downloads.map { it.url },
        )
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnsafeUrls() {
        ManifestCodec.parse("""{"schema":1,"entries":[{"url":"/../secret","mime":"text/plain","size":1,"version":"x"}]}""")
    }
}
