package io.github.stream29.kodex.cli.app

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.*
import io.ktor.http.*
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.cinterop.convert
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.io.*
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.*
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

private val HostId = "a".repeat(64)
private val Ticket = "b".repeat(64)
private val BrowserPassword = "c".repeat(64)
private val ManagementPassword = "d".repeat(64)
private const val Instance = "isolated-web-host-test"
private const val LargeSize = 256L * 1024 * 1024

val localFrontendHostTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("bootstrap refresh is scoped to the selected backend and normal return revokes only its grant") {
        withFixture { fixture ->
            lateinit var address: HostAddress
            val result = fixture.host { url ->
                address = HostAddress(url)
                assertEquals(Ticket, url.substringAfter('#'))
                assertFalse(url.contains(ManagementPassword))
                assertFalse(url.contains(BrowserPassword))
                assertNotEquals(fixture.endpoint.port, address.port)
                repeat(2) {
                    val reply = fixture.wire.request(address, "POST", address.bootstrap, address.bootstrapHeaders, ticketBody())
                    assertEquals(200, reply.status)
                    reply.assertPrivateHeaders()
                    val info = Json.decodeFromString(LocalFrontendBootstrap.serializer(), reply.body.decodeToString())
                    assertEquals(LocalBrowserGrantProtocol, info.protocol)
                    assertEquals(Instance, info.endpoint.instanceId)
                    assertEquals(fixture.endpoint.port, info.endpoint.port)
                    assertEquals(BackendBusinessProtocol, info.endpoint.businessProtocol)
                    assertEquals(BrowserPassword, info.endpoint.password)
                    assertEquals(fixture.canonicalDirectory, info.workingDirectory)
                    assertFalse(reply.body.decodeToString().contains(ManagementPassword))
                }
                42
            }
            assertEquals(42, result)
            fixture.assertRevoked(address)
            fixture.resources.assertClosed()
        }
    }
    test("bootstrap requires one exact Host and Origin and rejects cross-site fetches") {
        withFixture { fixture ->
            fixture.host { url ->
                val address = HostAddress(url)
                val host = "Host" to address.authority
                val origin = "Origin" to address.origin
                val lookups = fixture.resources.lookups.value
                for (headers in listOf(
                    listOf(origin),
                    listOf("Host" to "localhost:${address.port}", origin),
                    listOf("Host" to "127.0.0.1:1", origin),
                    listOf(host),
                    listOf(host, "Origin" to "null"),
                    listOf(host, "Origin" to "${address.origin}/"),
                    listOf(host, "Origin" to "http://evil.test"),
                    listOf(host, host, origin),
                    listOf(host, origin, origin),
                    listOf(host, origin, "Sec-Fetch-Site" to "cross-site"),
                )) {
                    val reply = fixture.wire.request(address, "POST", address.bootstrap,
                        headers + ("Content-Type" to "application/json"), ticketBody())
                    // CIO rejects duplicate Host fields before application response headers exist.
                    val duplicateHost = headers.count { it.first == HttpHeaders.Host } > 1
                    assertEquals(if (duplicateHost) 400 else 403, reply.status, headers.toString())
                    if (!duplicateHost) reply.assertPrivateHeaders()
                    assertFalse(reply.body.decodeToString().contains(BrowserPassword))
                }
                assertEquals(lookups, fixture.resources.lookups.value)
            }
        }
    }
    test("bootstrap rejects missing malformed wrong or duplicate Content-Type but permits JSON charset") {
        withFixture { fixture ->
            fixture.host { url ->
                val address = HostAddress(url)
                val base = listOf("Host" to address.authority, "Origin" to address.origin)
                for (types in listOf(
                    emptyList(), listOf("text/plain"), listOf("not-a-media-type"),
                    listOf("application/json", "application/json"),
                    listOf("application/json", "text/plain"),
                    listOf("text/plain", "application/json"),
                    listOf("application/json, text/plain"),
                    listOf("text/plain, application/json"),
                )) {
                    val reply = fixture.wire.request(address, "POST", address.bootstrap,
                        base + types.map { "Content-Type" to it }, ticketBody())
                    assertEquals(403, reply.status, types.toString())
                }
                assertEquals(200, fixture.wire.request(address, "POST", address.bootstrap,
                    base + ("Content-Type" to "application/json; charset=utf-8"), ticketBody()).status)
            }
        }
    }
    test("bad tickets invalid UTF-8 and oversized bodies never disclose the scoped credential") {
        withFixture { fixture ->
            fixture.host { url ->
                val address = HostAddress(url)
                for (body in listOf(
                    ticketBody("e".repeat(64)), "{}".encodeToByteArray(), "not JSON".encodeToByteArray(),
                    byteArrayOf(0xc3.toByte(), 0x28),
                    (ticketBody().decodeToString() + " ".repeat(1025)).encodeToByteArray(),
                )) {
                    val reply = fixture.wire.request(address, "POST", address.bootstrap, address.bootstrapHeaders, body)
                    assertEquals(401, reply.status)
                    reply.assertPrivateHeaders()
                    assertFalse(reply.body.decodeToString().contains(BrowserPassword))
                }
                assertEquals(200, fixture.wire.request(address, "POST", address.bootstrap,
                    address.bootstrapHeaders, ticketBody()).status, "Rejected bodies do not consume the launch capability.")
            }
        }
    }
    test("static assets use exact MIME and security headers and close every opened Source") {
        withFixture { fixture ->
            fixture.host { url ->
                val address = HostAddress(url)
                for ((path, mime) in AssetTypes) {
                    val reply = fixture.wire.request(address, "GET", address.base + path)
                    assertEquals(200, reply.status, path)
                    assertEquals(mime, reply.header("content-type")?.substringBefore(';'), path)
                    assertContentEquals(assetBytes(path), reply.body, path)
                    reply.assertPrivateHeaders()
                }
                val index = fixture.wire.request(address, "GET", address.base)
                assertContentEquals(assetBytes("index.html"), index.body)
                assertEquals(404, fixture.wire.request(address, "GET", address.base + "missing.js").status)
                assertEquals(404, fixture.wire.request(address, "GET", "/web/${"e".repeat(64)}/index.html").status)
            }
            fixture.resources.assertClosed()
        }
    }
    test("raw traversal and untrusted static headers are rejected before opening a resource") {
        withFixture { fixture ->
            fixture.host { url ->
                val address = HostAddress(url)
                val lookups = fixture.resources.lookups.value
                for (path in listOf("../secret", "%2e%2e/secret", "a/%2e%2e/secret", "%2fsecret",
                    "%5csecret", "a%00.js", ".hidden/../secret")) {
                    assertEquals(404, fixture.wire.request(address, "GET", address.base + path).status, path)
                }
                for (headers in listOf(
                    listOf("Host" to "localhost:${address.port}"),
                    listOf("Host" to address.authority, "Host" to address.authority),
                    listOf("Host" to address.authority, "Origin" to "http://evil.test"),
                    listOf("Host" to address.authority, "Origin" to address.origin, "Origin" to address.origin),
                    listOf("Host" to address.authority, "Sec-Fetch-Site" to "cross-site"),
                )) {
                    val duplicateHost = headers.count { it.first == HttpHeaders.Host } > 1
                    assertEquals(if (duplicateHost) 400 else 404,
                        fixture.wire.request(address, "GET", address.base, headers).status, headers.toString())
                }
                assertEquals(lookups, fixture.resources.lookups.value)
                for (path in listOf("", "/absolute", ".", "..", "a/../b", "a//b", "a\\b", "x?y")) {
                    assertFalse(frontendAssetPath(path), path)
                }
                assertTrue(frontendAssetPath("assets/@scope/file-1.2.wasm"))
            }
        }
    }
    test("cancelled large download closes its Source without materializing the whole asset") {
        withFixture { fixture ->
            fixture.host { url ->
                val address = HostAddress(url)
                fixture.wire.connected(address) { socket ->
                    socket.sendGet(address, "large.wasm")
                    val input = socket.openReadChannel()
                    assertEquals(200, input.responseHead().first)
                    input.readByteArray(1)
                    socket.close()
                }
                val source = withTimeout(5.seconds) { fixture.resources.large.await() }
                withTimeout(5.seconds) { source.closes.first { it == 1 } }
                assertTrue(source.bytes.value in 1 until LargeSize)
                assertEquals(1, source.closes.value)
            }
            fixture.resources.assertClosed()
        }
    }
    test("cancelling host closes an in-flight Source revokes admission and releases listener") {
        withFixture { fixture ->
            coroutineScope {
                val address = CompletableDeferred<HostAddress>()
                val host = launch {
                    fixture.host { url -> address.complete(HostAddress(url)); awaitCancellation() }
                }
                try {
                    val selected = address.await()
                    fixture.wire.connected(selected) { socket ->
                        socket.sendGet(selected, "large.wasm")
                        assertEquals(200, socket.openReadChannel().responseHead().first)
                        host.cancelAndJoin()
                    }
                    assertTrue(host.isCancelled)
                    fixture.assertRevoked(selected)
                    fixture.resources.assertClosed()
                    assertTrue(fixture.resources.large.await().bytes.value < LargeSize)
                } finally { host.cancelAndJoin() }
            }
        }
    }
    test("block failure retains its identity while reclaiming listener and grant") {
        withFixture { fixture ->
            val failure = IllegalStateException("fixture block failed")
            lateinit var address: HostAddress
            val caught = runCatching {
                fixture.host { url -> address = HostAddress(url); throw failure }
            }.exceptionOrNull()
            assertSame(failure, caught)
            fixture.assertRevoked(address)
            fixture.resources.assertClosed()
        }
    }
    test("accepted registration with lost reply still revokes its exact grant and listener") {
        withFixture(Registration.LostReply) { fixture ->
            var entered = false
            val failure = runCatching { fixture.host { entered = true } }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure is CancellationException)
            assertFalse(entered)
            val grant = fixture.accepted.await()
            fixture.assertRevoked(HostAddress("${grant.origin}/web/$HostId/#$Ticket"))
            fixture.resources.assertClosed()
        }
    }
    test("cancellation during registration leaves bootstrap unavailable then revokes and releases listener") {
        withFixture(Registration.Pending) { fixture ->
            coroutineScope {
                var entered = false
                val host = launch { fixture.host { entered = true } }
                try {
                    val grant = fixture.accepted.await()
                    val address = HostAddress("${grant.origin}/web/$HostId/#$Ticket")
                    val reply = fixture.wire.request(address, "POST", address.bootstrap,
                        address.bootstrapHeaders, ticketBody())
                    assertEquals(503, reply.status)
                    assertFalse(reply.body.decodeToString().contains(BrowserPassword))
                    host.cancelAndJoin()
                    assertTrue(host.isCancelled)
                    assertFalse(entered)
                    fixture.assertRevoked(address)
                    fixture.resources.assertClosed()
                } finally { host.cancelAndJoin() }
            }
        }
    }
    test("missing index fails without contacting control") {
        withFixture { fixture ->
            val failure = runCatching {
                withLocalFrontendHost(fixture.endpoint, fixture.directory, { null }, fixture.secrets()) {
                    fail("Missing website must not launch.")
                }
            }.exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertTrue(fixture.calls.value.isEmpty())
        }
    }
    test("invalid or colliding secrets fail before opening resources or contacting control") {
        withFixture { fixture ->
            for (values in listOf(
                listOf("not-hex", Ticket, BrowserPassword),
                listOf(HostId, HostId, BrowserPassword),
                listOf(HostId, Ticket, ManagementPassword),
            )) {
                val secrets = values.iterator()
                assertFailsWith<IllegalArgumentException> {
                    withLocalFrontendHost(fixture.endpoint, fixture.directory, fixture.resources::open, { secrets.next() }) {
                        fail("Invalid secrets must not launch.")
                    }
                }
            }
            assertTrue(fixture.calls.value.isEmpty())
            assertTrue(fixture.resources.opened.value.isEmpty())
            assertTrue(fixture.resources.lookups.value.isEmpty())
        }
    }
}

