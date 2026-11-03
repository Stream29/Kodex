package io.github.stream29.kodex.cli.app

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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val lastOwnedFixedCallback = MutableStateFlow<TimeMark?>(null)

internal class RoundTripHttpReply(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)

/** Socket-only test client: never launches a browser, follows redirects, or contacts a non-loopback host. */
internal class RoundTripWire {
    private val job = SupervisorJob()
    private val selector = SelectorManager(Dispatchers.Default + job)

    private suspend fun <R> connected(url: Url, block: suspend (Socket) -> R): R {
        require(url.protocol.name == "http" && url.host in setOf("localhost", "127.0.0.1"))
        val socket = aSocket(selector).tcp().connect("127.0.0.1", url.port)
        try { return block(socket) }
        finally {
            withContext(NonCancellable) {
                socket.close()
                withTimeout(5.seconds) { socket.socketContext.join() }
            }
        }
    }

    suspend fun request(
        address: String,
        method: String = "GET",
        path: String? = null,
        origin: String? = null,
        authority: String? = null,
        body: String = "",
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): RoundTripHttpReply = withTimeout(15.seconds) {
        val url = Url(address)
        if (url.port == 1455 || url.port == 1457) {
            lastOwnedFixedCallback.value = TimeSource.Monotonic.markNow()
        }
        connected(url) { socket ->
            val target = path ?: (url.encodedPath + url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty())
            val bytes = body.encodeToByteArray()
            val output = socket.openWriteChannel(autoFlush = true)
            output.writeStringUtf8(buildString {
                append("$method $target HTTP/1.1\r\nHost: ${authority ?: "${url.host}:${url.port}"}\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                if (method == "POST") append("Content-Type: application/json\r\n")
                for ((key, value) in extraHeaders) append("$key: $value\r\n")
                append("Connection: close\r\nContent-Length: ${bytes.size}\r\n\r\n")
            })
            output.writeFully(bytes)
            val input = socket.openReadChannel()
            val status = requireNotNull(input.readLineStrict()).split(' ')[1].toInt()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = requireNotNull(input.readLineStrict(limit = 8192))
                if (line.isEmpty()) break
                check(headers.size < 64)
                val key = line.substringBefore(':').lowercase()
                check(key !in headers)
                headers[key] = line.substringAfter(':').trim()
            }
            val data = Buffer()
            if (headers["transfer-encoding"].equals("chunked", ignoreCase = true)) {
                while (true) {
                    val size = requireNotNull(input.readLineStrict()).substringBefore(';').toInt(16)
                    if (size == 0) {
                        check(input.readLineStrict() == "")
                        break
                    }
                    require(size >= 0 && data.size + size <= 1024 * 1024)
                    data.write(input.readByteArray(size))
                    check(input.readLineStrict() == "")
                }
            } else {
                input.readRemaining(1024 * 1024 + 1L).use { data.transferFrom(it) }
                check(data.size <= 1024 * 1024)
            }
            RoundTripHttpReply(status, headers, data.readByteArray().decodeToString())
        }
    }

    suspend fun spareRedirect(): String {
        val socket = aSocket(selector).tcp().bind("127.0.0.1", 0)
        return try {
            "http://127.0.0.1:${(socket.localAddress as InetSocketAddress).port}/callback"
        } finally {
            withContext(NonCancellable) {
                socket.close()
                withTimeout(5.seconds) { socket.socketContext.join() }
            }
        }
    }

    suspend fun requireOpenAiPortsFree() {
        // Our preceding real callback connections can leave these fixed ports in TIME_WAIT.
        // Wait between our own fixtures rather than enabling socket reuse or weakening conflicts.
        lastOwnedFixedCallback.value?.let { last ->
            val quiet = 65.seconds - last.elapsedNow()
            if (quiet.isPositive()) delay(quiet)
        }
        // No SO_REUSEPORT/reuseAddress and no process discovery/termination. Abort on either conflict.
        // This is a preflight, not a reservation: the production bind must still ACK acquisition.
        val guards = mutableListOf<ServerSocket>()
        try {
            for (port in listOf(1455, 1457)) {
                guards += try { aSocket(selector).tcp().bind("127.0.0.1", port) }
                catch (_: Exception) {
                    throw IllegalStateException("BLOCKED: OpenAI callback port $port is occupied; do not stop its owner.")
                }
            }
        } finally {
            withContext(NonCancellable) {
                for (guard in guards) {
                    guard.close()
                    withTimeout(5.seconds) { guard.socketContext.join() }
                }
            }
        }
    }

