package io.github.stream29.kodex.rpc.browser

import io.github.stream29.kodex.backend.*
import io.github.stream29.kodex.rpc.client.RpcWireSerializers
import io.github.stream29.kodex.rpc.local.FrontendRpcConnection
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import io.github.stream29.kodex.rpc.websocket.WebSocketRpcTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.engine.js.JsError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.rpc.krpc.client.InitializedKrpcClient
import kotlinx.rpc.krpc.rpcClientConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.serialization.json.*

private class BrowserConnectionRejected : IllegalStateException("Local browser connection was rejected.")
private class BrowserRecoveryFailed(val failure: Throwable) : IllegalStateException()

// Ktor Wasm wraps a rejected fetch in Error(cause = JsError), outside Exception.
internal fun isBrowserNetworkFailure(failure: Throwable): Boolean =
    failure is Exception || failure is JsError || failure.cause is JsError

internal fun validateBrowserEndpoint(endpoint: LocalBrowserEndpoint) {
    require(endpoint.port in 1..65535 && endpoint.instanceId.length in 1..256 && endpoint.password.length in 32..1024) {
        "Invalid private backend connection record."
    }
    require(endpoint.businessProtocol == BackendBusinessProtocol) { "Backend business protocol is incompatible." }
}

internal fun heartbeatInterval(reply: JsonObject, endpoint: LocalBrowserEndpoint): Long {
    return heartbeatInterval(reply, endpoint.instanceId)
}

private fun heartbeatInterval(reply: JsonObject, instanceId: String): Long {
    if (reply["instance"]?.jsonPrimitive?.content != instanceId ||
        reply["business_protocol"]?.jsonPrimitive?.content != BackendBusinessProtocol ||
        reply["phase"]?.jsonPrimitive?.content != "ready"
    ) throw BrowserConnectionRejected()
    val ttl = requireNotNull(reply["idle_ttl_seconds"]?.jsonPrimitive?.longOrNull)
    require(ttl == -1L || ttl > 0L) { "Invalid backend idle timeout." }
    return if (ttl == -1L || ttl >= 60L) 20_000L else maxOf(100L, ttl * 1000L / 3)
}

public actual suspend fun <R> withLocalBrowserClient(
    endpoint: LocalBrowserEndpoint,
    workingDirectory: String?,
    block: suspend CoroutineScope.(canonicalDirectory: String, FrontendRpcConnection) -> R,
): R {
    validateBrowserEndpoint(endpoint)
    return withBrowserConnection(
        BrowserTarget(endpoint.instanceId, "http://127.0.0.1:${endpoint.port}",
            "/heartbeat", "/browser/rpc", endpoint.password, workingDirectory),
        block,
    )
}

internal class BrowserTarget(
    val instanceId: String,
    val origin: String,
    val heartbeatPath: String,
    val rpcPath: String,
    val password: String,
    val workingDirectory: String? = null,
)