private val AssetTypes = mapOf(
    "index.html" to "text/html", "app.js" to "application/javascript", "module.mjs" to "application/javascript",
    "app.wasm" to "application/wasm", "site.css" to "text/css", "data.json" to "application/json",
    "font.ttf" to "font/ttf", "font.woff" to "font/woff", "font.woff2" to "font/woff2",
    "NOTICE.txt" to "text/plain", "README.md" to "text/plain", "payload.bin" to "application/octet-stream",
)

private fun assetBytes(path: String): ByteArray = "isolated-resource:$path:\u00e9".encodeToByteArray()
private fun ticketBody(ticket: String = Ticket): ByteArray =
    Json.encodeToString(LocalWebTicket.serializer(), LocalWebTicket(ticket)).encodeToByteArray()

private class HostAddress(url: String) {
    val origin = url.substringBefore("/web/")
    val authority = origin.removePrefix("http://")
    val port = authority.substringAfterLast(':').toInt()
    val base = url.substringBefore('#').removePrefix(origin)
    val bootstrap = base + "bootstrap"
    val bootstrapHeaders = listOf("Host" to authority, "Origin" to origin, "Content-Type" to "application/json")
}

private class Reply(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
    fun header(name: String): String? = headers.singleOrNull { it.first.equals(name, true) }?.second
    fun assertPrivateHeaders() {
        assertEquals("no-store", header("cache-control"))
        assertEquals("nosniff", header("x-content-type-options"))
        assertEquals("no-referrer", header("referrer-policy"))
        assertEquals("DENY", header("x-frame-options"))
        assertEquals("frame-ancestors 'none'; base-uri 'none'; form-action 'none'", header("content-security-policy"))
        assertTrue(headers.none { it.first.equals("set-cookie", true) || it.first.equals("access-control-allow-origin", true) })
    }
}

