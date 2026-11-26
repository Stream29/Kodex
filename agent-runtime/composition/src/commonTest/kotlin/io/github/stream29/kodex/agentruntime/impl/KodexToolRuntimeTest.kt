package io.github.stream29.kodex.agentruntime.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import io.github.stream29.kodex.agentstate.contract.KodexAgentState as KodexAgentStateContract
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.InvalidToolInvocation
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommit
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCommandExecution
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableInvalidToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatch
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePatchToolExecutionResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTextTool
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.toResponseHistoryItems
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingFunctionToolCall
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingToolCall
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.contract.getUnstableAtOrNull
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputPayload
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesApiNamespace
import io.github.stream29.kodex.openai.ResponsesApiTool
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.applypatch.ApplyPatchTool
import io.github.stream29.kodex.tool.applypatch.ApplyPatchToolClient
import io.github.stream29.kodex.tool.contract.Tool
import io.github.stream29.kodex.tool.plan.PlanTools
import io.github.stream29.kodex.tool.plan.updatePlanTool
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecToolClient
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.logging.agent
import io.github.stream29.kodex.utils.logging.global
import io.github.stream29.kodex.utils.logging.session
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellSettings
import io.github.stream29.kodex.utils.shellclient.default
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.schema.json.PropertyBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

