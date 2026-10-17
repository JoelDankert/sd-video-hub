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
            "/" to ("root-v1" to "text/html"),
            "/media/a.mp4" to ("video-v1" to "video/mp4"),
        )
        store.write(retained)
        // A later successful generation creates the backup, then an interrupted/corrupt
        // primary simulates process death during a subsequent generation.
        store.write(retained + ("/api/state" to ("state-v1" to "application/json")))
        File(root, "index.json").writeText("{not-json")

        val recovered = store.read()
        assertEquals(retained, recovered)
        val remote = retained.map { (url, metadata) -> ManifestEntry(url, metadata.second, 1, metadata.first) }
        assertTrue(SyncPlanner.plan(recovered.mapValues { it.value.first }, remote).downloads.isEmpty())
    }
}
