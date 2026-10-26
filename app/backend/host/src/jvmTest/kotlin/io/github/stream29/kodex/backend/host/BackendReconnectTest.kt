package io.github.stream29.kodex.backend.host

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.cli.app.withFrontendApplication
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.local.BackendConnectionState
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val backendReconnectTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("frontend renewals continue while healthy transport recovery is waiting") {
        withHost(ttl = 3) { home, endpoint, backend ->
            TcpProxy(endpoint.port).use { proxy ->
                val routed = BackendEndpoint(endpoint.instanceId, endpoint.pid, proxy.port, endpoint.password, endpoint.applicationVersion)
                withLocalRpcClient(home, routed) { connection ->
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    connection.onReconnection { _, available ->
                        assertTrue(available())
                        entered.complete(Unit)
                        release.await()
                    }
                    try {
                        proxy.paused = true
                        proxy.dropConnections()
                        withTimeout(10.seconds) { connection.state.first { it == BackendConnectionState.Reconnecting } }
                        proxy.paused = false
                        withTimeout(5.seconds) { entered.await() }
                        delay(4_500) // Longer than the TTL, without any completed business recovery.
                        assertTrue(backend.isActive)
                        assertEquals("ready", io.github.stream29.kodex.rpc.control.backendControlRequest(endpoint, "status")["phase"]?.let {
                            (it as kotlinx.serialization.json.JsonPrimitive).content
                        })
                        release.complete(Unit)
                        withTimeout(5.seconds) { connection.state.first { it == BackendConnectionState.Connected } }
                    } finally { release.complete(Unit) }
                }
            }
        }
    }
    test("a live-transport recovery callback failure surfaces once instead of reconnect retry") {
        withHost { home, endpoint, _ ->
            TcpProxy(endpoint.port).use { proxy ->
                val routed = BackendEndpoint(endpoint.instanceId, endpoint.pid, proxy.port, endpoint.password, endpoint.applicationVersion)
                supervisorScope {
                    var callbacks = 0
                    val failure = IllegalStateException("fixture frontend recovery failure")
                    val task = async {
                        withLocalRpcClient(home, routed) { connection ->
                            connection.onReconnection { _, _ -> callbacks++; throw failure }
                            proxy.paused = true
                            proxy.dropConnections()
                            withTimeout(10.seconds) { connection.state.first { it == BackendConnectionState.Reconnecting } }
                            proxy.paused = false
                            awaitCancellation()
                        }
                    }
                    val observed = withTimeout(15.seconds) { runCatching { task.await() }.exceptionOrNull() }
                    assertIs<IllegalStateException>(observed)
                    assertEquals(failure.message, observed.message)
                    assertEquals(1, callbacks)
                }
            }
        }
    }
    test("real socket loss preserves frontend tabs and the sole composer across same-instance recovery") {
        withHost { home, endpoint, _ ->
            TcpProxy(endpoint.port).use { proxy ->
                val routed = BackendEndpoint(endpoint.instanceId, endpoint.pid, proxy.port, endpoint.password, endpoint.applicationVersion)
                withLocalRpcClient(home, routed) { connection ->
                    val rpc = RpcServices(RestoringRpcClient(connection))
                    assertEquals(OpenAiModelId("fixture"), rpc.global.getSettings().newSession.model)
                    val index = rpc.global.createSession(home)
                    withFrontendApplication(connection, home, io.github.stream29.kodex.cli.app.NativeFrontendHost,
                        io.github.stream29.kodex.cli.rpc.LocalRpcFrontendEnvironment) { app ->
                        val tab = app.viewModel.openSession(index)
                        val agent = requireNotNull(tab.rootAgent.value)
                        val composer = agent.composer
                        composer.update("not submitted", 5)
                        val tabs = app.viewModel.navigation.value.tabs
                        proxy.paused = true
                        proxy.dropConnections()
                        withTimeout(10.seconds) { connection.state.first { it == BackendConnectionState.Reconnecting } }
                        assertEquals("not submitted", composer.state.value.text)
                        assertSame(agent, tab.rootAgent.value)
                        assertEquals(tabs, app.viewModel.navigation.value.tabs)
                        proxy.paused = false
                        withTimeout(10.seconds) { connection.state.first { it == BackendConnectionState.Connected } }
                        delay(100)
                        assertSame(agent, tab.rootAgent.value)
                        assertSame(composer, requireNotNull(tab.rootAgent.value).composer)
                        assertEquals("not submitted", composer.state.value.text)
                        assertEquals(5, composer.state.value.cursorOffset)
                        assertEquals(tabs, app.viewModel.navigation.value.tabs)
                        assertTrue(rpc.global.getSessionCatalog(false).any { it.sessionIndex == index })
                    }
                }
            }
        }
    }
}

/** A bounded local byte proxy, not a product transport fallback or mocked RPC method. */
private class TcpProxy(private val target: Int) : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newFixedThreadPool(34) { runnable ->
        Thread(runnable, "kodex-fixture-proxy").apply { isDaemon = true }
    }
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile var paused = false
    @Volatile private var closed = false
    val port: Int get() = server.localPort
    init {
        pool.submit {
            while (!closed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                if (paused || sockets.size >= 32) { client.close(); continue }
                val backend = try { Socket("127.0.0.1", target) } catch (_: Exception) { client.close(); continue }
                sockets.add(client); sockets.add(backend)
                pool.submit { pipe(client, backend) }
                pool.submit { pipe(backend, client) }
            }
        }
    }
    private fun pipe(from: Socket, to: Socket) {
        try { from.getInputStream().copyTo(to.getOutputStream()) }
        catch (_: Exception) { /* Deliberately injected socket loss. */ }
        finally {
            runCatching { from.close() }; runCatching { to.close() }
            sockets.remove(from); sockets.remove(to)
        }
    }
    fun dropConnections() { sockets.toList().forEach { runCatching { it.close() } } }
    override fun close() {
        closed = true
        server.close()
        dropConnections()
        pool.shutdownNow()
    }
}
