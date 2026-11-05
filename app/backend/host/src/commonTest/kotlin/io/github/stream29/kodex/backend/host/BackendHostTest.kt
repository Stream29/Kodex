package io.github.stream29.kodex.backend.host

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.cli.settings.NewLineKey
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.processId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

val backendHostTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (cancelled in listOf(false, true)) {
        test("startup failure cancelled=$cancelled ends the host preserves the cause and releases its guard") {
            val home = Path(SystemTemporaryDirectory, "kodex-startup-failure-${Random.nextLong()}")
            val marker = "original startup reason cancelled=$cancelled"
            val original = if (cancelled) CancellationException(marker) else IllegalStateException(marker)
            SystemCoroutineFileSystem.createDirectories(home)
            SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.15\"")
            try {
                supervisorScope {
                    val backend = async {
                        runBackendHost(home, Path(home, "codex"), Path(home, "agents"),
                            createLoginClient = { throw original })
                    }
                    val observed = assertNotNull(withTimeout(10.seconds) { runCatching { backend.await() }.exceptionOrNull() })
                    assertTrue(observed.message.orEmpty().contains(marker))
                    assertTrue(generateSequence<Throwable>(observed) { it.cause }.take(8).any { it === original || it.message == marker })
                    assertNull(BackendInstance.discover(home))
                    assertEquals(marker, BackendInstance.readRecord(home)?.failureReason)
                }
            } finally { removeHost(home) }
        }
    }
    test("parent cancellation does not manufacture a backend failure") {
        withHost { home, _, backend ->
            backend.cancelAndJoin()
            assertNull(BackendInstance.discover(home))
            assertNull(BackendInstance.readRecord(home)?.failureReason)
        }
    }
    for (sameInstance in listOf(true, false)) {
        test("terminal diagnostics belong only to the captured instance same=$sameInstance") {
            withHost { home, endpoint, _ ->
                withLocalRpcClient(home, endpoint) { connection ->
                    val reported = BackendEndpoint(
                        if (sameInstance) endpoint.instanceId else "another-instance",
                        endpoint.pid, endpoint.port, endpoint.password, endpoint.applicationVersion,
                        failureReason = "runtime diagnostic marker",
                    )
                    SystemCoroutineFileSystem.writeString(Path(home, ".locks", "backend", "connection.json"),
                        Json.encodeToString(BackendEndpoint.serializer(), reported))
                    backendControlRequest(endpoint, "stop")
                    withTimeout(10.seconds) { connection.state.first { it == io.github.stream29.kodex.rpc.local.BackendConnectionState.Failed } }
                    assertEquals(if (sameInstance) "runtime diagnostic marker" else null, connection.failureReason.value)
                }
            }
        }
    }
    test("inactive log retention includes rolled siblings and protects live and foreign names") {
        val home = Path(SystemTemporaryDirectory, "kodex-daemon-logging-${Random.nextLong()}")
        val directory = Path(home, "log")
        SystemCoroutineFileSystem.createDirectories(directory)
        try {
            repeat(10) { offset ->
                val name = "Tui-${9_000_000 + offset}"
                SystemCoroutineFileSystem.writeString(Path(directory, "$name.log"), "base")
                SystemCoroutineFileSystem.writeString(Path(directory, "$name-1.log"), "rolled")
            }
            val live = Path(directory, "Backend-${processId()}-1.log")
            val legacy = Path(directory, "Kodex-1.log")
            val foreign = Path(directory, "user-note.txt")
            listOf(live, legacy, foreign).forEach { SystemCoroutineFileSystem.writeString(it, "preserved") }
            initializeProcessLogging(home, "Tui-${processId()}")
            withTimeout(5.seconds) {
                while (!SystemCoroutineFileSystem.exists(Path(directory, "Tui-${processId()}.log"))) delay(10)
            }
            val inactive = SystemCoroutineFileSystem.list(directory).filter { it.name.startsWith("Tui-900000") }
            assertEquals(16, inactive.size) // Eight groups, each with base and rolled sibling.
            assertEquals(8, inactive.count { it.name.endsWith("-1.log") })
            listOf(live, legacy, foreign).forEach { assertEquals("preserved", SystemCoroutineFileSystem.readString(it)) }
        } finally { removeHost(home) }
    }
    test("authenticated controls and two real RPC clients share a single settings owner") {
        withHost { home, endpoint, _ ->
            assertFailsWith<Exception> { BackendInstance.acquire(home) }
            val wrong = BackendEndpoint(endpoint.instanceId, endpoint.pid, endpoint.port, "0".repeat(64), endpoint.applicationVersion)
            assertFailsWith<Exception> { backendControlRequest(wrong, "status") }
            withLocalRpcClient(home, endpoint) { first ->
                val a = RpcServices(RestoringRpcClient(first))
                withLocalRpcClient(home, endpoint) { second ->
                    val b = RpcServices(RestoringRpcClient(second))
                    val old = a.global.getFrontendSettings()
                    assertTrue(a.global.compareAndSetFrontendSettings(old, old.copy(newLineKey = NewLineKey.Enter)))
                    withTimeout(10.seconds) { b.global.getFrontendSettingsFlow().first { it.newLineKey == NewLineKey.Enter } }
                    assertEquals("ready", backendControlRequest(endpoint, "status")["phase"]?.jsonPrimitive?.content)
                    // Management does not require a matching business protocol.
                    val incompatible = BackendEndpoint(endpoint.instanceId, endpoint.pid, endpoint.port, endpoint.password, endpoint.applicationVersion, "incompatible")
                    assertEquals("ready", backendControlRequest(incompatible, "status")["phase"]?.jsonPrimitive?.content)
                    assertFailsWith<IllegalArgumentException> { withLocalRpcClient(home, incompatible) {} }
                }
                assertEquals("ready", backendControlRequest(endpoint, "status")["phase"]?.jsonPrimitive?.content)
            }
        }
    }
    test("disconnecting the sole client never cancels an accepted turn and turn renews daemon TTL") {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        withHost(ttl = 2, stream = {
            flow {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }) { home, endpoint, _ ->
            var index = -1
            withLocalRpcClient(home, endpoint) { connection ->
                val rpc = RpcServices(RestoringRpcClient(connection))
                val model = withTimeout(10.seconds) { rpc.global.getModelsFlow().first { it.isNotEmpty() }.first().slug }
                val defaults = rpc.global.getSettings()
                assertTrue(rpc.global.compareAndSetSettings(
                    defaults, defaults.copy(newSession = defaults.newSession.copy(model = model)),
                ))
                index = rpc.global.createSession(home)
                rpc.global.keepSessionAlive(index)
                rpc.runtime.appendUserMessage(index, listOf(ContentItem.InputText("offline fixture")))
                val waiter = launch { rpc.runtime.resume(index) }
                withTimeout(10.seconds) { started.await() }
                waiter.cancelAndJoin()
            }
            delay(3_000) // Longer than the daemon TTL, without frontend renewal.
            assertTrue(!cancelled.isCompleted)
            assertEquals("ready", backendControlRequest(endpoint, "status")["phase"]?.jsonPrimitive?.content)
            withLocalRpcClient(home, endpoint) { connection ->
                val rpc = RpcServices(RestoringRpcClient(connection))
                rpc.global.keepSessionAlive(index)
                assertTrue(rpc.runtime.getRunningTurn(index))
                rpc.runtime.cancelRunningTurn(index)
                withTimeout(10.seconds) { cancelled.await() }
            }
        }
    }
    test("explicit reload validates one snapshot and broadcasts its frontend section") {
        withHost { home, endpoint, _ ->
            withLocalRpcClient(home, endpoint) { connection ->
                val rpc = RpcServices(RestoringRpcClient(connection))
                SystemCoroutineFileSystem.writeString(Path(home, "settings.yml"),
                    "backend:\n  daemon_ttl_seconds: -1\nfrontend:\n  new_line_key: enter\n")
                backendControlRequest(endpoint, "reload")
                withTimeout(10.seconds) { rpc.global.getFrontendSettingsFlow().first { it.newLineKey == NewLineKey.Enter } }
                val old = rpc.global.getFrontendSettings()
                SystemCoroutineFileSystem.writeString(Path(home, "settings.yml"), "backend:\n  daemon_ttl_seconds: 0\nfrontend: {}\n")
                assertFailsWith<Exception> { backendControlRequest(endpoint, "reload") }
                assertEquals(old, rpc.global.getFrontendSettings())
                assertEquals("ready", backendControlRequest(endpoint, "status")["phase"]?.jsonPrimitive?.content)
            }
        }
    }
    test("backend executes a business error hook once after every client disconnects") {
        val entered = CompletableDeferred<Unit>()
        val fail = CompletableDeferred<Unit>()
        withHost(hooks = """
            hooks:
              - name: offline-fixture
                types: [stop_unhandled_error]
                command: "printf x >> business-hook-marker"
        """.trimIndent(), stream = {
            flow {
                entered.complete(Unit)
                fail.await()
                error("offline fixture failure")
            }
        }) { home, endpoint, _ ->
            withLocalRpcClient(home, endpoint) { connection ->
                val rpc = RpcServices(RestoringRpcClient(connection))
                val model = withTimeout(10.seconds) { rpc.global.getModelsFlow().first { it.isNotEmpty() }.first().slug }
                val defaults = rpc.global.getSettings()
                assertTrue(rpc.global.compareAndSetSettings(
                    defaults, defaults.copy(newSession = defaults.newSession.copy(model = model)),
                ))
                val index = rpc.global.createSession(home)
                rpc.global.keepSessionAlive(index)
                rpc.runtime.appendUserMessage(index, listOf(ContentItem.InputText("offline fixture")))
                val waiter = launch { runCatching { rpc.runtime.resume(index) } }
                withTimeout(10.seconds) { entered.await() }
                waiter.cancelAndJoin()
            }
            fail.complete(Unit)
            val marker = Path(home, "business-hook-marker")
            withTimeout(10.seconds) {
                while (!SystemCoroutineFileSystem.exists(marker)) delay(50)
            }
            delay(300)
            assertEquals("x", SystemCoroutineFileSystem.readString(marker))
        }
    }
    test("manual stop drains an active turn with a connected client and flushes its log") {
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        withHost(stream = {
            flow {
                started.complete(Unit)
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { delay(50); cleaned.complete(Unit) } }
            }
        }) { home, endpoint, stopped ->
            withLocalRpcClient(home, endpoint) { connection ->
                val rpc = RpcServices(RestoringRpcClient(connection))
                val model = withTimeout(10.seconds) { rpc.global.getModelsFlow().first { it.isNotEmpty() }.first().slug }
                val defaults = rpc.global.getSettings()
                assertTrue(rpc.global.compareAndSetSettings(
                    defaults, defaults.copy(newSession = defaults.newSession.copy(model = model)),
                ))
                val index = rpc.global.createSession(home)
                rpc.global.keepSessionAlive(index)
                rpc.runtime.appendUserMessage(index, listOf(ContentItem.InputText("shutdown fixture")))
                val waiter = launch { runCatching { rpc.runtime.resume(index) } }
                withTimeout(10.seconds) { started.await() }
                backendControlRequest(endpoint, "stop")
                withTimeout(10.seconds) { stopped.join() }
                assertTrue(cleaned.isCompleted)
                assertNull(BackendInstance.discover(home))
                assertNull(BackendInstance.readRecord(home)?.failureReason)
                val log = SystemCoroutineFileSystem.readString(Path(home, "log/Backend-${processId()}.log"))
                assertTrue(!log.contains("AgentSession storage is closed."), log)
                waiter.cancelAndJoin()
            }
        }
    }
    test("manual stop acceptance eventually releases the lock") {
        withHost { home, endpoint, stopped ->
            backendControlRequest(endpoint, "stop")
            withTimeout(10.seconds) { stopped.join() }
            assertEquals(null, BackendInstance.discover(home))
        }
    }
    test("connectBackend connects to exact target in current or separate backend Home without touching frontend Home") {
        withHost { backendHome, endpoint, _ ->
            val frontendHome = Path(SystemTemporaryDirectory, "kodex-frontend-only-${Random.nextLong()}")
            try {
                assertEquals(endpoint.instanceId, connectBackend(backendHome, endpoint.port).instanceId)
                val target = connectBackend(backendHome, endpoint.port)
                initializeProcessLogging(frontendHome, "Tui-${processId()}")
                withLocalRpcClient(backendHome, target) { connection ->
                    val rpc = RpcServices(RestoringRpcClient(connection))
                    val old = rpc.global.getFrontendSettings()
                    assertTrue(rpc.global.compareAndSetFrontendSettings(old, old.copy(newLineKey = NewLineKey.Enter)))
                }
                assertTrue(SystemCoroutineFileSystem.exists(Path(frontendHome, "log")))
                assertNull(BackendInstance.discover(frontendHome))
                assertTrue(!SystemCoroutineFileSystem.exists(Path(frontendHome, ".locks", "backend")))
            } finally {
                removeHost(frontendHome)
            }
        }
    }
    test("connectBackend rejects missing lock missing record port mismatch unreachable auth and protocol failures without starting or replacing a backend") {
        val unlockedHome = Path(SystemTemporaryDirectory, "kodex-unlocked-${Random.nextLong()}")
        val lockedNoRecordHome = Path(SystemTemporaryDirectory, "kodex-no-record-${Random.nextLong()}")
        val unreachableHome = Path(SystemTemporaryDirectory, "kodex-unreachable-${Random.nextLong()}")
        try {
            assertFailsWith<IllegalStateException> { connectBackend(unlockedHome, 18080) }
            assertTrue(!SystemCoroutineFileSystem.exists(unlockedHome))

            BackendInstance.acquire(lockedNoRecordHome).use {
                assertFailsWith<IllegalStateException> { connectBackend(lockedNoRecordHome, 18080) }
            }

            BackendInstance.acquire(unreachableHome).use { instance ->
                instance.publish(BackendEndpoint("unreachable-id", processId(), 1, "a".repeat(64), "0.6.0"))
                assertFailsWith<Exception> { connectBackend(unreachableHome, 1) }
            }

            withHost { homeA, endpointA, backendA ->
                withHost { homeB, endpointB, backendB ->
                    assertFailsWith<IllegalStateException> { connectBackend(homeA, endpointB.port) }
                    val recordPath = Path(homeA, ".locks", "backend", "connection.json")
                    val originalRecord = SystemCoroutineFileSystem.readString(recordPath)
                    try {
                        val wrongPassword = BackendEndpoint(
                            endpointA.instanceId, endpointA.pid, endpointA.port,
                            "0".repeat(64), endpointA.applicationVersion,
                        )
                        SystemCoroutineFileSystem.writeString(
                            recordPath, Json.encodeToString(BackendEndpoint.serializer(), wrongPassword),
                        )
                        assertFailsWith<Exception> { connectBackend(homeA, endpointA.port) }

                        val wrongProtocol = BackendEndpoint(
                            endpointA.instanceId, endpointA.pid, endpointA.port,
                            endpointA.password, endpointA.applicationVersion, businessProtocol = "0.5.3",
                        )
                        SystemCoroutineFileSystem.writeString(
                            recordPath, Json.encodeToString(BackendEndpoint.serializer(), wrongProtocol),
                        )
                        assertFailsWith<IllegalStateException> { connectBackend(homeA, endpointA.port) }
                    } finally {
                        SystemCoroutineFileSystem.writeString(recordPath, originalRecord)
                    }
                    assertEquals(endpointA.instanceId, connectBackend(homeA, endpointA.port).instanceId)
                    assertEquals(endpointB.instanceId, connectBackend(homeB, endpointB.port).instanceId)
                    assertTrue(backendA.isActive)
                    assertTrue(backendB.isActive)
                }
            }
        } finally {
            removeHost(unlockedHome)
            removeHost(lockedNoRecordHome)
            removeHost(unreachableHome)
        }
    }
    test("same-instance reconnect with separate backend Home stays pinned to target Home and never falls back to frontend Home") {
        withHost { frontendHome, frontendEndpoint, frontendBackend ->
            withHost { backendHome, targetEndpoint, targetBackend ->
                val connected = connectBackend(backendHome, targetEndpoint.port)
                withLocalRpcClient(backendHome, connected) { connection ->
                    assertEquals(io.github.stream29.kodex.rpc.local.BackendConnectionState.Connected, connection.state.value)
                    backendControlRequest(targetEndpoint, "stop")
                    withTimeout(10.seconds) { targetBackend.join() }
                    withTimeout(10.seconds) {
                        connection.state.first { it == io.github.stream29.kodex.rpc.local.BackendConnectionState.Failed }
                    }
                    assertNull(BackendInstance.discover(backendHome))
                    assertEquals(frontendEndpoint.instanceId, BackendInstance.discover(frontendHome)?.instanceId)
                    assertTrue(frontendBackend.isActive)
                }
            }
        }
    }
}

