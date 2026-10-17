package com.sdvideohub.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestRoutingTest {
    @Test fun offlineRequestsGoDirectlyToCache() {
        assertEquals(RequestRoute.CACHE_ONLY, RequestRouting.select("/", false, true))
        assertEquals(RequestRoute.CACHE_ONLY, RequestRouting.select("/media/video.mp4", false, true))
    }

    @Test fun onlineUncachedStreamsStayNative() {
        assertEquals(RequestRoute.NATIVE, RequestRouting.select("/media/video.mp4", true, false))
        assertEquals(RequestRoute.NATIVE, RequestRouting.select("/media/video.mp4?download", true, false))
        assertEquals(RequestRoute.NATIVE, RequestRouting.select("/flight-download/token", true, false))
    }

    @Test fun onlineDocumentsAndCachedStreamsUseNetworkWithCacheFallback() {
        assertEquals(RequestRoute.NETWORK_THEN_CACHE, RequestRouting.select("/api/state", true, true))
        assertEquals(RequestRoute.NETWORK_THEN_CACHE, RequestRouting.select("/media/video.mp4", true, true))
    }
}