    suspend fun assertClosed(address: String) = withTimeout(5.seconds) {
        while (true) {
            val closed = try { connected(Url(address)) { false } }
            catch (_: IOException) { true }
            if (closed) break
            delay(20)
        }
    }

    suspend fun close() {
        selector.close()
        job.complete()
        withTimeout(5.seconds) { job.join() }
    }
}

/**
 * Fake external provider only. Backend OAuth, MCP metadata/registration/PKCE/token client,
 * persistence and Native callback server remain the production implementations.
 */
internal class RoundTripProvider {
    var origin: String = ""
        private set
    val visits = MutableStateFlow(0)
    val registrations = MutableStateFlow<List<JsonObject>>(emptyList())
    val tokens = MutableStateFlow<List<Map<String, String>>>(emptyList())
    val mcpAuthorizations = MutableStateFlow<List<Map<String, String>>>(emptyList())
    val tokenEntered = CompletableDeferred<Unit>()
    val releaseToken = CompletableDeferred<Unit>()
    private var startAttempted = false
    private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            post("/mcp") {
                call.response.header(
                    HttpHeaders.WWWAuthenticate,
                    "Bearer resource_metadata=\"$origin/resource-metadata\", scope=\"tools.read\"",
                )
                call.respondText("", status = HttpStatusCode.Unauthorized)
            }
            get("/resource-metadata") {
                call.respondText(
                    """{"resource":"$origin/mcp","authorization_servers":["$origin"],"scopes_supported":["tools.read"]}""",
                    ContentType.Application.Json,
                )
            }
            get("/.well-known/oauth-authorization-server") {
                call.respondText(
                    """{"issuer":"$origin","authorization_endpoint":"$origin/authorize","token_endpoint":"$origin/token","registration_endpoint":"$origin/register","token_endpoint_auth_methods_supported":["none"],"code_challenge_methods_supported":["S256"]}""",
                    ContentType.Application.Json,
                )
            }
            post("/register") {
                val request = Json.parseToJsonElement(call.receiveText()).jsonObject
                registrations.update { it + request }
                assertEquals("Kodex", request.getValue("client_name").jsonPrimitive.content)
                assertEquals("none", request.getValue("token_endpoint_auth_method").jsonPrimitive.content)
                call.respondText(
                    """{"client_id":"roundtrip-client","token_endpoint_auth_method":"none"}""",
                    ContentType.Application.Json, HttpStatusCode.Created,
                )
            }
            for ((path, code) in listOf("/authorize" to "mcp-code", "/openai-authorize" to "openai-code")) {
                get(path) {
                    val query = call.request.queryParameters.entries().associate { (key, values) -> key to values.single() }
                    assertEquals("code", query["response_type"])
                    assertEquals("S256", query["code_challenge_method"])
                    assertFalse(query["code_challenge"].isNullOrBlank())
                    val redirect = Url(query.getValue("redirect_uri"))
                    require(redirect.protocol.name == "http" && redirect.host in setOf("localhost", "127.0.0.1"))
                    if (path == "/authorize") {
                        assertEquals("roundtrip-client", query["client_id"])
                        assertEquals("$origin/mcp", query["resource"])
                        assertEquals("tools.read", query["scope"])
                        mcpAuthorizations.update { it + query }
                    }
                    call.respondRedirect(URLBuilder(redirect).apply {
                        parameters.append("code", code)
                        parameters.append("state", query.getValue("state"))
                    }.buildString())
                }
            }
            post("/token") {
                val parameters = call.receiveParameters().entries().associate { (key, values) -> key to values.single() }
                assertEquals("authorization_code", parameters["grant_type"])
                assertEquals("mcp-code", parameters["code"])
                assertEquals("roundtrip-client", parameters["client_id"])
                assertEquals("$origin/mcp", parameters["resource"])
                val authorization = mcpAuthorizations.value.single()
                assertEquals(authorization.getValue("redirect_uri"), parameters["redirect_uri"])
                assertEquals(authorization.getValue("code_challenge"), roundTripPkce(parameters.getValue("code_verifier")))
                tokens.update { it + parameters }
                tokenEntered.complete(Unit)
                releaseToken.await()
                call.respondText(
                    """{"access_token":"roundtrip-mcp-access","refresh_token":"roundtrip-mcp-refresh","token_type":"Bearer","expires_in":3600}""",
                    ContentType.Application.Json,
                )
            }
        }
    }

    suspend fun start() {
        startAttempted = true
        server.startSuspend()
        origin = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
    }

    suspend fun visitAuthorization(wire: RoundTripWire, registeredUrl: String): Boolean {
        val url = Url(registeredUrl)
        require("$origin/${url.encodedPath.removePrefix("/")}" == registeredUrl.substringBefore('?'))
        require(url.encodedPath in setOf("/authorize", "/openai-authorize"))
        visits.update { it + 1 }
        val authorization = wire.request(registeredUrl)
        assertEquals(302, authorization.status)
        val callback = requireNotNull(authorization.headers["location"])
        assertEquals(url.parameters["redirect_uri"], callback.substringBefore('?'))
        assertEquals(200, wire.request(callback).status)
        return true
    }

    suspend fun close() {
        releaseToken.complete(Unit)
        if (startAttempted) server.stopSuspend()
    }
}

