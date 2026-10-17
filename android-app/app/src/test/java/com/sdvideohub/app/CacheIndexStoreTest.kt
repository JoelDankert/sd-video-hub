package com.sdvideohub.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheIndexStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun corruptPrimaryRecoversPreviousCompleteIndexAndKeepsItsSyncPlanEntries() {
        val root = temporaryFolder.newFolder("offline-cache")
        val store = CacheIndexStore(root)
        val retained = mapOf(
            "/" to CacheRecord("root-v1", "text/html", 1, null),
            "/media/a.mp4" to CacheRecord("video-v1", "video/mp4", 1, null),
        )
        store.write(retained)
        // A later successful generation creates the backup, then an interrupted/corrupt
        // primary simulates process death during a subsequent generation.
        store.write(retained + ("/api/state" to CacheRecord("state-v1", "application/json", 1, null)))
        File(root, "index.json").writeText("{not-json")

        val recovered = store.read()
        assertEquals(retained, recovered)
        val remote = retained.map { (url, metadata) -> ManifestEntry(url, metadata.mime, 1, metadata.version) }
        val local = recovered.mapValues { it.value.copy(actualSize = 1) }
        assertTrue(SyncPlanner.plan(local, remote).downloads.isEmpty())
    }

    @Test fun legacyIndexWithExistingFileUsesItsSizeUntilManifestConfirmsOrRejectsIt() {
        val root = temporaryFolder.newFolder("legacy-offline-cache")
        File(root, "index.json").writeText(
            """{"/":{"version":"v1","mime":"text/html"},"/api/state":{"version":"v1","mime":"application/json"}}""",
        )
        cacheFile(root, "/").writeText("shell")
        cacheFile(root, "/api/state").writeText("{}")

        val records = effectiveCacheRecords(CacheIndexStore(root).read()) { cacheFile(root, it) }
        val record = records.getValue("/")
        assertEquals(5L, record.expectedSize)
        assertEquals(5L, record.actualSize)
        assertTrue(records.getValue("/").isComplete() && records.getValue("/api/state").isComplete())
        val matchingRemote = listOf(
            ManifestEntry("/", "text/html", 5, "v1"),
            ManifestEntry("/api/state", "application/json", 2, "v1"),
        )
        assertTrue(SyncPlanner.plan(records, matchingRemote).downloads.isEmpty())
        assertEquals(
            listOf("/"),
            SyncPlanner.plan(records, matchingRemote.map { if (it.url == "/") it.copy(size = 6) else it }).downloads.map { it.url },
        )

        cacheFile(root, "/api/state").delete()
        val missingRecords = effectiveCacheRecords(CacheIndexStore(root).read()) { cacheFile(root, it) }
        assertTrue(!missingRecords.getValue("/api/state").isComplete())
    }
}
