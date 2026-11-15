package io.github.stream29.kodex.backend.host

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwaitEventToolEvent
import io.github.stream29.kodex.backend.BackendEndpoint
import io.github.stream29.kodex.backend.BackendInstance
import io.github.stream29.kodex.cli.rpc.RpcServices
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.FreeformTool
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.ModelInfo
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponseItemId
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.rpc.client.RestoringRpcClient
import io.github.stream29.kodex.rpc.control.backendControlRequest
import io.github.stream29.kodex.rpc.local.withLocalRpcClient
import io.github.stream29.kodex.rpc.models.AgentStateValue
import io.github.stream29.kodex.rpc.models.Notification
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.shellclient.renderedBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val backendStepTwoEndToEndTest by testSuite(compartment = { TestCompartment.RealTime }) {
    test("real backend host drives interactive shell cursor stdin await_event wakeup and auto-title over WebSocket RPC") {
        val callMutex = Mutex()
        var agentTurnCalls = 0
        val capturedToolNames = CompletableDeferred<Set<String>>()
        val capturedShellId = CompletableDeferred<Int>()
        val thirdRequestSerialized = CompletableDeferred<String>()

        withStepTwoHost(
            respond = { request ->
                if (request.text.format?.name == "session_title") {
                    flowOf(
                        ResponsesStreamEvent.OutputTextDelta(
                            itemId = "title_msg",
                            outputIndex = 0,
                            contentIndex = 0,
                            delta = """{"title":"Run interactive shell and await exit"}""",
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "title_resp", endTurn = true)),
                    )
                } else {
                    val callNumber = callMutex.withLock { ++agentTurnCalls }
                    when (callNumber) {
                        1 -> {
                            val names = request.tools.mapNotNull { spec ->
                                when (spec) {
                                    is ResponsesApiTool -> spec.name
                                    is FreeformTool -> spec.name
                                    else -> null
                                }
                            }.toSet()
                            capturedToolNames.complete(names)
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(
                                    0,
                                    ResponseItem.FunctionCall(
                                        name = "exec_command",
                                        arguments = """{"cmd":"printf 'READY\\n'; read line; printf 'DONE:%s\\n' \"${'$'}line\"","yield_time_ms":250,"name":"interactive-worker"}""",
                                        callId = "exec_1",
                                    ),
                                ),
                                ResponsesStreamEvent.Completed(Response(id = "resp_1", endTurn = false)),
                            )
                        }

                        2 -> {
                            val outputText = request.input.toString()
                            val match = requireNotNull(
                                Regex(""""session_id"\s*:\s*(\d+)""").find(outputText),
                            ) {
                                "Expected running session_id in tool output, got: $outputText"
                            }
                            val shellId = match.groupValues[1].toInt()
                            capturedShellId.complete(shellId)
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(
                                    0,
                                    ResponseItem.FunctionCall(
                                        name = "await_event",
                                        arguments = """{"terminals":[$shellId]}""",
                                        callId = "await_1",
                                    ),
                                ),
                                ResponsesStreamEvent.Completed(Response(id = "resp_2", endTurn = false)),
                            )
                        }

                        3 -> {
                            thirdRequestSerialized.complete(request.input.toString())
                            flowOf(
                                ResponsesStreamEvent.OutputItemDone(
                                    0,
                                    ResponseItem.Message(
                                        id = ResponseItemId("msg_3"),
                                        role = MessageRole.Assistant,
                                        content = listOf(ContentItem.OutputText("Worker completed with WORLD")),
                                    ),
                                ),
                                ResponsesStreamEvent.Completed(Response(id = "resp_3", endTurn = true)),
                            )
                        }

                        4 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                0,
                                ResponseItem.FunctionCall(
                                    name = "await_event",
                                    arguments = """{"terminals":[]}""",
                                    callId = "await_2",
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "resp_4", endTurn = false)),
                        )

                        else -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                0,
                                ResponseItem.Message(
                                    id = ResponseItemId("msg_5"),
                                    role = MessageRole.Assistant,
                                    content = listOf(ContentItem.OutputText("Woken by user follow-up")),
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "resp_5", endTurn = true)),
                        )
                    }
                }
            },
        ) { home, endpoint ->
            withLocalRpcClient(home, endpoint) { connection ->
                val rpc = RpcServices(RestoringRpcClient(connection))
                val notifications = MutableStateFlow<List<Notification>>(emptyList())
                val collectorReady = CompletableDeferred<Unit>()
                val notificationJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    collectorReady.complete(Unit)
                    rpc.global.getNotificationFlow().collect { item ->
                        notifications.update { it + item }
                    }
                }
                collectorReady.await()

                try {
                    val index = rpc.global.createSession(home)
                    rpc.global.keepSessionAlive(index)

                    rpc.runtime.submit(
                        index,
                        listOf(ContentItem.InputText("Run an interactive worker shell and await its exit")),
                    )

                    val visibleTools = step("awaiting visible tools") { capturedToolNames.await() }
                    assertTrue("exec_command" in visibleTools, "Expected exec_command in $visibleTools")
                    assertTrue("await_event" in visibleTools, "Expected await_event in $visibleTools")
                    assertFalse("write_stdin" in visibleTools, "Legacy write_stdin must not be exposed: $visibleTools")

                    val shellId = step("awaiting shellId") { capturedShellId.await() }
                    step("awaiting parked PendingAwaitEventToolEvent") {
                        rpc.runtime.getStateFlow(index).first { state ->
                            val pending = (state as? AgentStateValue.ToolPending)?.events.orEmpty()
                            pending.singleOrNull() is PendingAwaitEventToolEvent
                        }
                        rpc.runtime.getRunningTurnFlow(index).first { !it }
                    }

                    val parkedState = assertIs<AgentStateValue.ToolPending>(rpc.runtime.getState(index))
                    val parkedAwait = assertIs<PendingAwaitEventToolEvent>(parkedState.events.single())
                    assertEquals(listOf(shellId), parkedAwait.arguments.terminals)
                    assertTrue(notifications.value.isEmpty(), "Parking on await_event must not emit Stop notifications")

                    step("awaiting auto-generated session title") {
                        while (rpc.global.getSessionCatalog(false).single().threadName != "Run interactive shell and await exit") {
                            delay(50)
                        }
                    }

                    val runningShell = rpc.runtime.getShellSessions(index).getValue(shellId)
                    assertNull(runningShell.exitCode)
                    assertFalse(runningShell.completed)
                    assertEquals("interactive-worker", runningShell.arguments.name)

                    val firstSlice = step("awaiting READY stdout slice") {
                        var slice = rpc.runtime.readShellSessionOutput(index, shellId, offset = 0uL, limit = 4096u)
                        while (!slice.renderedBytes().decodeToString().contains("READY")) {
                            delay(25)
                            slice = rpc.runtime.readShellSessionOutput(index, shellId, offset = 0uL, limit = 4096u)
                        }
                        slice
                    }
                    assertTrue(firstSlice.renderedBytes().decodeToString().contains("READY"))
                    assertTrue(firstSlice.nextOffset > 0uL)

                    rpc.runtime.writeShellSessionStdin(
                        sessionIndex = index,
                        shellSessionId = shellId,
                        chars = "WORLD\n",
                        closeInput = true,
                    )

                    step("awaiting terminal exit wakeup and AssistantMessage") {
                        rpc.runtime.getStateFlow(index).first { it == AgentStateValue.AssistantMessage }
                        rpc.runtime.getRunningTurnFlow(index).first { !it }
                        notifications.first { list ->
                            list.any { item ->
                                item is Notification.Stop.AssistantMessage && item.sessionIndex == index
                            }
                        }
                    }

                    val thirdInput = step("awaiting thirdRequestSerialized") { thirdRequestSerialized.await() }
                    assertTrue(
                        thirdInput.contains("terminal #$shellId (interactive-worker) exited with code 0"),
                        "Expected terminal exited steer message in $thirdInput",
                    )
                    assertTrue(thirdInput.contains("timestamp"), "Expected AwaitEventOutput timestamp in $thirdInput")

                    val completedShell = rpc.runtime.getShellSessions(index).getValue(shellId)
                    assertEquals(0, completedShell.exitCode)
                    assertTrue(completedShell.completed)

                    val deltaSlice = rpc.runtime.readShellSessionOutput(
                        sessionIndex = index,
                        shellSessionId = shellId,
                        offset = firstSlice.nextOffset,
                        limit = 4096u,
                    )
                    val deltaText = deltaSlice.renderedBytes().decodeToString()
                    assertTrue(deltaText.contains("DONE:WORLD"), "Expected DONE:WORLD in delta: $deltaText")
                    assertFalse(deltaText.contains("READY"), "Incremental cursor read must not repeat READY: $deltaText")

                    val fullSlice = rpc.runtime.readShellSessionOutput(
                        sessionIndex = index,
                        shellSessionId = shellId,
                        offset = 0uL,
                        limit = 4096u,
                    )
                    val fullText = fullSlice.renderedBytes().decodeToString()
                    assertTrue(fullText.contains("READY"), "Expected READY in full output: $fullText")
                    assertTrue(fullText.contains("DONE:WORLD"), "Expected DONE:WORLD in full output: $fullText")

                    // Second turn: park in await_event([]) and wake directly via user submit over RPC.
                    rpc.runtime.submit(index, listOf(ContentItem.InputText("Park until next message")))
                    step("awaiting second parked await_event") {
                        rpc.runtime.getStateFlow(index).first { state ->
                            (state as? AgentStateValue.ToolPending)?.events?.singleOrNull() is PendingAwaitEventToolEvent
                        }
                        rpc.runtime.getRunningTurnFlow(index).first { !it }
                    }

                    rpc.runtime.submit(index, listOf(ContentItem.InputText("Wake up now")))
                    step("awaiting user-message wakeup") {
                        rpc.runtime.getStateFlow(index).first { it == AgentStateValue.AssistantMessage }
                        rpc.runtime.getRunningTurnFlow(index).first { !it }
                        notifications.first { list ->
                            list.count { it is Notification.Stop.AssistantMessage && it.sessionIndex == index } >= 2
                        }
                    }
                } finally {
                    notificationJob.cancel()
                }
            }
        }
    }
}