/** Raw HTTP keeps duplicate headers and traversal bytes intact on every Native client platform. */
private class Wire {
    private val selectorJob = SupervisorJob()
    private val selector = SelectorManager(Dispatchers.Default + selectorJob)
    suspend fun <R> connected(address: HostAddress, block: suspend (Socket) -> R): R {
        val socket = aSocket(selector).tcp().connect("127.0.0.1", address.port)
        try { return block(socket) }
        finally {
            withContext(NonCancellable) {
                socket.close()
                withTimeout(5.seconds) { socket.socketContext.join() }
            }
        }
    }
    suspend fun request(
        address: HostAddress, method: String, path: String,
        headers: List<Pair<String, String>> = listOf("Host" to address.authority),
        body: ByteArray = byteArrayOf(),
    ): Reply = connected(address) { socket ->
        val output = socket.openWriteChannel(autoFlush = true)
        output.writeStringUtf8("$method $path HTTP/1.1\r\n" +
            headers.joinToString("") { "${it.first}: ${it.second}\r\n" } +
            "Connection: close\r\nContent-Length: ${body.size}\r\n\r\n")
        output.writeFully(body)
        val input = socket.openReadChannel()
        val (status, responseHeaders) = input.responseHead()
        val data = Buffer()
        if (responseHeaders.any { it.first.equals("transfer-encoding", true) && it.second.equals("chunked", true) }) {
            while (true) {
                val size = requireNotNull(input.readLineStrict()).substringBefore(';').toInt(16)
                if (size == 0) {
                    check(input.readLineStrict() == "")
                    break
                }
                require(data.size + size <= 1024 * 1024) { "Fixture response exceeded limit." }
                data.write(input.readByteArray(size))
                check(input.readLineStrict() == "")
            }
        } else {
            input.readRemaining(1024 * 1024 + 1L).use { data.transferFrom(it) }
            check(data.size <= 1024 * 1024)
        }
        Reply(status, responseHeaders, data.readByteArray())
    }
    suspend fun assertListenerReleased(address: HostAddress) {
        withTimeout(5.seconds) {
            while (true) {
                val listener = try {
                    aSocket(selector).tcp().bind("127.0.0.1", address.port) { reuseAddress = true }
                } catch (_: IOException) {
                    delay(20)
                    continue
                }
                listener.close()
                listener.socketContext.join()
                break
            }
        }
    }
    suspend fun close() {
        selector.close()
        selectorJob.complete()
        withTimeout(5.seconds) { selectorJob.join() }
    }
}

