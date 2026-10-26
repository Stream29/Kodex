@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package io.github.stream29.kodex.rpc.local

import io.github.stream29.kodex.backend.BackendBusinessProtocol
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.rpc.websocket.WebSocketRpcTransport
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.io.files.Path
import kotlinx.rpc.RpcCall
import kotlinx.rpc.RpcClient
import kotlinx.rpc.krpc.client.InitializedKrpcClient
import kotlinx.rpc.krpc.rpcClientConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private class BusinessConnectionRejected : IllegalStateException("Backend rejected this business connection.")

/** Frontend renewal through the existing business endpoint, not a separate health-check protocol. */
private suspend fun heartbeat(client: HttpClient, endpoint: BackendEndpoint): JsonObject {
    val response = client.post("http://127.0.0.1:${endpoint.port}/heartbeat") {
        header("Authorization", "Bearer ${endpoint.password}")
        header("X-Kodex-Instance", endpoint.instanceId)
        header("X-Kodex-Protocol", BackendBusinessProtocol)
    }
    if (response.status.value in 400..499) throw BusinessConnectionRejected()
    check(response.status.value in 200..299) { "Backend heartbeat is unavailable." }
    return Json.parseToJsonElement(response.bodyAsText()) as JsonObject
}

internal data class ActiveConnection(val client: RpcClient, val transport: WebSocketRpcTransport)

public actual class LocalRpcConnection internal constructor(
    private val active: MutableStateFlow<ActiveConnection?>,
    actual override val state: StateFlow<BackendConnectionState>,
    actual override val failureReason: StateFlow<String?>,
) : FrontendRpcConnection {
    actual override val available: Boolean get() = active.value?.transport?.alive == true
    internal var recovery: suspend (RpcClient, () -> Boolean) -> Unit = { _, _ -> }
        private set
    actual override fun onReconnection(handler: suspend (RpcClient, () -> Boolean) -> Unit) { recovery = handler }
    actual override suspend fun <T> call(call: RpcCall): T {
        currentCoroutineContext().ensureActive()
        val connection = active.value ?: throw BackendConnectionLost("Backend is disconnected; operation was not submitted.")
        if (!connection.transport.alive) throw BackendConnectionLost("Backend is disconnected; operation was not submitted.")
        return try { connection.client.call(call) }
        catch (failure: Throwable) {
            currentCoroutineContext().ensureActive()
            if (!connection.transport.alive) {
                throw BackendConnectionLost("Backend transport closed; operation outcome may be unknown.", failure)
            }
            throw failure
        }
    }
    actual override fun <T> callServerStreaming(call: RpcCall): Flow<T> = channelFlow {
        active.collectLatest { connection ->
            if (connection != null) {
                try { connection.client.callServerStreaming<T>(call).collect { send(it) } }
                catch (failure: Throwable) {
                    currentCoroutineContext().ensureActive()
                    if (connection.transport.alive && active.value === connection) throw failure
                }
            }
        }
    }.buffer(0)
}

public actual suspend fun <R> withLocalRpcClient(
    home: Path,
    endpoint: BackendEndpoint,
    block: suspend CoroutineScope.(LocalRpcConnection) -> R,
): R = coroutineScope {
    val frontendOwner = this
    require(endpoint.businessProtocol == BackendBusinessProtocol) { "Backend business protocol is incompatible." }
    val http = HttpClient {
        install(WebSockets)
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 5_000 }
    }
    val active = MutableStateFlow<ActiveConnection?>(null)
    val status = MutableStateFlow(BackendConnectionState.Reconnecting)
    val failureReason = MutableStateFlow<String?>(null)
    val connection = LocalRpcConnection(active, status.asStateFlow(), failureReason.asStateFlow())
    val initial = CompletableDeferred<Unit>()
    var everConnected = false
    var renewals: Job? = null

    suspend fun readFailure(): String? = try {
        BackendInstance.readRecord(home)?.takeIf { it.instanceId == endpoint.instanceId }?.failureReason
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }

    suspend fun CoroutineScope.openConnection(): ActiveConnection {
        val found = BackendInstance.discover(home)
        check(found != null && found.instanceId == endpoint.instanceId) { "Backend instance changed." }
        heartbeat(http, endpoint)
        val socket = http.webSocketSession(
            method = HttpMethod.Get, host = "127.0.0.1", port = endpoint.port, path = "/rpc",
        ) {
            header("Authorization", "Bearer ${endpoint.password}")
            header("X-Kodex-Instance", endpoint.instanceId)
            header("X-Kodex-Protocol", BackendBusinessProtocol)
        }
        val transport = WebSocketRpcTransport(socket, coroutineContext)
        try {
            val client = object : InitializedKrpcClient(rpcClientConfig { serialization { json() } }, transport) {}
            return ActiveConnection(client, transport)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { transport.shutdown() }
            throw failure
        }
    }

    val loop = launch {
        while (true) {
            currentCoroutineContext().ensureActive()
            // Only establishing transport/liveness belongs to this retry boundary.
            val opened = try { openConnection() }
            catch (rejected: BusinessConnectionRejected) {
                failureReason.value = readFailure()
                status.value = BackendConnectionState.Failed
                initial.completeExceptionally(failureReason.value?.let { IllegalStateException(it, rejected) } ?: rejected)
                return@launch
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!everConnected) {
                    initial.completeExceptionally(IllegalStateException(readFailure() ?: "Unable to establish the backend connection.", failure))
                    return@launch
                }
                null
            }
            if (opened != null) {
                try {
                    if (everConnected) {
                        try { connection.recovery(opened.client) { opened.transport.alive } }
                        catch (failure: Throwable) {
                            currentCoroutineContext().ensureActive()
                            // A business/owner failure on a live transport must surface unchanged.
                            if (opened.transport.alive) throw failure
                        }
                    }
                    if (opened.transport.alive) {
                        if (!everConnected) {
                            // Frontend-owned, not a child of one transport or its business recovery callback.
                            renewals = frontendOwner.launch {
                                while (true) {
                                    val interval = try {
                                        val reply = heartbeat(http, endpoint)
                                        val ttl = requireNotNull(reply["idle_ttl_seconds"]?.jsonPrimitive?.longOrNull)
                                        if (ttl == -1L || ttl >= 60) 20_000L else maxOf(100, ttl * 1000 / 3)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Exception) {
                                        // Failed delivery grants no renewal; keep retrying while this frontend owns the instance.
                                        500L
                                    }
                                    delay(interval)
                                }
                            }
                        }
                        active.value = opened
                        status.value = BackendConnectionState.Connected
                        everConnected = true
                        initial.complete(Unit)
                        opened.transport.awaitClosed()
                    }
                } finally {
                    active.value = null
                    withContext(NonCancellable) { opened.transport.shutdown() }
                }
            }
            val found = try { BackendInstance.discover(home) } catch (_: Exception) { null }
            if (found == null || found.instanceId != endpoint.instanceId) {
                failureReason.value = readFailure()
                status.value = BackendConnectionState.Failed
                return@launch
            }
            status.value = BackendConnectionState.Reconnecting
            delay(500)
        }
    }
    loop.invokeOnCompletion { renewals?.cancel() }
    try {
        initial.await()
        block(connection)
    } finally {
        withContext(NonCancellable) {
            loop.cancel()
            renewals?.cancel()
            loop.join()
            renewals?.join()
            http.close()
        }
    }
}