/** Independent test-side SHA-256/S256 check, including the RFC 7636 known-answer vector. */
internal fun roundTripPkce(verifier: String): String {
    fun hash(value: String): String {
        val bytes = value.encodeToByteArray()
        val padded = ByteArray(((bytes.size + 9 + 63) / 64) * 64)
        bytes.copyInto(padded)
        padded[bytes.size] = 0x80.toByte()
        val bits = bytes.size.toLong() * 8
        for (i in 0..7) padded[padded.lastIndex - i] = (bits ushr (i * 8)).toByte()
        val state = intArrayOf(0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19)
        val words = IntArray(64)
        for (offset in padded.indices step 64) {
            for (i in 0..15) {
                var word = 0
                for (j in 0..3) word = (word shl 8) or (padded[offset + i * 4 + j].toInt() and 255)
                words[i] = word
            }
            for (i in 16..63) {
                val x = words[i - 15]
                val y = words[i - 2]
                words[i] = words[i - 16] + (x.rotateRight(7) xor x.rotateRight(18) xor (x ushr 3)) +
                    words[i - 7] + (y.rotateRight(17) xor y.rotateRight(19) xor (y ushr 10))
            }
            val v = state.copyOf()
            for (i in 0..63) {
                val t1 = v[7] + (v[4].rotateRight(6) xor v[4].rotateRight(11) xor v[4].rotateRight(25)) +
                    ((v[4] and v[5]) xor (v[4].inv() and v[6])) + roundTripShaConstants[i] + words[i]
                val t2 = (v[0].rotateRight(2) xor v[0].rotateRight(13) xor v[0].rotateRight(22)) +
                    ((v[0] and v[1]) xor (v[0] and v[2]) xor (v[1] and v[2]))
                for (j in 7 downTo 1) v[j] = v[j - 1]
                v[4] += t1
                v[0] = t1 + t2
            }
            for (i in state.indices) state[i] += v[i]
        }
        val result = ByteArray(32)
        for (i in state.indices) for (j in 0..3) result[i * 4 + j] = (state[i] ushr (24 - j * 8)).toByte()
        return Base64.UrlSafe.encode(result).trimEnd('=')
    }
    check(hash("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    return hash(verifier)
}

private val roundTripShaConstants = longArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
).map { it.toInt() }.toIntArray()