internal suspend fun <R> withBrowserConnection(
    target: BrowserTarget,
    block: suspend CoroutineScope.(canonicalDirectory: String, FrontendRpcConnection) -> R,
): R = coroutineScope {
    val owner = this
    val http = HttpClient(Js) {
        install(WebSockets)
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
    }
    val connection = BrowserConnection()
    val initial = CompletableDeferred<String>()
    var canonicalDirectory: String? = null
    var renewals: Job? = null
    var currentTransport: WebSocketRpcTransport? = null

    suspend fun renew(): Long {
        val response = http.post(target.origin + target.heartbeatPath) {
            header("Authorization", "Bearer ${target.password}")
            header("X-Kodex-Instance", target.instanceId)
            header("X-Kodex-Protocol", BackendBusinessProtocol)
            header("X-Kodex-Browser-Protocol", LocalBrowserProtocol)
        }
        if (response.status.value in 400..499) throw BrowserConnectionRejected()
        check(response.status.value in 200..299) { "Backend heartbeat unavailable." }
        return heartbeatInterval(Json.parseToJsonElement(response.bodyAsText()).jsonObject, target.instanceId)
    }

    val loop = launch connectionLoop@ {
        while (true) {
            if (connection.state.value == BackendConnectionState.Failed) return@connectionLoop
            var transport: WebSocketRpcTransport? = null
            var peer: BrowserPeer? = null
            try {
                // Reject stale credentials/instances before opening the business transport.
                renew()
                val socket = withTimeout(10_000) {
                    http.webSocketSession(target.origin.replaceFirst("http://", "ws://") + target.rpcPath)
                }
                try {
                    val welcome = withTimeout(5_000) {
                        val hello = Json.encodeToString(LocalBrowserHello.serializer(), LocalBrowserHello(
                            LocalBrowserProtocol, target.instanceId, target.password, BackendBusinessProtocol,
                            canonicalDirectory ?: target.workingDirectory,
                        ))
                        socket.send(hello)
                        val frame = socket.incoming.receiveCatching().getOrNull() as? Frame.Text ?: throw BrowserConnectionRejected()
                        if (frame.data.size > LocalBrowserHelloMaxBytes) throw BrowserConnectionRejected()
                        try { Json.decodeFromString(LocalBrowserWelcome.serializer(), frame.readText()) }
                        catch (_: Exception) { throw BrowserConnectionRejected() }
                    }
                    if (welcome.protocol != LocalBrowserProtocol || welcome.instanceId != target.instanceId ||
                        welcome.businessProtocol != BackendBusinessProtocol || welcome.workingDirectory.isBlank() ||
                        (canonicalDirectory != null && canonicalDirectory != welcome.workingDirectory)
                    ) throw BrowserConnectionRejected()
                    val opened = WebSocketRpcTransport(socket, currentCoroutineContext()).also {
                        transport = it
                        currentTransport = it
                    }
                    val client = object : InitializedKrpcClient(
                        rpcClientConfig { serialization { json { serializersModule = RpcWireSerializers } } }, opened,
                    ) {}
                    val replacement = BrowserPeer(client) { opened.alive }.also { peer = it }
                    // A recovery business failure on a live replacement is never a network retry.
                    try { connection.install(replacement) }
                    catch (failure: Throwable) {
                        currentCoroutineContext().ensureActive()
                        if (connection.state.value == BackendConnectionState.Failed) return@connectionLoop
                        if (opened.alive) throw BrowserRecoveryFailed(failure)
                    }
                    if (opened.alive) {
                        if (canonicalDirectory == null) {
                            canonicalDirectory = welcome.workingDirectory
                            renewals = owner.launch renewalLoop@ {
                                while (true) {
                                    val interval = try { renew() }
                                    catch (rejected: BrowserConnectionRejected) {
                                        connection.reject()
                                        currentTransport?.shutdown()
                                        return@renewalLoop
                                    } catch (failure: Throwable) {
                                        currentCoroutineContext().ensureActive()
                                        if (!isBrowserNetworkFailure(failure)) throw failure
                                        500L
                                    }
                                    delay(interval)
                                }
                            }
                        }
                        initial.complete(welcome.workingDirectory)
                        opened.awaitClosed()
                    }
                } finally { withContext(NonCancellable) { socket.close() } }
            } catch (recovery: BrowserRecoveryFailed) {
                throw recovery.failure
            } catch (rejected: BrowserConnectionRejected) {
                connection.reject()
                initial.completeExceptionally(rejected)
                return@connectionLoop
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (!isBrowserNetworkFailure(failure)) throw failure
                // Recovery failed while alive: surface it rather than silently replacing business state.
                if (transport?.alive == true) throw failure
                if (!initial.isCompleted) {
                    initial.completeExceptionally(IllegalStateException("Unable to connect to the selected local backend."))
                    return@connectionLoop
                }
            } finally {
                peer?.let(connection::detach)
                if (currentTransport === transport) currentTransport = null
                withContext(NonCancellable) { transport?.shutdown() }
            }
            if (connection.state.value == BackendConnectionState.Failed) return@connectionLoop
            delay(500)
        }
    }
    loop.invokeOnCompletion { renewals?.cancel() }
    try { block(initial.await(), connection) }
    finally {
        withContext(NonCancellable) {
            loop.cancelAndJoin()
            renewals?.cancelAndJoin()
            http.close()
        }
    }
}
