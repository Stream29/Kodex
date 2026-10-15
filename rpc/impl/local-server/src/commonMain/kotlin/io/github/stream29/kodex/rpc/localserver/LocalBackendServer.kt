package io.github.stream29.kodex.rpc.localserver

import io.github.stream29.kodex.backend.BackendBusinessProtocol
import io.github.stream29.kodex.rpc.websocket.WebSocketRpcTransport
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.rpc.RpcServer
import kotlinx.rpc.krpc.rpcServerConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.krpc.server.KrpcServer
import kotlinx.serialization.json.JsonObject

/** One port, one external service graph, independent resources for each authenticated connection. */
public suspend fun <R> withLocalBackendServer(
    password: String,
    instanceId: String,
    control: BackendControl,
    registerServices: RpcServer.() -> Unit,
    block: suspend CoroutineScope.(Int) -> R,
): R = coroutineScope {
    val engine = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
        install(WebSockets) { maxFrameSize = 16L * 1024 * 1024 }
        suspend fun ApplicationCall.authorized(): Boolean {
            if (request.headers["Authorization"] != "Bearer $password" ||
                request.headers["X-Kodex-Instance"] != instanceId) {
                respondText("""{"error":"unauthorized"}""", status = HttpStatusCode.Unauthorized)
                return false
            }
            return true
        }
        routing {
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
            post("/heartbeat") {
                if (!call.authorized()) return@post
                if (call.request.headers["X-Kodex-Protocol"] != BackendBusinessProtocol) {
                    call.respondText("""{"error":"protocol_mismatch"}""", status = HttpStatusCode.Conflict)
                } else if (control.heartbeat()) call.respondText(control.status().toString())
                else call.respondText("""{"error":"not_ready"}""", status = HttpStatusCode.Conflict)
            }
            webSocket("/rpc") {
                if (!call.authorized()) return@webSocket
                if (call.request.headers["X-Kodex-Protocol"] != BackendBusinessProtocol || !control.ready()) {
                    close()
                    return@webSocket
                }
                val transport = WebSocketRpcTransport(this, coroutineContext)
                val server = object : KrpcServer(rpcServerConfig { serialization { json() } }, transport) {}
                try {
                    server.registerServices()
                    transport.awaitClosed()
                } finally {
                    withContext(NonCancellable) { transport.shutdown() }
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
        try { withContext(NonCancellable) { engine.stopSuspend() } }
        catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
    }
}
