package io.github.stream29.kodex.app.webrenderersmoke

import io.github.stream29.kodex.rpc.client.RpcWireSerializers
import io.github.stream29.kodex.rpc.websocket.WebSocketRpcTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.*
import kotlinx.io.files.Path
import kotlinx.rpc.krpc.client.InitializedKrpcClient
import kotlinx.rpc.krpc.rpcClientConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.serialization.json.*

internal suspend fun <R> withFixtureConnection(
    token: String,
    block: suspend CoroutineScope.(Path, FixtureConnection) -> R,
): R = coroutineScope {
    val http = HttpClient(Js) { install(WebSockets) }
    val connection = FixtureConnection()
    val initial = CompletableDeferred<Path>()
    val loop = launch {
        while (true) {
            val socket = try {
                withTimeout(10_000) { http.webSocketSession("ws://127.0.0.1:18796/fixture") }
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (!initial.isCompleted) {
                    initial.completeExceptionally(failure)
                    return@launch
                }
                delay(500)
                continue
            }
            val transport = WebSocketRpcTransport(socket, currentCoroutineContext())
            var peer: FixturePeer? = null
            try {
                val cwd = withTimeout(5_000) {
                    socket.send(Frame.Text(buildJsonObject {
                        put("token", token); put("instance", "web-fixture-995"); put("protocol", 1)
                    }.toString()))
                    val reply = socket.incoming.receive() as? Frame.Text
                    check(reply != null) { "Fixture handshake rejected." }
                    val ready = Json.parseToJsonElement(reply.readText()).jsonObject
                    check(ready["ready"]?.jsonPrimitive?.boolean == true)
                    Path(ready.getValue("cwd").jsonPrimitive.content)
                }
                if (initial.isCompleted) check(cwd == initial.await()) { "Fixture backend Home changed." }
                val client = object : InitializedKrpcClient(
                    rpcClientConfig { serialization { json { serializersModule = RpcWireSerializers } } }, transport,
                ) {}
                val opened = connection.observe(client, { transport.alive }, { transport.shutdown() }).also { peer = it }
                try {
                    connection.install(opened)
                    initial.complete(cwd)
                    transport.awaitClosed()
                } catch (failure: Throwable) {
                    currentCoroutineContext().ensureActive()
                    if (transport.alive) throw failure
                }
            } finally {
                peer?.let(connection::detach)
                withContext(NonCancellable) { transport.shutdown() }
            }
            delay(500)
        }
    }
    try { block(initial.await(), connection) }
    finally {
        withContext(NonCancellable) { loop.cancelAndJoin(); http.close() }
    }
}
