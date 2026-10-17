package com.sdvideohub.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkSelectorTest {
    @Test fun activeWorkWinsOverOlderSuccessfulHistory() {
        val selected = SyncWorkSelector.select(
            listOf(
                SyncWorkSnapshot("old", SyncWorkState.SUCCEEDED),
                SyncWorkSnapshot("new", SyncWorkState.ENQUEUED),
            ),
        )
        assertEquals("new", selected?.id)
    }

    @Test fun justCompletedObservedRunWinsOverOlderHistory() {
        val selected = SyncWorkSelector.select(
            listOf(
                SyncWorkSnapshot("old", SyncWorkState.SUCCEEDED),
                SyncWorkSnapshot("new", SyncWorkState.SUCCEEDED),
            ),
            previouslyActiveIds = setOf("new"),
        )
        assertEquals("new", selected?.id)
    }
}
