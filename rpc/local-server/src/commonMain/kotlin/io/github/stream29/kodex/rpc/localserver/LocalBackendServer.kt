package io.github.stream29.kodex.rpc.localserver

import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.rpc.websocket.WebSocketRpcTransport
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.options
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.request.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.rpc.RpcServer
import kotlinx.rpc.krpc.rpcServerConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.krpc.server.KrpcServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json

public actual suspend fun <R> withLocalBackendServer(
    password: String,
    instanceId: String,
    control: BackendControl,
    registerServices: RpcServer.() -> Unit,
    browserAccess: LocalBrowserAccess?,
    block: suspend CoroutineScope.(Int) -> R,
): R = coroutineScope {
    val grants = BrowserGrants()
    val expiry = launch {
        while (true) { delay(1_000); grants.expire() }
    }
    val engine = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        install(WebSockets) { maxFrameSize = 16L * 1024 * 1024 }
        val pendingBrowserHellos = Semaphore(16)
        suspend fun ApplicationCall.browserOriginAllowed(): Boolean =
            request.headers.getAll(HttpHeaders.Origin)?.singleOrNull()?.let {
                it in browserAccess?.allowedOrigins.orEmpty() || grants.originAllowed(it)
            } == true
        fun ApplicationCall.browserHostAllowed(): Boolean =
            request.headers.getAll(HttpHeaders.Host)?.singleOrNull() == "127.0.0.1:${request.local.localPort}"
        suspend fun DefaultWebSocketServerSession.serveBusiness() {
            val transport = WebSocketRpcTransport(this, coroutineContext)
            val server = object : KrpcServer(rpcServerConfig { serialization { json() } }, transport) {}
            try {
                server.registerServices()
                transport.awaitClosed()
            } finally { withContext(NonCancellable) { transport.shutdown() } }
        }
        suspend fun ApplicationCall.authorized(): Boolean {
            if (request.headers.contains(HttpHeaders.Origin) || !browserHostAllowed() ||
                request.headers.getAll("Authorization")?.singleOrNull() != "Bearer $password" ||
                request.headers.getAll("X-Kodex-Instance")?.singleOrNull() != instanceId) {
                respondText("""{"error":"unauthorized"}""", status = HttpStatusCode.Unauthorized)
                return false
            }
            return true
        }
        routing {
            if (browserAccess != null) {
                for (action in listOf("grant", "revoke")) post("/control/browser/$action") {
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    if (!call.authorized()) return@post
                    if (!control.ready()) {
                        call.respondText("Browser admission unavailable.", status = HttpStatusCode.Conflict)
                        return@post
                    }
                    val input = call.browserGrantInput()
                    if (input == null || input.protocol != LocalBrowserGrantProtocol ||
                        input.password == password
                    ) {
                        call.respondText("Invalid browser admission.", status = HttpStatusCode.BadRequest)
                        return@post
                    }
                    var directory: String? = null
                    if (action == "revoke") {
                        grants.revoke(input.password)
                    } else {
                        try {
                            directory = browserAccess.resolveDirectory(input.workingDirectory)
                            if (!control.ready()) error("Admission closed.")
                            grants.put(input, requireNotNull(directory))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            call.respondText("Browser admission rejected.", status = HttpStatusCode.BadRequest)
                            return@post
                        }
                    }
                    call.respondText(kotlinx.serialization.json.buildJsonObject {
                        put("accepted", kotlinx.serialization.json.JsonPrimitive(true))
                        directory?.let { put("working_directory", kotlinx.serialization.json.JsonPrimitive(it)) }
                    }.toString())
                }
            }
            get("/control/status") {
                if (call.authorized()) call.respondText(control.status().toString())
            }
            post("/control/stop") {
                if (!call.authorized()) return@post
                var accepted = false
                try {
                    accepted = control.acceptStop()
                    if (accepted) call.respondText("""{"accepted":true}""")
                    else call.respondText("""{"accepted":true,"already_stopping":true}""")
                } finally { if (accepted) control.stopReplyFinished() }
            }
            post("/control/reload") {
                if (!call.authorized()) return@post
                if (!control.ready()) {
                    call.respondText("""{"error":"not_ready"}""", status = HttpStatusCode.Conflict)
                    return@post
                }
                try {
                    control.reload()
                    call.respondText("""{"accepted":true}""")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    call.respondText("""{"error":"configuration_rejected"}""", status = HttpStatusCode.BadRequest)
                }
            }
            route("/heartbeat") {
                options {
                    val headers = call.request.headers[HttpHeaders.AccessControlRequestHeaders]
                        ?.split(',')?.map { it.trim().lowercase() }.orEmpty()
                    if (!call.browserHostAllowed() || !call.browserOriginAllowed() ||
                        call.request.headers[HttpHeaders.AccessControlRequestMethod] != "POST" ||
                        headers.any { it !in setOf("authorization", "x-kodex-instance",
                            "x-kodex-protocol", "x-kodex-browser-protocol") }
                    ) {
                        call.respondText("Browser preflight rejected.", status = HttpStatusCode.Forbidden)
                        return@options
                    }
                    call.browserCors()
                    call.response.header(HttpHeaders.AccessControlAllowMethods, "POST")
                    call.response.header(HttpHeaders.AccessControlAllowHeaders,
                        "authorization,x-kodex-instance,x-kodex-protocol,x-kodex-browser-protocol")
                    call.respondText("")
                }
                post {
                    if (call.request.headers.contains(HttpHeaders.Origin)) {
                        if (!call.browserOriginAllowed() || !call.browserHostAllowed() ||
                            call.request.headers.getAll("X-Kodex-Browser-Protocol")?.singleOrNull() != LocalBrowserProtocol.toString()
                        ) {
                            call.respondText("""{"error":"browser_access_rejected"}""", status = HttpStatusCode.Forbidden)
                            return@post
                        }
                        call.browserCors()
                        val origin = call.request.headers[HttpHeaders.Origin]!!
                        val credential = call.request.headers.getAll(HttpHeaders.Authorization)
                            ?.singleOrNull()?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
                        if (call.request.headers.getAll("X-Kodex-Instance")?.singleOrNull() != instanceId ||
                            call.request.headers.getAll("X-Kodex-Protocol")?.singleOrNull() != BackendBusinessProtocol
                        ) {
                            call.respondText("""{"error":"instance_or_protocol_mismatch"}""", status = HttpStatusCode.Conflict)
                            return@post
                        }
                        val manual = origin in browserAccess?.allowedOrigins.orEmpty() && credential == password
                        val scoped = credential != null && credential != password && grants.renew(credential, origin)
                        if ((!manual && !scoped) || call.request.headers["X-Kodex-Instance"] != instanceId) {
                            call.respondText("""{"error":"unauthorized"}""", status = HttpStatusCode.Unauthorized)
                            return@post
                        }
                    } else {
                        if (!call.authorized()) return@post
                    }
                    if (call.request.headers.getAll("X-Kodex-Protocol")?.singleOrNull() != BackendBusinessProtocol) {
                        call.respondText("""{"error":"protocol_mismatch"}""", status = HttpStatusCode.Conflict)
                    } else if (control.heartbeat()) call.respondText(control.status().toString())
                    else call.respondText("""{"error":"not_ready"}""", status = HttpStatusCode.Conflict)
                }
            }
            webSocket("/rpc") {
                if (!call.authorized()) return@webSocket
                if (call.request.headers.getAll("X-Kodex-Protocol")?.singleOrNull() != BackendBusinessProtocol || !control.ready()) {
                    close()
                    return@webSocket
                }
                serveBusiness()
            }
            if (browserAccess != null) webSocket("/browser/rpc") {
                if (!call.browserOriginAllowed() || !call.browserHostAllowed()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Browser origin or host rejected."))
                    return@webSocket
                }
                if (!pendingBrowserHellos.tryAcquire()) {
                    close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Browser admission busy."))
                    return@webSocket
                }
                maxFrameSize = LocalBrowserHelloMaxBytes
                val peer = coroutineContext.job
                var grantPassword: String? = null
                val directory = try {
                    withTimeoutOrNull(5_000) {
                        val first = incoming.receiveCatching().getOrNull() as? Frame.Text ?: return@withTimeoutOrNull null
                        if (first.data.size > LocalBrowserHelloMaxBytes) return@withTimeoutOrNull null
                        val hello = try { Json.decodeFromString(LocalBrowserHello.serializer(), first.readText()) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { return@withTimeoutOrNull null }
                        if (hello.protocol != LocalBrowserProtocol || hello.instanceId != instanceId ||
                            hello.businessProtocol != BackendBusinessProtocol ||
                            !control.ready()
                        ) return@withTimeoutOrNull null
                        val origin = call.request.headers[HttpHeaders.Origin]!!
                        if (hello.password != password) {
                            val directory = grants.attach(hello.password, origin, hello.workingDirectory, peer)
                            if (directory != null) grantPassword = hello.password
                            return@withTimeoutOrNull directory
                        }
                        if (origin !in browserAccess.allowedOrigins) return@withTimeoutOrNull null
                        try { browserAccess.resolveDirectory(hello.workingDirectory) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    }
                } finally { pendingBrowserHellos.release() }
                if (directory == null || !control.ready()) {
                    withContext(NonCancellable) { grantPassword?.let { grants.detach(it, peer) } }
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Browser hello rejected or backend not ready."))
                    return@webSocket
                }
                maxFrameSize = 16L * 1024 * 1024
                try {
                    send(Frame.Text(Json.encodeToString(LocalBrowserWelcome.serializer(),
                        LocalBrowserWelcome(LocalBrowserProtocol, instanceId, BackendBusinessProtocol, directory))))
                    serveBusiness()
                } finally {
                    withContext(NonCancellable) { grantPassword?.let { grants.detach(it, peer) } }
                }
            }
        }
    }
    var primary: Throwable? = null
    try {
        engine.startSuspend()
        block(engine.engine.resolvedConnectors().single().port)
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        withContext(NonCancellable) { expiry.cancelAndJoin() }
        try { withContext(NonCancellable) { engine.stopSuspend() } }
        catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
    }
}

private fun ApplicationCall.browserCors() {
    response.header(HttpHeaders.AccessControlAllowOrigin, request.headers[HttpHeaders.Origin]!!)
    response.header(HttpHeaders.Vary, "Origin")
    response.header(HttpHeaders.CacheControl, "no-store")
}

private suspend fun ApplicationCall.browserGrantInput(): LocalBrowserGrant? {
    if (!request.contentType().match(io.ktor.http.ContentType.Application.Json)) return null
    val bytes = receiveChannel().readRemaining(16_385).use { it.readByteArray() }
    if (bytes.size > 16_384) return null
    return try { Json.decodeFromString(LocalBrowserGrant.serializer(), bytes.decodeToString(throwOnInvalidSequence = true)) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