val kodexToolRuntimeTest by testSuite {
    testFixture {
        ToolRuntimeTestContext(
            scope = testSuiteCoroutineScope.supervisorChildScope(),
        )
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
        test("pending calls are handled before requesting another response") {
            val extraTool = RuntimeTestTool("dynamic")
            val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
            var requestCount = 0
            val client = mockOpenAiClient {
                createResponse {
                    requestCount += 1
                    when (requestCount) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.FunctionCall(
                                    name = "dynamic",
                                    arguments = "{}",
                                    callId = "call_dynamic",
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, assistantMessage()),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )

                        else -> error("Unexpected request count $requestCount.")
                    }
                }
            }
            val state = KodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
            )
            state.appendUserMessage(listOf(ContentItem.InputText("Use the tool.")))
            state.requestResponseApi()

            RequestOnlyRuntime(state)
                .testToolRuntime(listOf(extraTool))
                .resume()

            assertEquals(2, requestCount)
            val history = storage.stableHistoryItems()
            assertEquals(
                FunctionCallOutputPayload(FunctionCallOutputBody.Text("done"), success = true),
                assertIs<ResponseItem.FunctionCallOutput>(history[2]).output,
            )
            assertEquals(
                StableTextTool(
                    callId = "call_dynamic",
                    name = "dynamic",
                    arguments = JsonObject(emptyMap()),
                    result = "done",
                    success = true,
                ),
                storage.getCommitsIn(0..storage.latestIndex(), AgentCommitType.WorkTypes)
                    .map { it.commit }
                    .filterIsInstance<StableTextTool>()
                    .single(),
            )
            assertEquals(emptyList(), storage.getUnstableAtOrNull(storage.latestIndex()))
        }

        test("ToolRouting rejects ResponsesApiNamespace specs and leaves namespaced pending calls unmatched") {
            val namespaceTool = object : Tool {
                override val spec: ToolSpec = ResponsesApiNamespace(
                    name = "shared",
                    description = "namespace",
                    tools = listOf(
                        ResponsesApiTool(
                            name = "dynamic",
                            description = "dynamic",
                            parameters = PropertyBuilder().obj { additionalProperties = false },
                        ),
                    ),
                )

                override suspend fun handle(pending: PendingToolCall): AgentCommit.CompletedTool =
                    error("must not run")

                override fun close(): Unit = Unit
            }
            assertFailsWith<IllegalStateException> {
                ToolRouting.index(listOf(namespaceTool))
            }

            val plainTool = RuntimeTestTool("dynamic")
            val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
            val state = KodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.FunctionCall(
                                    name = "dynamic",
                                    namespace = "shared",
                                    arguments = "{}",
                                    callId = "call_namespaced",
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )
                    }
                },
                storage = storage,
                contextSettings = TestAgentContextSettings,
            )
            state.appendUserMessage(listOf(ContentItem.InputText("Try namespaced call.")))
            RequestOnlyRuntime(state).testToolRuntime(listOf(plainTool)).resume()

            val pending = assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
            assertEquals(listOf("call_namespaced"), pending.events.map(PendingToolCall::callId))
        }

        test("unowned pending calls stop at the state boundary") {
            val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
            val state = KodexAgentState(
                client = mockOpenAiClient {
                    createResponse {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.FunctionCall(
                                    name = "host_only",
                                    arguments = "{}",
                                    callId = "call_host_only",
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )
                    }
                },
                storage = storage,
                contextSettings = TestAgentContextSettings,
            )
            state.appendUserMessage(listOf(ContentItem.InputText("Ask the host.")))
            val runtime = RequestOnlyRuntime(state).testToolRuntime()

            runtime.resume()

            val pending = assertIs<KodexAgentStateValue.ToolPending>(state.state.value)
            assertEquals(listOf("call_host_only"), pending.events.map(PendingToolCall::callId))
        }

        test("a throwing tool logs contextual error, converts to failed completion, and does not block another tool in the same batch") {
            var failedCalls = 0
            var successfulCalls = 0
            val rootFailure = IllegalStateException("private failure")
            val failing = RuntimeTestTool("failing") {
                failedCalls++
                throw rootFailure
            }
            val successful = RuntimeTestTool("successful") {
                successfulCalls++
                "done"
            }
            val recordingLogger = FailureRecordingLogger()
            val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
            var requestCount = 0
            val client = mockOpenAiClient {
                createResponse {
                    requestCount++
                    if (requestCount == 1) {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                0,
                                ResponseItem.FunctionCall(
                                    name = "failing",
                                    arguments = "{}",
                                    callId = "failed",
                                ),
                            ),
                            ResponsesStreamEvent.OutputItemDone(
                                1,
                                ResponseItem.FunctionCall(
                                    name = "successful",
                                    arguments = "{}",
                                    callId = "succeeded",
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )
                    } else {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, assistantMessage()),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )
                    }
                }
            }
            val state = KodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
            )
            state.appendUserMessage(listOf(ContentItem.InputText("Use both tools.")))
            RequestOnlyRuntime(state)
                .testToolRuntime(
                    extraTools = listOf(failing, successful),
                    baseLogger = recordingLogger,
                )
                .resume()

            val outputs = storage.stableHistoryItems().filterIsInstance<ResponseItem.FunctionCallOutput>()
            assertEquals(2, outputs.size)
            assertEquals(
                FunctionCallOutputPayload(FunctionCallOutputBody.Text("Tool execution failed."), success = false),
                outputs[0].output,
            )
            assertEquals(FunctionCallOutputPayload(FunctionCallOutputBody.Text("done"), success = true), outputs[1].output)
            assertEquals(1, failedCalls)
            assertEquals(1, successfulCalls)
            assertEquals(2, requestCount)
            val errorEvent = recordingLogger.events.single { it.level == Level.ERROR }
            assertSame(rootFailure, errorEvent.cause)
            assertEquals("Tool execution failed.", errorEvent.message)
        }

        test("a tool that completes itself before throwing is not completed twice") {
            lateinit var state: KodexAgentStateContract
            val tool = RuntimeTestTool("self_complete") { pending ->
                state.completeToolCall(
                    StableTextTool(
                        callId = pending.callId,
                        itemId = pending.itemId,
                        name = pending.name,
                        namespace = pending.namespace,
                        arguments = pending.arguments,
                        result = "already completed",
                        success = true,
                    ),
                )
                error("failure after completion")
            }
            val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
            var requestCount = 0
            val client = mockOpenAiClient {
                createResponse {
                    requestCount++
                    if (requestCount == 1) {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                0,
                                ResponseItem.FunctionCall(
                                    name = "self_complete",
                                    arguments = "{}",
                                    callId = "self",
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )
                    } else {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, assistantMessage()),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )
                    }
                }
            }
            state = KodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
            )
            state.appendUserMessage(listOf(ContentItem.InputText("Use the tool.")))
            RequestOnlyRuntime(state).testToolRuntime(listOf(tool)).resume()
            val outputs = storage.stableHistoryItems().filterIsInstance<ResponseItem.FunctionCallOutput>()
            assertEquals(1, outputs.size)
            assertEquals(FunctionCallOutputPayload(FunctionCallOutputBody.Text("already completed"), success = true), outputs.single().output)
            assertEquals(2, requestCount)
        }

        test("persistence failure propagates with the original execution failure") {
            val executionFailure = IllegalStateException("tool failure")
            val persistenceFailure = IllegalStateException("storage failure")
            val tool = RuntimeTestTool("failing") { throw executionFailure }
            val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
            var requestCount = 0
            val client = mockOpenAiClient {
                createResponse {
                    requestCount++
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            0,
                            ResponseItem.FunctionCall(
                                name = "failing",
                                arguments = "{}",
                                callId = "call",
                            ),
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )
                }
            }
            val state = KodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
            )
            state.appendUserMessage(listOf(ContentItem.InputText("Use the tool.")))
            val layer = object : ResumableAgentLayer, KodexAgentStateContract by state {
                override suspend fun resume() = RequestOnlyRuntime(state).resume()

                override suspend fun completeToolCall(completed: AgentCommit.CompletedTool): Int {
                    throw persistenceFailure
                }
            }
            val failure = assertFailsWith<IllegalStateException> { layer.testToolRuntime(listOf(tool)).resume() }
            assertSame(persistenceFailure, failure)
            assertTrue(failure.suppressedExceptions.any { it === executionFailure })
            assertEquals(1, requestCount)
        }

        test("tool cancellation propagates and does not run the next tool") {
            var otherCalls = 0
            val cancellation = CancellationException("cancel tool")
            val failing = RuntimeTestTool("failing") { throw cancellation }
            val successful = RuntimeTestTool("successful") {
                otherCalls++
                "done"
            }
            val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
            val client = mockOpenAiClient {
                createResponse {
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            0,
                            ResponseItem.FunctionCall(
                                name = "failing",
                                arguments = "{}",
                                callId = "cancelled",
                            ),
                        ),
                        ResponsesStreamEvent.OutputItemDone(
                            1,
                            ResponseItem.FunctionCall(
                                name = "successful",
                                arguments = "{}",
                                callId = "untouched",
                            ),
                        ),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )
                }
            }
            val state = KodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
            )
            state.appendUserMessage(listOf(ContentItem.InputText("Use both tools.")))
            assertEquals(
                cancellation,
                assertFailsWith<CancellationException> {
                    RequestOnlyRuntime(state).testToolRuntime(listOf(failing, successful)).resume()
                },
            )
            assertEquals(0, otherCalls)
            assertTrue(storage.stableHistoryItems().filterIsInstance<ResponseItem.FunctionCallOutput>().isEmpty())
        }

        test("direct tool execution preserves handler input and successful output") {
            val handledCalls = mutableListOf<PendingFunctionToolCall>()
            val tool = RuntimeTestTool("dynamic") { pending ->
                handledCalls += pending
                "original"
            }
            val call = ResponseItem.FunctionCall(
                name = "dynamic",
                arguments = "{\"value\":\"before\"}",
                callId = "call_hooked",
            )
            val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
            var requestCount = 0
            val client = mockOpenAiClient {
                createResponse {
                    requestCount += 1
                    when (requestCount) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, call),
                            ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                        )

                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(0, assistantMessage()),
                            ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = true)),
                        )

                        else -> error("Unexpected request count $requestCount.")
                    }
                }
            }
            val state = KodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
            )
            state.appendUserMessage(listOf(ContentItem.InputText("Use the tool.")))
            val runtime = RequestOnlyRuntime(state).testToolRuntime(listOf(tool))

            assertEquals(Unit, runtime.resume())

            assertEquals(
                buildJsonObject { put("value", JsonPrimitive("before")) },
                handledCalls.single().arguments,
            )
            val output = storage.stableHistoryItems().filterIsInstance<ResponseItem.FunctionCallOutput>().single()
            assertEquals(FunctionCallOutputPayload(FunctionCallOutputBody.Text("original"), success = true), output.output)
        }

        test("custom tool uses the latest cwd and preserves original input") {
            val initialRoot = Path(
                SystemTemporaryDirectory,
                "codex-tool-runtime-initial-${Random.nextLong()}",
            )
            val updatedRoot = Path(
                SystemTemporaryDirectory,
                "codex-tool-runtime-updated-${Random.nextLong()}",
            )
            SystemCoroutineFileSystem.createDirectories(initialRoot)
            SystemCoroutineFileSystem.createDirectories(updatedRoot)
            val patch = """
                *** Begin Patch
                *** Add File: hook.txt
                +hook input
                *** End Patch
            """.trimIndent()
            val call = ResponseItem.CustomToolCall(
                callId = "call_patch",
                name = "apply_patch",
                input = patch,
            )
            val initialSettings = KodexAgentSettings(
                model = OpenAiModelId("test-model"),
                cwd = initialRoot,
            )
            val fixture = testStateWithCalls(
                settings = initialSettings,
                call,
            )
            try {
                val runtime = RequestOnlyRuntime(fixture.state).testToolRuntime()
                fixture.state.updateSettings(initialSettings.copy(cwd = updatedRoot))

                runtime.resume()

                assertEquals(null, SystemCoroutineFileSystem.metadataOrNull(Path(initialRoot, "hook.txt")))
                assertEquals(
                    "hook input\n",
                    SystemCoroutineFileSystem.readString(Path(updatedRoot, "hook.txt")),
                )
                val completed = fixture.state.storage.getCommitsIn(
                    0..fixture.state.latestIndex.value,
                    AgentCommitType.WorkTypes,
                )
                    .map { it.commit }
                    .filterIsInstance<StablePatch>()
                    .single()
                assertIs<StablePatchToolExecutionResult.Success>(completed.result)
            } finally {
                deleteRecursively(initialRoot)
                deleteRecursively(updatedRoot)
            }
        }

        test("exec command and failed exec command persist independent completions") {
            val execCall = ResponseItem.FunctionCall(
                name = "exec_command",
                arguments = "{\"cmd\":\"sleep 1\",\"name\":\"Exercise background wait\",\"yield_time_ms\":250}",
                callId = "call_exec",
            )
            val failedExecCall = ResponseItem.FunctionCall(
                name = "exec_command",
                arguments = "{\"cmd\":\"   \",\"name\":\"Check blank command failure\",\"yield_time_ms\":250}",
                callId = "call_failed_exec",
            )
            val fixture = testStateWithCalls(calls = arrayOf(execCall, failedExecCall))

            val runtime = RequestOnlyRuntime(fixture.state).testToolRuntime()
            assertEquals(Unit, runtime.resume())

            val outputs = fixture.state.storage.stableHistoryItems()
                .filterIsInstance<ResponseItem.ToolCallOutput>()
            assertEquals(listOf("call_exec", "call_failed_exec"), outputs.map { it.callId })
            val completed = fixture.state.storage.getCommitsIn(
                0..fixture.state.latestIndex.value,
                AgentCommitType.WorkTypes,
            )
                .map { it.commit }
                .filterIsInstance<StableCommandExecution>()
            assertIs<StableCommandExecutionResult.Output>(completed.single { it.callId == "call_exec" }.result)
            assertIs<StableCommandExecutionResult.Failure>(completed.single { it.callId == "call_failed_exec" }.result)
            assertEquals(KodexAgentStateValue.AssistantMessage, fixture.state.state.value)
        }

        test("invalid pending calls complete without invoking their ordinary handler") {
            val malformedExecCall = ResponseItem.FunctionCall(
                name = UnifiedExecTools.ExecCommandName,
                arguments = """{"cmd":""",
                callId = "call_invalid",
            )
            val missingYieldExecCall = ResponseItem.FunctionCall(
                name = UnifiedExecTools.ExecCommandName,
                arguments = """{"cmd":"echo should-not-run","name":"Check missing yield"}""",
                callId = "call_exec_missing_yield",
            )
            val missingNameExecCall = ResponseItem.FunctionCall(
                name = UnifiedExecTools.ExecCommandName,
                arguments = """{"cmd":"echo should-not-run","yield_time_ms":250}""",
                callId = "call_exec_missing_name",
            )
            val retiredToolSearchCall = ResponseItem.ClientToolSearchCall(
                callId = "call_retired_search",
                arguments = buildJsonObject { put("query", JsonPrimitive("dynamic")) },
            )
            val invalidCalls: List<ResponseItem.ToolCall> = listOf(
                malformedExecCall,
                missingYieldExecCall,
                retiredToolSearchCall,
                missingNameExecCall,
            )
            val fixture = testStateWithCalls(
                calls = invalidCalls.toTypedArray(),
            )

            RequestOnlyRuntime(fixture.state)
                .testToolRuntime()
                .resume()

            val workEvents = fixture.state.storage.getCommitsIn(
                0..fixture.state.latestIndex.value,
                AgentCommitType.WorkTypes,
            )
                .map { it.commit }
            assertTrue(workEvents.filterIsInstance<StableCommandExecution>().isEmpty())
            val completed = workEvents.filterIsInstance<StableInvalidToolCall>()
            assertEquals(
                listOf("call_invalid", "call_exec_missing_yield", "call_retired_search", "call_exec_missing_name"),
                completed.map { it.callId },
            )
            assertEquals(
                InvalidToolInvocation.Function(
                    name = malformedExecCall.name,
                    arguments = malformedExecCall.arguments,
                ),
                completed[0].invocation,
            )
            assertEquals(
                InvalidToolInvocation.Function(
                    name = "tool_search",
                    arguments = """{"query":"dynamic"}""",
                ),
                completed[2].invocation,
            )
            assertEquals("`name` must be nonblank, single-line text.", completed[3].message)
            assertEquals(KodexAgentStateValue.AssistantMessage, fixture.state.state.value)
        }

        test("update plan is handled by the ordinary tool runtime") {
            val plan = UpdatePlanArgs(
                explanation = "Start implementation.",
                plan = listOf(PlanItemArg("Implement runtime", StepStatus.InProgress)),
            )
            val call = ResponseItem.FunctionCall(
                name = PlanTools.Name,
                arguments = OpenAiJsonCodec.encodeToString(plan),
                callId = "call_plan",
            )
            val fixture = testStateWithCalls(calls = arrayOf(call))

            RequestOnlyRuntime(fixture.state)
                .testToolRuntime()
                .resume()

            assertEquals(plan, fixture.state.storage.getSettingsAt(fixture.state.storage.latestIndex()).plan)
            val completed = fixture.state.storage.getCommitsIn(
                0..fixture.state.latestIndex.value,
                AgentCommitType.IndexTypes,
            )
                .map { it.commit }
                .filterIsInstance<StablePlanUpdate>()
                .single()
            assertEquals(plan, completed.arguments)
            assertEquals(
                listOf("call_plan"),
                fixture.state.storage.stableHistoryItems()
                    .filterIsInstance<ResponseItem.ToolCallOutput>()
                    .map { it.callId },
            )
            assertEquals(KodexAgentStateValue.AssistantMessage, fixture.state.state.value)
        }
    }
}

