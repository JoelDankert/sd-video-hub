package com.sdvideohub.app

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LoopbackHttpServerIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var root: File
    private lateinit var repository: CacheRepository
    private lateinit var upstream: FakeUpstream
    private lateinit var loopback: LoopbackHttpServer
    private val credentials = Credentials("", "pilot", "secret")
    private val upstreamRequests = Collections.synchronizedList(mutableListOf<UpstreamRequest>())
    private lateinit var mp4: ByteArray
    private val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")

    @Before fun setUp() {
        root = temporary.newFolder("cache")
        CacheRepository.forgetForTests(root)
        repository = CacheRepository.open(root)
        mp4 = createMp4(temporary.newFile("fixture.mp4"))
        upstream = FakeUpstream(mp4, png, credentials, upstreamRequests)
        loopback = LoopbackHttpServer(
            repository,
            "http://127.0.0.1:${upstream.port}",
            credentials.copy(baseUrl = "http://127.0.0.1:${upstream.port}"),
        ).start()
    }

    @After fun tearDown() {
        loopback.close()
        upstream.close()
        CacheRepository.forgetForTests(root)
    }

    @Test fun offlineCachedDocumentStateThumbAndRealMediaAreImmediateAndPartialGalleryIsFiltered() {
        install("/", "text/html", "<html>saved shell</html>".toByteArray())
        install("/thumb/%2Ftmp%2FFlug%20%C3%84.jpg", "image/jpeg", byteArrayOf(1, 2, 3))
        install("/media/%2Ftmp%2FFlug%20%C3%84.mp4", "video/mp4", mp4)
        install("/api/state", "application/json", """{"files":[
          {"id":1,"status":"done","video_url":"/media/%2Ftmp%2FFlug%20%C3%84.mp4"},
          {"id":2,"status":"done","video_url":"/media/%2Ftmp%2FMissing.mp4"}],"sessions":[],"devices":[]}""".toByteArray())
        upstream.close()
        loopback.setHubStatusForTests(HubStatus.UNREACHABLE)

        val started = System.nanoTime()
        assertEquals("<html>saved shell</html>", request("GET", "/").body.toString(Charsets.UTF_8))
        val state = JSONObject(request("GET", "/api/state").body.toString(Charsets.UTF_8))
        assertEquals(1, state.getJSONArray("files").length())
        assertArrayEquals(byteArrayOf(1, 2, 3), request("GET", "/thumb/%2Ftmp%2FFlug%20%C3%84.jpg").body)
        assertArrayEquals(mp4, request("GET", "/media/%2Ftmp%2FFlug%20%C3%84.mp4").body)
        assertTrue("cached requests took too long", elapsedMs(started) < 500)
        assertEquals(503, request("GET", "/media/%2Ftmp%2FMissing.mp4").code)
    }

    @Test fun cachedMediaSupportsHeadClosedOpenSuffixAndUnsatisfiableRanges() {
        install("/media/%2Fa%20b.mp4", "video/mp4", mp4)
        loopback.setHubStatusForTests(HubStatus.UNREACHABLE)
        val head = request("HEAD", "/media/%2Fa%20b.mp4")
        assertEquals(200, head.code); assertEquals(mp4.size.toString(), head.headers["Content-Length"]); assertEquals(0, head.body.size)
        val closed = request("GET", "/media/%2Fa%20b.mp4", "bytes=2-12")
        assertEquals(206, closed.code); assertEquals("bytes 2-12/${mp4.size}", closed.headers["Content-Range"])
        assertArrayEquals(mp4.copyOfRange(2, 13), closed.body)
        assertArrayEquals(mp4.copyOfRange(7, mp4.size), request("GET", "/media/%2Fa%20b.mp4", "bytes=7-").body)
        assertArrayEquals(mp4.takeLast(9).toByteArray(), request("GET", "/media/%2Fa%20b.mp4", "bytes=-9").body)
        val invalid = request("GET", "/media/%2Fa%20b.mp4", "bytes=${mp4.size}-")
        assertEquals(416, invalid.code); assertEquals("bytes */${mp4.size}", invalid.headers["Content-Range"])
    }

    @Test fun uncachedEncodedMediaIsAuthenticatedAndRangeStreamedByNativeLayer() {
        loopback.setHubStatusForTests(HubStatus.REACHABLE)
        val target = "/media/%2Fvideos%2FGr%C3%BC%C3%9Fe%20aus%20K%C3%B6ln.mp4"
        val response = request("GET", target, "bytes=4-31")
        assertEquals(206, response.code)
        assertEquals("bytes 4-31/${mp4.size}", response.headers["Content-Range"])
        assertArrayEquals(mp4.copyOfRange(4, 32), response.body)
        assertTrue(upstreamRequests.any { it.target == target && it.authorization == Network.authorization(credentials) })
        assertTrue(upstreamRequests.none { it.method == "HEAD" })
        val head = request("HEAD", "/media/%2Fvideos%2Funcached.mp4")
        assertEquals(200, head.code)
        assertEquals(mp4.size.toString(), head.headers["Content-Length"])
        assertEquals(0, head.body.size)
        assertTrue(upstreamRequests.none { it.method == "HEAD" })
    }

    @Test fun invalidCookieAndCrossOriginMutationAreRejected() {
        assertEquals(403, request("GET", "/", cookie = "wrong").code)
        assertEquals(403, rawStatus("POST", "/api/clear", "127.0.0.1:${loopback.port}", "http://evil.invalid"))
        assertEquals(403, rawStatus("GET", "/", "evil.invalid", null))
        loopback.setHubStatusForTests(HubStatus.REACHABLE)
        assertEquals(200, rawStatus("POST", "/api/flight-download", "127.0.0.1:${loopback.port}", loopback.origin))
        assertEquals("http://127.0.0.1:${upstream.port}", upstreamRequests.last { it.method == "POST" }.origin)
        assertNull(upstreamRequests.last { it.method == "POST" }.cookie)
    }

    @Test fun interruptedOrWrongSizedReplacementNeverPublishesPartial() {
        val old = "complete-old".toByteArray()
        install("/media/a.mp4", "video/mp4", old, version = "old")
        val partial = temporary.newFile("partial").apply { writeBytes(byteArrayOf(1, 2)) }
        try {
            repository.install(ManifestEntry("/media/a.mp4", "video/mp4", 10, "new"), partial)
            fail("wrong-sized transfer was accepted")
        } catch (_: IllegalArgumentException) {}
        assertArrayEquals(old, repository.open("/media/a.mp4")!!.file.readBytes())
        assertEquals("old", repository.metadata("/media/a.mp4")!!.version)
    }

    @Test fun legacyFilesMigrateWithoutRedownloadAndConcurrentInstallReadLosesNoIndexEntries() {
        loopback.close()
        CacheRepository.forgetForTests(root)
        File(root, "index.json").writeText("""{"/":{"version":"v1","mime":"text/html"}}""")
        cacheFile(root, "/").writeText("shell")
        repository = CacheRepository.open(root)
        assertTrue(repository.complete("/"))
        assertTrue(SyncPlanner.plan(repository.recordsForPlanning(), listOf(ManifestEntry("/", "text/html", 5, "v1"))).downloads.isEmpty())

        val count = 40
        val pool = Executors.newFixedThreadPool(8)
        val done = CountDownLatch(count)
        repeat(count) { index -> pool.execute {
            try {
                val body = "body-$index".toByteArray()
                val part = File(root, "part-$index").apply { writeBytes(body) }
                repository.install(ManifestEntry("/thumb/$index.jpg", "image/jpeg", body.size.toLong(), "v$index"), part)
                assertArrayEquals(body, repository.open("/thumb/$index.jpg")!!.file.readBytes())
            } finally { done.countDown() }
        } }
        assertTrue(done.await(10, TimeUnit.SECONDS)); pool.shutdownNow()
        assertEquals(count + 1, CacheIndexStore(root).read().size)
    }

    @Test fun reopenedLegacyVideosPopulateOfflineGalleryAndMissingOrTruncatedFilesDoNot() {
        loopback.close()
        CacheRepository.forgetForTests(root)
        val good = "/media/legacy-good.mp4"
        val missing = "/media/legacy-missing.mp4"
        val truncated = "/media/legacy-truncated.mp4"
        File(root, "index.json").writeText("""{
          "$good":{"version":"v1","mime":"video/mp4"},
          "$missing":{"version":"v1","mime":"video/mp4"},
          "$truncated":{"version":"v1","mime":"video/mp4","size":99}
        }""")
        cacheFile(root, good).writeBytes(mp4)
        cacheFile(root, truncated).writeBytes(byteArrayOf(1, 2))
        repository = CacheRepository.open(root)
        val filtered = JSONObject(repository.filterCachedState("""{"files":[
          {"video_url":"$good"},{"video_url":"$missing"},{"video_url":"$truncated"}]}"""))
        assertEquals(1, filtered.getJSONArray("files").length())
        assertEquals(good, filtered.getJSONArray("files").getJSONObject(0).getString("video_url"))
        assertNotNull(repository.metadata(good)?.expectedSize)
    }

    @Test fun stalledUpstreamProbeCannotDelayCachedPlayback() {
        install("/media/cached.mp4", "video/mp4", mp4)
        // Cached routing never waits for status/probes, including UNKNOWN.
        loopback.setHubStatusForTests(HubStatus.UNKNOWN)
        val started = System.nanoTime()
        assertArrayEquals(mp4, request("GET", "/media/cached.mp4").body)
        assertTrue(elapsedMs(started) < 300)
    }

    @Test fun chromiumPlaysAndSeeksOnlineUncachedThenDisconnectedCachedMediaWithOfflineGalleryReady() {
        val repoRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "index.html").isFile }
        val shell = File(repoRoot, "index.html").readText()
            .replace("__SDH_CUTLIST__", JSONObject.quote("/library/.flight-skip.json"))
            .toByteArray()
        val cachedPath = "/media/%2Fvideos%2Foffline%20%C3%84.mp4"
        val thumbPath = "/thumb/%2Fthumbs%2Foffline%20%C3%84.jpg"
        install("/", "text/html; charset=utf-8", shell)
        install(cachedPath, "video/mp4", mp4)
        install(thumbPath, "image/png", png)
        install("/api/state", "application/json", stateJson(cachedPath, thumbPath, "offline.mp4").toByteArray())
        val onlinePath = "/media/%2Fvideos%2Fonline%20%C3%84.mp4"
        val onlineThumb = "/thumb/%2Fthumbs%2Fonline%20%C3%84.png"
        upstream.stateBody = stateJson(onlinePath, onlineThumb, "online.mp4").toByteArray()
        loopback.probeNowForTests()
        waitForReachableState()
        val online = runBrowserHarness(onlinePath, 1)
        println("PLAYWRIGHT_ONLINE $online")
        assertTrue(online.contains("\"advanced\""))
        upstream.close()
        loopback.setHubStatusForTests(HubStatus.UNKNOWN)
        val offlineStarted = System.nanoTime()
        val offline = runBrowserHarness(cachedPath, 1)
        println("PLAYWRIGHT_OFFLINE $offline")
        assertTrue(offline.contains("\"cards\":1"))
        assertTrue("offline browser readiness/playback exceeded 10 seconds", elapsedMs(offlineStarted) < 10_000)
    }

    @Test fun unknownOfflineMissingCutlistAndFull106CardCacheRenderImmediately() {
        val repoRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "index.html").isFile }
        install("/", "text/html; charset=utf-8", File(repoRoot, "index.html").readText()
            .replace("__SDH_CUTLIST__", JSONObject.quote("/library/.flight-skip.json")).toByteArray())
        val files = (0 until 106).map { index ->
            val media = "/media/library-$index.mp4"
            val thumb = "/thumb/library-$index.png"
            install(media, "video/mp4", mp4)
            install(thumb, "image/png", png)
            """{"id":$index,"status":"done","name":"video-$index.mp4","output":"/library/video-$index.mp4","output_size":${mp4.size},"duration":4,"flight_duration":4,"video_url":"$media","thumb_url":"$thumb","skip_intervals":[]}"""
        }
        install("/api/state", "application/json", """{"files":[${files.joinToString(",")}],"sessions":[],"devices":[],"settings":{"archive":{}}}""".toByteArray())
        upstream.close()
        loopback.setHubStatusForTests(HubStatus.UNKNOWN)
        val started = System.nanoTime()
        val result = runBrowserHarness("/media/library-0.mp4", 106)
        println("PLAYWRIGHT_106_UNKNOWN $result")
        assertTrue(result.contains("\"cards\":106"))
        assertTrue("106-card UNKNOWN render/playback exceeded 10 seconds", elapsedMs(started) < 10_000)
        val cutlist = request("GET", "/media/%2Flibrary%2F.flight-skip.json")
        assertEquals(200, cutlist.code)
        assertEquals(0, JSONObject(cutlist.body.toString(Charsets.UTF_8)).getJSONObject("videos").length())
    }

    private fun install(url: String, mime: String, body: ByteArray, version: String = "v1") {
        val temporaryFile = File(root, "source-${System.nanoTime()}").apply { writeBytes(body) }
        repository.install(ManifestEntry(url, mime, body.size.toLong(), version), temporaryFile)
    }

    private fun request(method: String, path: String, range: String? = null, cookie: String = loopback.secret, origin: String? = null, host: String? = null): Response {
        val connection = URL(loopback.origin + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 2_000; connection.readTimeout = 3_000
        connection.setRequestProperty("Cookie", "${LoopbackHttpServer.COOKIE_NAME}=$cookie")
        host?.let { connection.setRequestProperty("Host", it) }
        range?.let { connection.setRequestProperty("Range", it) }
        origin?.let { connection.setRequestProperty("Origin", it) }
        if (method == "POST") { connection.doOutput = true; connection.outputStream.use { it.write("{}".toByteArray()) } }
        val code = connection.responseCode
        val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.first() }
        val body = (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() } ?: byteArrayOf()
        connection.disconnect()
        return Response(code, headers, body)
    }

    private fun createMp4(file: File): ByteArray {
        val process = ProcessBuilder("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
            "color=c=blue:s=160x90:d=4:r=12", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", file.absolutePath)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "ffmpeg fixture failed: $output" }
        return file.readBytes().also { assertTrue(it.size > 1000) }
    }

    private fun runBrowserHarness(mediaPath: String, cards: Int): String {
        val repoRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "browser_playback_harness.cjs").isFile }
        val script = File(repoRoot, "browser_playback_harness.cjs")
        val process = ProcessBuilder("node", script.absolutePath, loopback.origin, loopback.secret, mediaPath, cards.toString(), WebPageScripts.REFRESH)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("browser harness failed (${process.waitFor()}): $output", process.exitValue() == 0)
        return output.trim()
    }

    private fun rawStatus(method: String, path: String, host: String, origin: String?): Int {
        Socket("127.0.0.1", loopback.port).use { socket ->
            val body = if (method == "POST") "{}" else ""
            val request = buildString {
                append("$method $path HTTP/1.1\r\nHost: $host\r\n")
                append("Cookie: ${LoopbackHttpServer.COOKIE_NAME}=${loopback.secret}\r\n")
                origin?.let { append("Origin: $it\r\n") }
                append("Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body")
            }
            socket.getOutputStream().apply { write(request.toByteArray()); flush() }
            return socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }
    }

    private fun stateJson(media: String, thumb: String, name: String) =
        """{"files":[{"id":1,"status":"done","name":"$name","output":"/library/$name","output_size":${mp4.size},"duration":4,"flight_duration":4,"video_url":"$media","thumb_url":"$thumb","skip_intervals":[]}],"sessions":[],"devices":[],"settings":{"archive":{}}}"""

    private fun waitForReachableState() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (loopback.hubStatus() != HubStatus.REACHABLE && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(HubStatus.REACHABLE, loopback.hubStatus())
    }

    private fun elapsedMs(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    private data class Response(val code: Int, val headers: Map<String, String>, val body: ByteArray)

    private class FakeUpstream(
        private val mp4: ByteArray,
        private val png: ByteArray,
        private val credentials: Credentials,
        private val requests: MutableList<UpstreamRequest>,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        @Volatile private var running = true
        @Volatile var stateBody: ByteArray = "{\"files\":[],\"sessions\":[],\"devices\":[]}".toByteArray()
        val port get() = socket.localPort
        init { executor.execute { while (running) try { val client = socket.accept(); executor.execute { client.use(::handle) } } catch (_: Exception) {} } }
        private fun handle(client: Socket) {
            val input = client.getInputStream().buffered()
            val output = client.getOutputStream().buffered()
            val requestLine = readLine(input) ?: return
            val requestParts = requestLine.split(' ')
            val method = requestParts[0]; val target = requestParts[1]
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            requests += UpstreamRequest(method, target, headers["authorization"], headers["origin"], headers["cookie"])
            if (method == "HEAD") {
                output.write("HTTP/1.1 501 Not Implemented\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush(); return
            }
            if (headers["authorization"] != Network.authorization(credentials)) {
                output.write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); output.flush(); return
            }
            val path = target.substringBefore('?')
            val body = when { path == "/api/state" -> stateBody; path == "/" -> "<html>online</html>".toByteArray(); path.startsWith("/thumb/") -> png; else -> mp4 }
            val range = try { RangeLogic.parse(headers["range"], body.size.toLong()) } catch (_: Exception) { null }
            val response = range?.let { body.copyOfRange(it.start.toInt(), it.endInclusive.toInt() + 1) } ?: body
            val responseHeaders = buildString {
                append("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\n")
                append("Content-Type: ${if (path.endsWith(".mp4")) "video/mp4" else if (path.startsWith("/thumb/")) "image/png" else "application/json"}\r\n")
                append("Content-Length: ${response.size}\r\nAccept-Ranges: bytes\r\n")
                range?.let { append("Content-Range: bytes ${it.start}-${it.endInclusive}/${body.size}\r\n") }
                append("Connection: close\r\n\r\n")
            }
            output.write(responseHeaders.toByteArray())
            if (method != "HEAD") output.write(response)
            output.flush()
        }
        private fun readLine(input: java.io.InputStream): String? {
            val result = StringBuilder()
            while (true) { val c = input.read(); if (c < 0) return null; if (c == '\n'.code) return result.toString().trimEnd('\r'); result.append(c.toChar()) }
        }
        override fun close() { running = false; socket.close(); executor.shutdownNow() }
    }
    private data class UpstreamRequest(val method: String, val target: String, val authorization: String?, val origin: String?, val cookie: String?)
}
