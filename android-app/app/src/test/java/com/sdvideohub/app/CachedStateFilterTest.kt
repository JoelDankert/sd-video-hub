package com.sdvideohub.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CachedStateFilterTest {
    @Test fun keepsStateShapeButOnlyIncludesCompletelyCachedVideos() {
        val input = """{
            "files": [
                {"id": 1, "status": "done", "video_url": "/media/complete.mp4", "name": "complete"},
                {"id": 2, "status": "done", "video_url": "/media/partial.mp4", "name": "partial"},
                {"id": 3, "status": "done", "video_url": "/media/missing.mp4", "name": "missing"},
                {"id": 4, "status": "compressing", "video_url": "/media/in-progress.mp4", "name": "working"}
            ],
            "sessions": [{"id": "session-1", "status": "done"}],
            "devices": [],
            "settings": {"archive": {"sort_direction": "desc"}}
        }""".trimIndent()

        val filtered = JSONObject(CachedStateFilter.filter(input, setOf("/media/complete.mp4")))
        val files = filtered.getJSONArray("files")
        assertEquals(1, files.length())
        assertEquals(1, files.getJSONObject(0).getInt("id"))
        assertEquals("session-1", filtered.getJSONArray("sessions").getJSONObject(0).getString("id"))
        assertEquals("desc", filtered.getJSONObject("settings").getJSONObject("archive").getString("sort_direction"))
        assertFalse(filtered.has("unexpected"))
    }
}
