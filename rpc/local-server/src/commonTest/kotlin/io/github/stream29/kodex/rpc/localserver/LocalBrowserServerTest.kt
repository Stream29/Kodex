package io.github.stream29.kodex.rpc.localserver

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.rpc.control.backendRegisterBrowser
import io.github.stream29.kodex.rpc.control.backendRevokeBrowser
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

private const val Origin = "http://127.0.0.1:18795"
private const val Instance = "local-browser-server-test"
private val Secret = "private-local-test-password-".repeat(3)

val localBrowserServerTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("Native grant allows only scoped business access and revocation closes its transport") {
        withServer(origins = emptySet()) { harness ->
            val endpoint = BackendEndpoint(Instance, 123, harness.port, Secret, "test")
            val credential = "a".repeat(64)
            val grant = LocalBrowserGrant(LocalBrowserGrantProtocol, Origin, credential, "/project")
            assertEquals("/canonical/project", backendRegisterBrowser(endpoint, grant))
            assertEquals(0, harness.control.renewals.value, "Registration is not a business heartbeat.")
            val management = harness.http.get(harness.httpUrl("/control/status")) {
                header(HttpHeaders.Authorization, "Bearer $credential")
                header("X-Kodex-Instance", Instance)
            }
            assertEquals(HttpStatusCode.Unauthorized, management.status)
            for ((label, hello) in listOf(
                "directory" to encodeHello(password = credential, directory = "/project"),
                "instance" to encodeHello(password = credential, instance = "replacement", directory = "/canonical/project"),
                "protocol" to encodeHello(password = credential, business = "other", directory = "/canonical/project"),
                "master" to encodeHello(directory = "/canonical/project"),
            )) {
                val rejected = harness.browser()
                rejected.send(hello)
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code,
                    withTimeout(5.seconds) { rejected.closeReason.await() }?.code, label)
            }
            assertEquals(0, harness.registered.value)
            val socket = harness.browser()
            try {
                socket.send(encodeHello(password = credential, directory = "/canonical/project"))
                val welcome = assertIs<Frame.Text>(withTimeout(5.seconds) { socket.incoming.receive() })
                assertEquals("/canonical/project",
                    Json.decodeFromString(LocalBrowserWelcome.serializer(), welcome.readText()).workingDirectory)
                withTimeout(5.seconds) { harness.registered.first { it == 1 } }
                backendRevokeBrowser(endpoint, grant)
                withTimeout(5.seconds) { socket.closeReason.await() }
                assertEquals(0, harness.control.renewals.value)
            } finally { socket.close() }
            val stillReady = harness.http.get(harness.httpUrl("/control/status")) { nativeCredentials() }
            assertEquals(HttpStatusCode.OK, stillReady.status)
            assertFalse(stillReady.bodyAsText().contains(credential))
            val resources = harness.http.get(harness.httpUrl("/web/${"b".repeat(64)}/"))
            assertEquals(HttpStatusCode.NotFound, resources.status)
        }
    }
    test("Native grant registration rejects browser Origin before admission or directory work") {
        withServer(origins = emptySet()) { harness ->
            val response = harness.http.post(harness.httpUrl("/control/browser/grant")) {
                nativeCredentials()
                header(HttpHeaders.Origin, Origin)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(LocalBrowserGrant.serializer(),
                    LocalBrowserGrant(LocalBrowserGrantProtocol, Origin, "c".repeat(64), "/project")))
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals(0, harness.directoryChecks.value + harness.registered.value + harness.control.renewals.value)
        }
    }
    test("default server retains Native headers and does not enable browser entry") {
        withServer(enabled = false) { harness ->
            val response = harness.http.post(harness.httpUrl("/heartbeat")) { nativeCredentials() }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(1, harness.control.renewals.value)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
            val missing = harness.http.get(harness.httpUrl("/browser/rpc"))
            assertEquals(HttpStatusCode.NotFound, missing.status)
            val native = harness.http.webSocketSession(harness.wsUrl("/rpc")) { nativeCredentials() }
            try {
                withTimeout(5.seconds) { harness.registered.first { it == 1 } }
            } finally { native.close() }
        }
    }
    test("authenticated browser receives validated backend directory before original RPC registration") {
        withServer { harness ->
            val socket = harness.browser()
            try {
                socket.send(Frame.Text(encodeHello(directory = "/project")))
                val frame = assertIs<Frame.Text>(withTimeout(5.seconds) { socket.incoming.receive() })
                val welcome = Json.decodeFromString(LocalBrowserWelcome.serializer(), frame.readText())
                assertEquals(Instance, welcome.instanceId)
                assertEquals(BackendBusinessProtocol, welcome.businessProtocol)
                assertEquals("/canonical/project", welcome.workingDirectory)
                withTimeout(5.seconds) { harness.registered.first { it == 1 } }
                assertEquals(1, harness.directoryChecks.value)
                assertEquals(0, harness.control.renewals.value, "An open socket is not a renewal.")
            } finally { socket.close() }
        }
    }
    for ((label, frame) in listOf(
        "password" to Frame.Text(encodeHello(password = Secret + "-wrong")),
        "instance" to Frame.Text(encodeHello(instance = "replacement-instance")),
        "business version" to Frame.Text(encodeHello(business = "incompatible")),
        "browser version" to Frame.Text(encodeHello(protocol = LocalBrowserProtocol + 1)),
        "malformed" to Frame.Text("not JSON"),
        "missing fields" to Frame.Text("""{"password":"$Secret"}"""),
        "binary" to Frame.Binary(true, byteArrayOf(1, 2, 3)),
    )) test("$label rejection precedes directory work renewal and RPC allocation") {
        withServer { harness ->
            val socket = harness.browser()
            socket.send(frame)
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code,
                withTimeout(5.seconds) { socket.closeReason.await() }?.code)
            assertEquals(0, harness.registered.value)
            assertEquals(0, harness.directoryChecks.value)
            assertEquals(0, harness.control.renewals.value)
        }
    }
    test("wrong Origin is rejected before receiving credentials") {
        withServer { harness ->
            val socket = harness.browser("http://evil.test:18795")
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code,
                withTimeout(5.seconds) { socket.closeReason.await() }?.code)
            assertEquals(0, harness.registered.value + harness.directoryChecks.value + harness.control.renewals.value)
        }
    }
    test("directory rejection is local and does not allocate RPC or keep the daemon alive") {
        withServer { harness ->
            val socket = harness.browser()
            socket.send(Frame.Text(encodeHello(directory = "/denied")))
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code,
                withTimeout(5.seconds) { socket.closeReason.await() }?.code)
            assertEquals(1, harness.directoryChecks.value)
            assertEquals(0, harness.registered.value + harness.control.renewals.value)
        }
    }
    test("unauthenticated hello times out without renewing or allocating RPC") {
        withServer { harness ->
            val socket = harness.browser()
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code,
                withTimeout(8.seconds) { socket.closeReason.await() }?.code)
            assertEquals(0, harness.registered.value + harness.directoryChecks.value + harness.control.renewals.value)
        }
    }
    test("pending unauthenticated browser sockets are bounded independently of RPC") {
        withServer { harness ->
            val sockets = mutableListOf<DefaultClientWebSocketSession>()
            try {
                repeat(17) { sockets += harness.browser() }
                val firstClose = coroutineScope {
                    val waiters = sockets.map { async { it.closeReason.await() } }
                    try {
                        withTimeout(3.seconds) { select { waiters.forEach { waiter -> waiter.onAwait { it } } } }
                    } finally { waiters.forEach { it.cancel() } }
                }
                assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, firstClose?.code)
                assertEquals(0, harness.registered.value + harness.directoryChecks.value + harness.control.renewals.value)
            } finally { sockets.forEach { it.close() } }
        }
    }
    test("heartbeat CORS is exact and preflight never renews or grants management CORS") {
        withServer { harness ->
            val preflight = harness.http.options(harness.httpUrl("/heartbeat")) {
                header(HttpHeaders.Origin, Origin)
                header(HttpHeaders.AccessControlRequestMethod, "POST")
                header(HttpHeaders.AccessControlRequestHeaders,
                    "authorization,x-kodex-instance,x-kodex-protocol,x-kodex-browser-protocol")
            }
            assertEquals(HttpStatusCode.OK, preflight.status)
            assertEquals(Origin, preflight.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals(0, harness.control.renewals.value)
            val native = harness.http.post(harness.httpUrl("/heartbeat")) { nativeCredentials() }
            assertEquals(HttpStatusCode.OK, native.status)
            val browser = harness.http.post(harness.httpUrl("/heartbeat")) {
                nativeCredentials()
                header(HttpHeaders.Origin, Origin)
                header("X-Kodex-Browser-Protocol", LocalBrowserProtocol)
            }
            assertEquals(HttpStatusCode.OK, browser.status)
            assertEquals(Origin, browser.headers[HttpHeaders.AccessControlAllowOrigin])
            assertFalse(browser.bodyAsText().contains(Secret))
            assertEquals(2, harness.control.renewals.value)
            val rejected = harness.http.post(harness.httpUrl("/heartbeat")) {
                nativeCredentials()
                header(HttpHeaders.Origin, "http://127.0.0.1:18794")
                header("X-Kodex-Browser-Protocol", LocalBrowserProtocol)
            }
            assertEquals(HttpStatusCode.Forbidden, rejected.status)
            assertNull(rejected.headers[HttpHeaders.AccessControlAllowOrigin])
            val oldVersion = harness.http.post(harness.httpUrl("/heartbeat")) {
                nativeCredentials()
                header(HttpHeaders.Origin, Origin)
                header("X-Kodex-Browser-Protocol", LocalBrowserProtocol + 1)
            }
            assertEquals(HttpStatusCode.Forbidden, oldVersion.status)
            assertEquals(2, harness.control.renewals.value)
            val management = harness.http.options(harness.httpUrl("/control/status")) {
                header(HttpHeaders.Origin, Origin)
                header(HttpHeaders.AccessControlRequestMethod, "GET")
            }
            assertNull(management.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }
}

private fun HttpRequestBuilder.nativeCredentials() {
    header(HttpHeaders.Authorization, "Bearer $Secret")
    header("X-Kodex-Instance", Instance)
    header("X-Kodex-Protocol", BackendBusinessProtocol)
}

private fun encodeHello(
    protocol: Int = LocalBrowserProtocol, instance: String = Instance, password: String = Secret,
    business: String = BackendBusinessProtocol, directory: String? = null,
): String = Json.encodeToString(LocalBrowserHello.serializer(),
    LocalBrowserHello(protocol, instance, password, business, directory))

private class Control : BackendControl {
    val renewals = MutableStateFlow(0)
    override fun ready(): Boolean = true
    override fun status(): JsonObject = buildJsonObject {
        put("phase", "ready")
        put("instance", Instance)
        put("business_protocol", BackendBusinessProtocol)
        put("idle_ttl_seconds", 300)
        put("browser_grant_protocol", LocalBrowserGrantProtocol)
    }
    override suspend fun heartbeat(): Boolean { renewals.update { it + 1 }; return true }
    override suspend fun acceptStop(): Boolean = false
    override fun stopReplyFinished() = Unit
    override suspend fun reload() = Unit
}

private class Harness(
    val http: HttpClient, val port: Int, val control: Control,
    val registered: MutableStateFlow<Int>, val directoryChecks: MutableStateFlow<Int>,
) {
    fun httpUrl(path: String): String = "http://127.0.0.1:$port$path"
    fun wsUrl(path: String): String = "ws://127.0.0.1:$port$path"
    suspend fun browser(origin: String = Origin): DefaultClientWebSocketSession =
        http.webSocketSession(wsUrl("/browser/rpc")) { header(HttpHeaders.Origin, origin) }
}

private suspend fun withServer(enabled: Boolean = true, origins: Set<String> = setOf(Origin), block: suspend (Harness) -> Unit) {
    val control = Control()
    val registered = MutableStateFlow(0)
    val directoryChecks = MutableStateFlow(0)
    val access = if (!enabled) null else LocalBrowserAccess(origins) { requested ->
        directoryChecks.update { it + 1 }
        check(requested != "/denied") { "Directory unavailable." }
        if (requested == "/project") "/canonical/project" else "/backend/home"
    }
    val http = HttpClient { install(WebSockets) }
    try {
        withLocalBackendServer(Secret, Instance, control, { registered.update { it + 1 } }, access) { port ->
            withTimeout(15.seconds) { block(Harness(http, port, control, registered, directoryChecks)) }
        }
    } finally { http.close() }
}