private suspend fun Socket.sendGet(address: HostAddress, path: String) {
    openWriteChannel(autoFlush = true).writeStringUtf8(
        "GET ${address.base}$path HTTP/1.1\r\nHost: ${address.authority}\r\nConnection: close\r\n\r\n")
}

private suspend fun ByteReadChannel.responseHead(): Pair<Int, List<Pair<String, String>>> {
    val status = requireNotNull(readLineStrict()).split(' ')[1].toInt()
    val headers = mutableListOf<Pair<String, String>>()
    while (true) {
        val line = requireNotNull(readLineStrict(limit = 8192))
        if (line.isEmpty()) break
        check(headers.size < 64)
        headers += line.substringBefore(':') to line.substringAfter(':').trim()
    }
    return status to headers
}

private class TrackedSource(private val content: ByteArray?, private val length: Long) : RawSource {
    val bytes = MutableStateFlow(0L)
    val closes = MutableStateFlow(0)
    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        check(closes.value == 0)
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        val position = bytes.value
        if (position == length) return -1
        val count = minOf(byteCount, length - position, 8192).toInt()
        if (content == null) sink.write(ByteArray(count) { 0x5a }) else {
            sink.write(content, position.toInt(), position.toInt() + count)
        }
        bytes.update { it + count }
        return count.toLong()
    }
    override fun close() { closes.update { it + 1 } }
}

