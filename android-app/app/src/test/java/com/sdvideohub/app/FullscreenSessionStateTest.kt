package com.sdvideohub.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenSessionStateTest {
    @Test fun onlyOneCustomViewSessionCanBeActive() {
        val state = FullscreenSessionState()

        assertTrue(state.begin())
        assertTrue(state.isActive)
        assertFalse(state.begin())
    }

    @Test fun customViewSessionCanOnlyEndOnce() {
        val state = FullscreenSessionState()

        assertFalse(state.end())
        assertTrue(state.begin())
        assertTrue(state.end())
        assertFalse(state.isActive)
        assertFalse(state.end())
    }
}