private suspend fun withStepTwoHost(
    respond: suspend (ResponsesApiRequest) -> kotlinx.coroutines.flow.Flow<ResponsesStreamEvent>,
    block: suspend kotlinx.coroutines.CoroutineScope.(Path, BackendEndpoint) -> Unit,
) = withTimeout(45.seconds) {
    coroutineScope {
        val home = Path(SystemTemporaryDirectory, "kodex-step2-e2e-${Random.nextLong()}")
        SystemCoroutineFileSystem.createDirectories(home, mustCreate = true)
        SystemCoroutineFileSystem.writeString(
            Path(home, "settings.yml"),
            """
            backend:
              daemon_ttl_seconds: -1
              new_session:
                model: fixture
              session_title:
                enabled: true
                model: fixture
            frontend: {}
            """.trimIndent() + "\n",
        )
        val mock = mockOpenAiClient {
            listModels {
                OpenAiResult.Success(
                    ModelsResponse(
                        listOf(
                            ModelInfo(
                                OpenAiModelId("fixture"),
                                "Fixture",
                                contextWindow = 100_000,
                                maxContextWindow = 100_000,
                            ),
                        ),
                    ),
                )
            }
            createResponse { request -> respond(request) }
        }
        val backend = launch {
            runBackendHost(home, Path(home, "codex"), Path(home, "agents"), createClient = { mock })
        }
        var endpoint: BackendEndpoint? = null
        try {
            withTimeout(15.seconds) {
                while (true) {
                    endpoint = try {
                        BackendInstance.discover(home)
                    } catch (_: Exception) {
                        null
                    }
                    if (endpoint != null) {
                        val status = try {
                            backendControlRequest(requireNotNull(endpoint), "status")
                        } catch (_: Exception) {
                            null
                        }
                        if (status?.get("phase")?.jsonPrimitive?.content == "ready") break
                    }
                    delay(50)
                }
            }
            block(home, assertNotNull(endpoint))
        } catch (failure: Throwable) {
            val logDir = Path(home, "log")
            val logs = withContext(NonCancellable) {
                runCatching {
                    if (SystemCoroutineFileSystem.exists(logDir)) {
                        val parts = mutableListOf<String>()
                        for (file in SystemCoroutineFileSystem.list(logDir)) {
                            parts += "${file.name}:\n${SystemCoroutineFileSystem.readString(file)}"
                        }
                        parts.joinToString("\n---\n")
                    } else {
                        "no log dir"
                    }
                }.getOrElse { "failed reading logs: $it" }
            }
            throw AssertionError("E2E failed: ${failure.message}\nBackend logs:\n$logs", failure)
        } finally {
            withContext(NonCancellable) {
                if (backend.isActive && endpoint != null) {
                    runCatching { backendControlRequest(requireNotNull(endpoint), "stop") }
                }
                withTimeoutOrNull(10.seconds) { backend.join() } ?: backend.cancel()
                backend.join()
                deleteStepTwoPath(home)
            }
        }
    }
}

private suspend fun <T> step(label: String, block: suspend () -> T): T =
    try {
        withTimeout(10.seconds) { block() }
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("Timed out at step: $label", timeout)
    }

private suspend fun deleteStepTwoPath(path: Path) {
    val attributes = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (attributes.isDirectory) SystemCoroutineFileSystem.list(path).forEach { deleteStepTwoPath(it) }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
