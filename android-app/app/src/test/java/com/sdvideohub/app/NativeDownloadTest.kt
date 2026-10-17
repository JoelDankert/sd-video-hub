package com.sdvideohub.app

import org.junit.Assert.*
import org.junit.Test

class NativeDownloadTest {
    private val origin = "http://127.0.0.1:18787"

    @Test fun onlyExactTrustedDownloadRoutesAreAcceptedBeforeNavigation() {
        assertEquals(DownloadKind.MEDIA, DownloadRouting.fromLoopbackUrl("$origin/media/V%C3%ADdeo.mp4?download", origin)?.kind)
        assertEquals("Vídeo.mp4", DownloadRouting.fromLoopbackUrl("$origin/media/V%C3%ADdeo.mp4?download", origin)?.suggestedName)
        assertEquals(DownloadKind.FLIGHT_EXPORT, DownloadRouting.fromLoopbackUrl("$origin/flight-download/Abc_123456789", origin)?.kind)
        assertNull(DownloadRouting.fromLoopbackUrl("$origin/media/a.mp4", origin))
        assertNull(DownloadRouting.fromLoopbackUrl("https://example.test/media/a.mp4?download", origin))
        assertNull(DownloadRouting.fromLoopbackUrl("$origin/media/a.mp4?download=x", origin))
    }

    @Test fun acceptsRealEncodedAbsoluteMediaPathsWithoutDoubleDecoding() {
        val path = "/media/%2Fhome%2Fjoeld%2Fvideos%2FGr%C3%BCn%20%2B%20%252F.mp4?download"
        val request = DownloadRouting.fromLoopbackUrl(origin + path, origin)!!
        assertEquals(path, request.path)
        assertEquals("Grün + %2F.mp4", request.suggestedName)
    }

    @Test fun rejectsTraversalAndUnsafeExportTokens() {
        assertNull(DownloadRouting.fromLoopbackUrl("$origin/media/%2E%2E%2Fb.mp4?download", origin))
        assertNull(DownloadRouting.fromLoopbackUrl("$origin/flight-download/a", origin))
        assertNull(DownloadRouting.fromLoopbackUrl("$origin/flight-download/../../secret", origin))
        assertNull(DownloadRouting.fromLoopbackUrl("$origin/flight-download/abc.defghijkl", origin))
    }

    @Test fun contentDispositionSupportsUnicodeAndSanitizesPaths() {
        assertEquals("Flug ü.mp4", DownloadRouting.contentDispositionFilename("attachment; filename*=UTF-8''Flug%20%C3%BC.mp4", "fallback.mp4"))
        assertEquals("_secret.mp4", DownloadRouting.contentDispositionFilename("attachment; filename=\"../secret.mp4\"", "fallback.mp4"))
        assertEquals("fallback.mp4", DownloadRouting.contentDispositionFilename(null, "fallback.mp4"))
    }

    @Test fun documentStartCapturePreventsSyntheticLinkBeforeGet() {
        val script = SettingsBootstrap.script("{}")
        assertTrue(script.contains("document.addEventListener('click'"))
        assertTrue(script.contains("e.preventDefault();e.stopImmediatePropagation()"))
        assertTrue(script.contains("a[download]"))
    }
}
