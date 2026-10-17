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

    @Test fun cachedMediaAlwaysUsesLocalCacheEvenOnline() {
        assertEquals(RequestRoute.CACHE_ONLY, RequestRouting.select("/media/video.mp4", true, true))
        assertEquals(RequestRoute.CACHE_ONLY, RequestRouting.select("/media/video.mp4?download", true, true))
    }

    @Test fun onlineDocumentsUseNetworkWithCacheFallback() {
        assertEquals(RequestRoute.NETWORK_THEN_CACHE, RequestRouting.select("/api/state", true, true))
    }

    @Test fun cacheFirstSessionUsesEveryCompleteLocalResource() {
        assertEquals(RequestRoute.CACHE_ONLY, RequestRouting.select("/", true, true, forceCache = true))
        assertEquals(RequestRoute.CACHE_ONLY, RequestRouting.select("/api/state", true, true, forceCache = true))
        assertEquals(RequestRoute.CACHE_ONLY, RequestRouting.select("/thumb/a.jpg", true, true, forceCache = true))
        assertEquals(RequestRoute.NETWORK_THEN_CACHE, RequestRouting.select("/thumb/missing.jpg", true, false, forceCache = true))
        assertEquals(RequestRoute.NATIVE, RequestRouting.select("/media/missing.mp4", true, false, forceCache = true))
    }
}
