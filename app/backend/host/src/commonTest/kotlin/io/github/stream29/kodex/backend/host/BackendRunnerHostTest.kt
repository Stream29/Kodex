package io.github.stream29.kodex.backend.host

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.backend.setBackendWorkingDirectory
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.client.OpenAiLoginClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private class ScheduleClockFailure(val marker: String) : Error(marker)

internal val backendRunnerHostTest by testSuite(compartment = { TestCompartment.RealTime }) {
    for (recordWritable in listOf(true, false)) {
        test("fatal schedule runner closes admission before cleanup and retains its Error [recordWritable=$recordWritable]") {
            supervisorScope {
                val home = Path(SystemTemporaryDirectory, "kodex-daemon-runner-${Random.nextLong()}")
                val cwd = SystemCoroutineFileSystem.resolve(Path("."))
                SystemCoroutineFileSystem.createDirectories(home, mustCreate = true)
                val fatal = ScheduleClockFailure("injected schedule clock failure")
                val injected = MutableStateFlow<Throwable?>(null)
                val transientSeen = CompletableDeferred<Unit>()
                val transient = IllegalStateException("injected transient clock failure")
                val frozenTime = Clock.System.now() + 1.minutes
                val clock = object : Clock {
                    override fun now(): kotlin.time.Instant {
                        val failure = injected.value
                        if (failure != null && injected.compareAndSet(failure, null)) {
                            if (failure === transient) transientSeen.complete(Unit)
                            throw failure
                        }
                        return frozenTime
                    }
                }
                val started = CompletableDeferred<Unit>()
                val cleaning = CompletableDeferred<Unit>()
                val releaseCleanup = CompletableDeferred<Unit>()
                var host: Deferred<Unit>? = null
                try {
                    prepareCatalogHomeFixture(home)
                    SystemCoroutineFileSystem.writeString(Path(home, "settings.yml"),
                        "backend:\n  daemon_ttl_seconds: -1\n  new_session:\n    model: fixture\n  session_title:\n    enabled: false\nfrontend: {}\n")
                    val client = mockOpenAiClient {
                        listModels {
                            OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(
                                OpenAiModelId("fixture"), "Fixture", contextWindow = 100_000, maxContextWindow = 100_000,
                            ))))
                        }
                        createResponse {
                            flow {
                                started.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    withContext(NonCancellable) {
                                        cleaning.complete(Unit)
                                        withTimeout(12.seconds) { releaseCleanup.await() }
                                    }
                                }
                            }
                        }
                    }
                    val backend = async {
                        runBackendHostWithClock(
                            home, Path(home, "codex"), Path(home, "agents"),
                            createClient = { client },
                            createLoginClient = { OpenAiLoginClient() },
                            clock = clock,
                            enableProcessStopSignals = false,
                        )
                    }.also { host = it }
                    val endpoint = withTimeout(10.seconds) {
                        while (true) {
                            if (backend.isCompleted) backend.await()
                            val current = try {
                                BackendInstance.discover(home)?.takeIf {
                                    backendControlRequest(it, "status")["phase"]?.jsonPrimitive?.content == "ready"
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                null
                            }
                            if (current != null) return@withTimeout current
                            delay(20)
                        }
                        error("Unreachable")
                    }
                    withLocalRpcClient(home, endpoint) { connection ->
                        val rpc = RpcServices(RestoringRpcClient(connection))
                        val model = withTimeout(8.seconds) { rpc.global.getModelsFlow().first { it.isNotEmpty() }.first().slug }
                        val defaults = rpc.global.getSettings()
                        assertTrue(rpc.global.compareAndSetSettings(
                            defaults, defaults.copy(newSession = defaults.newSession.copy(model = model)),
                        ))
                        val agentId = rpc.global.getRootAgentId(rpc.global.createSession(home))
                        rpc.runtime.keepAgentAlive(agentId)
                        rpc.runtime.submit(agentId, listOf(ContentItem.InputText("leave cleanup running")))
                        withTimeout(8.seconds) { started.await() }
                        if (!recordWritable) {
                            val record = Path(home, ".locks", "backend", "connection.json")
                            SystemCoroutineFileSystem.delete(record, mustExist = true)
                            SystemCoroutineFileSystem.createDirectories(record, mustCreate = true)
                        }
                        // The frozen clock cannot reach this deadline. Arm the next real runner
                        // read only after the scheduling RPC has completed successfully.
                        rpc.global.createSchedule(agentId, frozenTime + 75.milliseconds, null, "inject scheduler failure")
                        injected.value = transient
                    }
                    withTimeout(8.seconds) { transientSeen.await() }
                    assertEquals("ready", backendControlRequest(endpoint, "status")["phase"]?.jsonPrimitive?.content)
                    injected.value = fatal
                    withTimeout(8.seconds) { cleaning.await() }
                    assertNull(injected.value)
                    assertEquals("stopping", backendControlRequest(endpoint, "status")["phase"]?.jsonPrimitive?.content)
                    assertFailsWith<IllegalStateException> { BackendInstance.acquire(home).close() }
                    if (recordWritable) {
                        val failureRecord = assertNotNull(BackendInstance.readRecord(home))
                        assertEquals(endpoint.instanceId, failureRecord.instanceId)
                        assertEquals(fatal.marker, failureRecord.failureReason)
                    }
                    releaseCleanup.complete(Unit)
                    assertSame(fatal, assertFailsWith<ScheduleClockFailure> { withTimeout(8.seconds) { backend.await() } })
                    assertEquals(!recordWritable, fatal.suppressedExceptions.isNotEmpty())
                    assertNull(BackendInstance.discover(home))
                } finally {
                    withContext(NonCancellable) {
                        releaseCleanup.complete(Unit)
                        host?.cancelAndJoin()
                        setBackendWorkingDirectory(cwd)
                        removeHost(home)
                    }
                }
            }
        }
    }
}
