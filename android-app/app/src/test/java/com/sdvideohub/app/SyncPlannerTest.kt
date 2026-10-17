package com.sdvideohub.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {
    @Test fun plansNewChangedAndStaleEntries() {
        val remote = listOf(
            ManifestEntry("/", "text/html", 10, "v2"),
            ManifestEntry("/same", "text/plain", 4, "v1"),
            ManifestEntry("/new", "video/mp4", 20, "v1"),
        )
        val plan = SyncPlanner.plan(
            mapOf("/" to "v1", "/same" to "v1", "/stale" to "old"),
            remote,
        )
        assertEquals(listOf("/", "/new"), plan.downloads.map { it.url })
        assertEquals(setOf("/", "/same", "/new"), plan.validUrls)
    }

    @Test fun emptyPlanLeavesNothingToDownloadOrPrune() {
        val remote = listOf(ManifestEntry("/", "text/html", 10, "v1"))
        val plan = SyncPlanner.plan(mapOf("/" to "v1"), remote)
        assertTrue(plan.downloads.isEmpty())
        assertEquals(setOf("/"), plan.validUrls)
    }
}