internal suspend fun withHost(
    ttl: Long = -1,
    hooks: String = "",
    stream: (suspend () -> kotlinx.coroutines.flow.Flow<io.github.stream29.kodex.openai.ResponsesStreamEvent>)? = null,
    prepareHome: suspend CoroutineScope.(Path) -> Unit = {},
    observeRequest: (io.github.stream29.kodex.openai.ResponsesApiRequest) -> Unit = {},
    block: suspend CoroutineScope.(Path, BackendEndpoint, Job) -> Unit,
) = withTimeout(45.seconds) {
    coroutineScope {
        val home = Path(SystemTemporaryDirectory, "kodex-daemon-host-${Random.nextLong()}")
        SystemCoroutineFileSystem.createDirectories(home, mustCreate = true)
        SystemCoroutineFileSystem.writeString(Path(home, "version.json"), "\"0.4.15\"")
        SystemCoroutineFileSystem.writeString(Path(home, "settings.yml"),
            "backend:\n  daemon_ttl_seconds: $ttl\n  new_session:\n    model: fixture\n  session_title:\n    enabled: false\n" +
                (if (hooks.isEmpty()) "" else hooks.prependIndent("  ") + "\n") + "frontend: {}\n")
        try {
            prepareHome(home)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { removeHost(home) }
            throw failure
        }
        val mock = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
                OpenAiModelId("fixture"), "Fixture", contextWindow = 100_000, maxContextWindow = 100_000,
            )))) }
            createResponse { request ->
                observeRequest(request)
                stream?.invoke() ?: flow { awaitCancellation() }
            }
        }
        val backend = launch {
            runBackendHost(home, Path(home, "codex"), Path(home, "agents"), createClient = { mock })
        }
        var endpoint: BackendEndpoint? = null
        try {
            withTimeout(15.seconds) {
                while (true) {
                    endpoint = try { BackendInstance.discover(home) } catch (_: Exception) { null }
                    if (endpoint != null) {
                        val status = try { backendControlRequest(requireNotNull(endpoint), "status") } catch (_: Exception) { null }
                        if (status?.get("phase")?.jsonPrimitive?.content == "ready") break
                    }
                    delay(50)
                }
            }
            block(home, assertNotNull(endpoint), backend)
        } finally {
            withContext(NonCancellable) {
                if (backend.isActive && endpoint != null) runCatching { backendControlRequest(requireNotNull(endpoint), "stop") }
                withTimeoutOrNull(10.seconds) { backend.join() } ?: backend.cancelAndJoin()
                removeHost(home)
            }
        }
    }
}
private suspend fun removeHost(path: Path) {
    val attributes = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (attributes.isDirectory) SystemCoroutineFileSystem.list(path).forEach { removeHost(it) }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
