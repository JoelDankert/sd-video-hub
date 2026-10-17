package com.sdvideohub.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class HubStatus { UNKNOWN, REACHABLE, UNREACHABLE }

/** The site's sole origin inside Android. It is deliberately package-independent JVM code for real integration tests. */
class LoopbackHttpServer(
    private val repository: CacheRepository,
    private val upstreamBase: String,
    private val credentials: Credentials,
    private val diagnostics: (String) -> Unit = {},
) : AutoCloseable {
    private val secretBytes = ByteArray(32).also(SecureRandom()::nextBytes)
    val secret: String = secretBytes.joinToString("") { "%02x".format(Locale.US, it) }
    private val running = AtomicBoolean(false)
    private val status = AtomicReference(HubStatus.UNKNOWN)
    private val latestRemoteState = AtomicReference<ByteArray?>(null)
    private val clients = Executors.newCachedThreadPool { task -> Thread(task, "sdh-loopback-client").apply { isDaemon = true } }
    private var probes: ScheduledExecutorService? = null
    private var socket: ServerSocket? = null
    val port: Int get() = socket?.localPort ?: error("Server not started")
    val origin: String get() = "http://127.0.0.1:$port"

    @Synchronized fun start(): LoopbackHttpServer {
        if (running.get()) return this
        socket = try {
            ServerSocket(PREFERRED_PORT, 50, InetAddress.getByName("127.0.0.1"))
        } catch (_: Exception) {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        }.apply { reuseAddress = false }
        check(socket!!.inetAddress.hostAddress == "127.0.0.1")
        running.set(true)
        Thread(::acceptLoop, "sdh-loopback-accept").apply { isDaemon = true; start() }
        probes = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "sdh-hub-probe").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay(::probe, 0, 4, TimeUnit.SECONDS) }
        diagnostics("loopback started port=$port cached=${repository.urls().size}")
        return this
    }

    fun hubStatus(): HubStatus = status.get()
    internal fun setHubStatusForTests(value: HubStatus) { status.set(value) }
    internal fun probeNowForTests() = probe()

    private fun acceptLoop() {
        while (running.get()) try {
            val client = socket!!.accept()
            clients.execute { client.use(::handle) }
        } catch (_: SocketException) {
            if (running.get()) diagnostics("loopback accept failed")
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 8_000
        val input = BufferedInputStream(client.getInputStream())
        val output = ResponseOutput(BufferedOutputStream(client.getOutputStream()))
        val request = try { readRequest(input) } catch (_: Exception) {
            writeSimple(output, 400, "Bad Request", "bad request")
            return
        } ?: return
        val started = System.nanoTime()
        try {
            if (!authorized(request.headers["cookie"])) {
                writeSimple(output, 403, "Forbidden", "forbidden")
                return
            }
            if (request.headers["host"] != "127.0.0.1:$port") {
                writeSimple(output, 403, "Forbidden", "host rejected")
                return
            }
            if (request.method !in setOf("GET", "HEAD", "POST")) {
                writeSimple(output, 405, "Method Not Allowed", "method not allowed")
                return
            }
            if (!allowedTarget(request.target)) {
                writeSimple(output, 404, "Not Found", "not found")
                return
            }
            val requestOrigin = request.headers["origin"]
            if ((request.method == "POST" && requestOrigin != origin) ||
                (request.method != "POST" && requestOrigin != null && requestOrigin != origin)) {
                writeSimple(output, 403, "Forbidden", "origin rejected")
                return
            }
            route(request, output)
        } catch (error: Exception) {
            diagnostics("request failed method=${request.method} path=${safeLabel(request.target)} type=${error.javaClass.simpleName}")
            if (!output.committed) try { writeSimple(output, 502, "Bad Gateway", "hub unavailable") } catch (_: Exception) {}
        } finally {
            try { output.flush() } catch (_: Exception) {}
            val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            diagnostics("request method=${request.method} path=${safeLabel(request.target)} elapsedMs=$elapsed hub=${status.get()}")
        }
    }

    private fun route(request: Request, output: ResponseOutput) {
        val path = request.target.substringBefore('?')
        val cached = repository.open(request.target) ?: repository.open(path)
        val isState = path == "/api/state"
        val isRoot = path == "/"

        if (isState) {
            val remote = latestRemoteState.get()
            if (status.get() == HubStatus.REACHABLE && remote != null) {
                serveBytes(remote, "application/json; charset=utf-8", request, output)
            } else if (cached != null) {
                serveFilteredState(cached, request, output)
            } else {
                writeSimple(output, 503, "Service Unavailable", "hub unavailable")
            }
            return
        }
        if (cached != null) {
            serveFile(cached, request, output)
            return
        }
        // Current state embeds per-file skip_intervals. Never make first gallery render
        // wait on optional legacy metadata, even while a stale probe still says reachable.
        if (path.startsWith("/media/") && path.endsWith(".flight-skip.json") && repository.complete("/api/state")) {
            serveBytes("{\"videos\":{}}".toByteArray(StandardCharsets.UTF_8), "application/json; charset=utf-8", request, output)
            return
        }
        if (status.get() != HubStatus.REACHABLE && !isRoot) {
            writeSimple(output, 503, "Service Unavailable", "hub unavailable")
            return
        }
        proxy(request, output)
    }

    private fun serveFilteredState(resource: CachedResource, request: Request, output: ResponseOutput) {
        val body = repository.filterCachedState(resource.file.readText())
            .toByteArray(StandardCharsets.UTF_8)
        serveBytes(body, "application/json; charset=utf-8", request, output)
    }

    private fun serveBytes(body: ByteArray, mime: String, request: Request, output: ResponseOutput) {
        writeHeaders(output, 200, "OK", mapOf(
            "Content-Type" to mime,
            "Content-Length" to body.size.toString(),
            "Cache-Control" to "no-store",
        ))
        if (request.method != "HEAD") output.write(body)
    }

    private fun serveFile(resource: CachedResource, request: Request, output: ResponseOutput) {
        val size = resource.file.length()
        val range = try { RangeLogic.parse(request.headers["range"], size) } catch (_: Exception) {
            writeHeaders(output, 416, "Range Not Satisfiable", mapOf(
                "Content-Range" to "bytes */$size", "Content-Length" to "0", "Accept-Ranges" to "bytes"))
            return
        }
        val length = range?.length ?: size
        val headers = linkedMapOf(
            "Content-Type" to resource.metadata.mime,
            "Content-Length" to length.toString(),
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-store",
        )
        range?.let { headers["Content-Range"] = "bytes ${it.start}-${it.endInclusive}/$size" }
        writeHeaders(output, if (range == null) 200 else 206, if (range == null) "OK" else "Partial Content", headers)
        if (request.method == "HEAD") return
        FileInputStream(resource.file).use { file ->
            if (range != null) file.channel.position(range.start)
            val source: InputStream = if (range == null) file else BoundedInputStream(file, range.length)
            source.copyTo(output, 64 * 1024)
        }
    }

    private fun proxy(request: Request, output: ResponseOutput) {
        val upstreamHead = request.method == "HEAD"
        val proxyRange = request.headers["range"] ?: if (upstreamHead) "bytes=0-0" else null
        val connection = Network.open(upstreamBase, request.target, credentials, proxyRange)
        connection.requestMethod = if (upstreamHead) "GET" else request.method
        if (request.method == "POST") {
            connection.doOutput = true
            request.headers["content-type"]?.let { connection.setRequestProperty("Content-Type", it) }
            connection.setRequestProperty("Origin", upstreamOrigin())
            connection.setFixedLengthStreamingMode(request.body.size)
            connection.outputStream.use { it.write(request.body) }
        }
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                writeSimple(output, 502, "Bad Gateway", "upstream redirect rejected")
                status.set(HubStatus.REACHABLE)
                return
            }
            status.set(HubStatus.REACHABLE)
            val source = if (code >= 400) connection.errorStream else connection.inputStream
            val headers = linkedMapOf<String, String>()
            for (name in listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Content-Disposition")) {
                connection.getHeaderField(name)?.let { headers[name] = it }
            }
            headers["Cache-Control"] = "no-store"
            if (upstreamHead && code in 200..299) {
                val total = connection.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    ?: connection.getHeaderFieldLong("Content-Length", -1).takeIf { it >= 0 }
                if (total != null) headers["Content-Length"] = total.toString()
                headers.remove("Content-Range")
            }
            if (!headers.containsKey("Content-Length")) headers["Connection"] = "close"
            val responseCode = if (upstreamHead && code in 200..299) 200 else code
            writeHeaders(output, responseCode, if (responseCode == 200) "OK" else connection.responseMessage ?: reason(responseCode), headers)
            if (!upstreamHead) source?.use { it.copyTo(output, 64 * 1024) }
        } catch (error: Exception) {
            status.set(HubStatus.UNREACHABLE)
            throw error
        } finally { connection.disconnect() }
    }

    private fun probe() {
        if (!running.get()) return
        val started = System.nanoTime()
        val connection = try { Network.open(upstreamBase, "/api/state", credentials) } catch (_: Exception) {
            status.set(HubStatus.UNREACHABLE); return
        }
        try {
            connection.connectTimeout = 900
            connection.readTimeout = 900
            if (connection.responseCode in 200..299) {
                latestRemoteState.set(connection.inputStream.use { it.readBytes() })
                status.set(HubStatus.REACHABLE)
            } else status.set(HubStatus.UNREACHABLE)
        } catch (_: Exception) { status.set(HubStatus.UNREACHABLE) }
        finally {
            connection.disconnect()
            diagnostics("probe elapsedMs=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} status=${status.get()}")
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val line = readLine(input, 16_384) ?: return null
        val parts = line.split(' ', limit = 3)
        require(parts.size == 3 && parts[2].startsWith("HTTP/1."))
        val method = parts[0].uppercase(Locale.US)
        val target = parts[1]
        require(target.startsWith('/') && !target.startsWith("//") && !target.contains('\r') && !target.contains('\n'))
        val headers = linkedMapOf<String, String>()
        while (true) {
            val header = readLine(input, 16_384) ?: throw IllegalArgumentException("truncated headers")
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            require(colon > 0)
            headers[header.substring(0, colon).trim().lowercase(Locale.US)] = header.substring(colon + 1).trim()
            require(headers.size <= 80)
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 0..1_048_576)
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(body, offset, length - offset)
            if (count < 0) throw IllegalArgumentException("truncated body")
            offset += count
        }
        return Request(method, target, headers, body)
    }

    private fun readLine(input: InputStream, limit: Int): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= limit) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else throw IllegalArgumentException("truncated line")
            if (value == '\n'.code) {
                if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            }
            bytes.add(value.toByte())
        }
        throw IllegalArgumentException("line too long")
    }

    private fun authorized(cookie: String?): Boolean {
        val supplied = cookie?.split(';')?.map(String::trim)?.firstOrNull { it.startsWith("$COOKIE_NAME=") }
            ?.substringAfter('=') ?: return false
        return MessageDigest.isEqual(secret.toByteArray(StandardCharsets.US_ASCII), supplied.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun allowedTarget(target: String): Boolean {
        if (target.contains('#') || target.contains("..") || target.contains('\\')) return false
        val path = target.substringBefore('?')
        return path == "/" || path == "/api/state" || path == "/api/clear" ||
            path == "/api/flight-download" || path.startsWith("/api/flight-download/") ||
            path.startsWith("/flight-download/") || path.startsWith("/stream480/") ||
            path.startsWith("/thumb/") || path.startsWith("/media/")
    }

    private fun writeSimple(output: ResponseOutput, code: Int, reason: String, message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        writeHeaders(output, code, reason, mapOf("Content-Type" to "text/plain; charset=utf-8", "Content-Length" to body.size.toString()))
        output.write(body)
    }

    private fun writeHeaders(output: ResponseOutput, code: Int, reason: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value.replace("\r", "").replace("\n", "")).append("\r\n") }
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(text.toByteArray(StandardCharsets.ISO_8859_1))
        output.committed = true
        output.flush()
    }

    private fun upstreamOrigin(): String {
        val uri = URI(upstreamBase)
        val portPart = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme}://${uri.host}$portPart"
    }

    private fun safeLabel(target: String) = target.substringBefore('?').take(160)
    private fun reason(code: Int) = when (code) { 200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 416 -> "Range Not Satisfiable"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "Upstream" }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        probes?.shutdownNow()
        socket?.close()
        clients.shutdownNow()
        diagnostics("loopback stopped")
    }

    private data class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray)

    private class ResponseOutput(private val delegate: OutputStream) : OutputStream() {
        var committed = false
        override fun write(value: Int) = delegate.write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = delegate.write(bytes, offset, length)
        override fun flush() = delegate.flush()
    }

    companion object {
        const val COOKIE_NAME = "sdh_loopback_session"
        private const val PREFERRED_PORT = 18787
    }
}