private class ToolRuntimeTestContext(
    scope: CoroutineScope,
) : CoroutineScope by scope {
    fun ResumableAgentLayer.testToolRuntime(
        extraTools: List<Tool> = emptyList(),
        baseLogger: KLogger = TestLogger,
    ): KodexToolRuntime {
        val tools = buildList {
            add(
                ApplyPatchTool(
                    ApplyPatchToolClient(
                        workingDirectoryProvider = { storage.getSettingsAt(storage.latestIndex()).cwd },
                    ),
                ),
            )
            add(updatePlanTool())
            add(
                UnifiedExecTools.createTool(
                    UnifiedExecToolClient(
                        settingsProvider = { TestShellSettings() },
                        workingDirectoryProvider = { storage.getSettingsAt(storage.latestIndex()).cwd },
                    ),
                ),
            )
            addAll(extraTools)
        }
        coroutineContext.job.invokeOnCompletion {
            tools.asReversed().forEach { tool -> runCatching { tool.close() } }
        }
        return toolRuntime(
            tools = tools,
            logger = baseLogger
                .global()
                .session(storage.agentId)
                .agent(),
        )
    }
}

private class RequestOnlyRuntime(
    private val delegate: KodexAgentStateContract,
) : ResumableAgentLayer, KodexAgentStateContract by delegate {
    override suspend fun resume() {
        while (true) {
            if (state.value is KodexAgentStateValue.ToolPending) return
            when (requestResponseApi()) {
                RequestFinish.Continue,
                RequestFinish.Retryable,
                -> Unit

                RequestFinish.Finish -> return
            }
        }
    }
}

