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
            mapOf(
                "/" to CacheRecord("v1", "text/html", 10, 10),
                "/same" to CacheRecord("v1", "text/plain", 4, 4),
                "/stale" to CacheRecord("old", "text/plain", 1, 1),
            ),
            remote,
        )
        assertEquals(listOf("/", "/new"), plan.downloads.map { it.url })
        assertEquals(setOf("/", "/same", "/new"), plan.validUrls)
    }

    @Test fun emptyPlanLeavesNothingToDownloadOrPrune() {
        val remote = listOf(ManifestEntry("/", "text/html", 10, "v1"))
        val plan = SyncPlanner.plan(mapOf("/" to CacheRecord("v1", "text/html", 10, 10)), remote)
        assertTrue(plan.downloads.isEmpty())
        assertEquals(setOf("/"), plan.validUrls)
    }

    @Test fun sameVersionRequiresEffectiveExpectedAndActualExactByteSize() {
        val remote = listOf(ManifestEntry("/media/a.mp4", "video/mp4", 8, "v1"))

        assertEquals(
            listOf("/media/a.mp4"),
            SyncPlanner.plan(
                mapOf("/media/a.mp4" to CacheRecord("v1", "video/mp4", null, 8)),
                remote,
            ).downloads.map { it.url },
        )
        assertEquals(
            listOf("/media/a.mp4"),
            SyncPlanner.plan(
                mapOf("/media/a.mp4" to CacheRecord("v1", "video/mp4", 8, 7)),
                remote,
            ).downloads.map { it.url },
        )
        assertTrue(
            SyncPlanner.plan(
                mapOf("/media/a.mp4" to CacheRecord("v1", "video/mp4", 8, 8)),
                remote,
            ).downloads.isEmpty(),
        )
    }
}