private class Resources {
    val lookups = MutableStateFlow<List<String>>(emptyList())
    val opened = MutableStateFlow<List<TrackedSource>>(emptyList())
    val large = CompletableDeferred<TrackedSource>()
    fun open(path: String): Source? {
        lookups.update { it + path }
        if (path != "large.wasm" && path !in AssetTypes) return null
        val content = if (path == "large.wasm") null else assetBytes(path)
        val source = TrackedSource(content, content?.size?.toLong() ?: LargeSize)
        opened.update { it + source }
        if (path == "large.wasm") large.complete(source)
        return source.buffered()
    }
    suspend fun assertClosed() {
        for (source in opened.value) {
            withTimeout(5.seconds) { source.closes.first { it > 0 } }
            assertEquals(1, source.closes.value)
        }
    }
}

private enum class Registration { Ready, LostReply, Pending }

private class Fixture(val home: Path, val wire: Wire, val registration: Registration) {
    lateinit var endpoint: BackendEndpoint
    val directory = Path(home, "project").toString()
    val canonicalDirectory = Path(home, "canonical-project").toString()
    val resources = Resources()
    val calls = MutableStateFlow<List<String>>(emptyList())
    val grants = MutableStateFlow<List<LocalBrowserGrant>>(emptyList())
    val revocations = MutableStateFlow<List<LocalBrowserGrant>>(emptyList())
    val accepted = CompletableDeferred<LocalBrowserGrant>()
    val allowReply = CompletableDeferred<Unit>()
    fun secrets(): () -> String = listOf(HostId, Ticket, BrowserPassword).iterator().let { { it.next() } }
    suspend fun <R> host(block: suspend CoroutineScope.(String) -> R): R =
        withLocalFrontendHost(endpoint, directory, resources::open, secrets(), block)
    suspend fun assertRevoked(address: HostAddress) {
        assertEquals(listOf("status", "grant", "revoke"), calls.value)
        val grant = grants.value.single()
        val revoke = revocations.value.single()
        assertEquals(listOf(LocalBrowserGrantProtocol, address.origin, BrowserPassword, directory),
            listOf(grant.protocol, grant.origin, grant.password, grant.workingDirectory))
        assertEquals(listOf(grant.protocol, grant.origin, grant.password, grant.workingDirectory),
            listOf(revoke.protocol, revoke.origin, revoke.password, revoke.workingDirectory))
        wire.assertListenerReleased(address)
        assertEquals(200, wire.request(HostAddress("http://127.0.0.1:${endpoint.port}/web/unused/"),
            "GET", "/fixture/alive").status, "Frontend cleanup must leave the fake backend alive.")
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private suspend fun withFixture(registration: Registration = Registration.Ready, block: suspend (Fixture) -> Unit) {
    val home = Path(SystemTemporaryDirectory, "kodex-web-host-test-${Random.nextLong().toULong()}")
    SystemFileSystem.createDirectories(home, mustCreate = true)
    val wire = Wire()
    val fixture = Fixture(home, wire, registration)
    val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            get("/fixture/alive") { call.respondText("alive") }
            get("/control/status") {
                call.assertManagement(fixture, "status")
                call.respondText(buildJsonObject {
                    put("phase", "ready")
                    put("instance", Instance)
                    put("business_protocol", BackendBusinessProtocol)
                    put("browser_grant_protocol", LocalBrowserGrantProtocol)
                }.toString(), ContentType.Application.Json)
            }
            post("/control/browser/grant") {
                call.assertManagement(fixture, "grant")
                val grant = Json.decodeFromString(LocalBrowserGrant.serializer(), call.receiveText())
                fixture.grants.update { it + grant }
                fixture.accepted.complete(grant)
                when (fixture.registration) {
                    Registration.Ready -> Unit
                    Registration.Pending -> fixture.allowReply.await()
                    Registration.LostReply -> {
                        call.respondBytesWriter(ContentType.Application.Json) {
                            writeStringUtf8("{")
                            flush()
                            throw IOException("Fixture dropped reply after accepting registration.")
                        }
                        return@post
                    }
                }
                call.respondText(buildJsonObject { put("working_directory", fixture.canonicalDirectory) }.toString(),
                    ContentType.Application.Json)
            }
            post("/control/browser/revoke") {
                call.assertManagement(fixture, "revoke")
                val grant = Json.decodeFromString(LocalBrowserGrant.serializer(), call.receiveText())
                fixture.revocations.update { it + grant }
                call.respondText("{}", ContentType.Application.Json)
            }
        }
    }
    try {
        check(platform.posix.chmod(home.toString(), 0x1c0.convert()) == 0) { "Cannot protect fixture Home." }
        SystemFileSystem.createDirectories(Path(fixture.directory), mustCreate = true)
        SystemFileSystem.createDirectories(Path(fixture.canonicalDirectory), mustCreate = true)
        server.startSuspend()
        fixture.endpoint = BackendEndpoint(Instance, 1, server.engine.resolvedConnectors().single().port,
            ManagementPassword, "fixture-only")
        withTimeout(30.seconds) { block(fixture) }
    } finally {
        withContext(NonCancellable) {
            fixture.allowReply.complete(Unit)
            try { server.stopSuspend() }
            finally {
                try { wire.close() }
                finally { removeFixture(home) }
            }
        }
    }
    assertFalse(SystemFileSystem.exists(home))
}

private fun ApplicationCall.assertManagement(fixture: Fixture, action: String) {
    assertEquals("Bearer $ManagementPassword", request.headers[HttpHeaders.Authorization])
    assertEquals(Instance, request.headers["X-Kodex-Instance"])
    assertNull(request.headers[HttpHeaders.Origin])
    fixture.calls.update { it + action }
}

private fun removeFixture(path: Path) {
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach(::removeFixture)
    }
    SystemFileSystem.delete(path, mustExist = false)
}