private data class TestShellSettings(
    override val shell: Shell = Shell.default,
) : ShellSettings

private class RuntimeTestTool(
    name: String,
    private val handler: suspend (PendingFunctionToolCall) -> String = { "done" },
) : Tool {
    override val spec: ToolSpec = ResponsesApiTool(
        name = name,
        description = "$name tool",
        parameters = PropertyBuilder().obj { additionalProperties = false },
    )

    override suspend fun handle(pending: PendingToolCall): AgentCommit.CompletedTool {
        val functionPending = assertIs<PendingFunctionToolCall>(pending)
        return StableTextTool(
            callId = functionPending.callId,
            itemId = functionPending.itemId,
            name = functionPending.name,
            namespace = functionPending.namespace,
            arguments = functionPending.arguments,
            result = handler(functionPending),
            success = true,
        )
    }

    override fun close(): Unit = Unit
}

private data class ToolCallTestState(
    val state: KodexAgentStateContract,
    val client: OpenAiClient,
)

private suspend fun CoroutineScope.testStateWithCalls(
    settings: KodexAgentSettings = KodexAgentSettings(
        model = OpenAiModelId("test-model"),
    ),
    vararg calls: ResponseItem.ToolCall,
): ToolCallTestState {
    var requestCount = 0
    val client = mockOpenAiClient {
        createResponse {
            val call = calls.getOrNull(requestCount)
            requestCount += 1
            if (call != null) {
                flowOf(
                    ResponsesStreamEvent.OutputItemDone(0, call),
                    ResponsesStreamEvent.Completed(
                        Response(id = "response_$requestCount", endTurn = false),
                    ),
                )
            } else {
                flowOf(
                    ResponsesStreamEvent.OutputItemDone(0, assistantMessage()),
                    ResponsesStreamEvent.Completed(
                        Response(id = "response_$requestCount", endTurn = true),
                    ),
                )
            }
        }
    }
    val state = KodexAgentState(
        client = client,
        storage = InMemoryKodexAgentStorage(settings),
        contextSettings = TestAgentContextSettings,
    )
    state.appendUserMessage(listOf(ContentItem.InputText("Use the tool.")))
    return ToolCallTestState(state, client)
}

private fun assistantMessage(): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText("Done.")),
    )

private suspend fun KodexAgentStorage.stableHistoryItems(): List<ResponseItem.HistoryItem> =
    getCommitsIn(0..latestIndex(), AgentCommitType.CleanEventTypes)
        .flatMap { it.commit.toResponseHistoryItems() }

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

private val TestLogger = KotlinLogging.logger {}
