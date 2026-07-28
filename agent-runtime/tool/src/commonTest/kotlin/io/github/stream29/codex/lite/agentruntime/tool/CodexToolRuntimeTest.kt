package io.github.stream29.codex.lite.agentruntime.tool

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentruntime.contract.CodexAgentRuntime
import io.github.stream29.codex.lite.agentruntime.turnhook.turnHookRuntime
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestAgentContextSettings
import io.github.stream29.codex.lite.agentstate.test.TestMcpService
import io.github.stream29.codex.lite.agentstate.tool.toDeferredToolSearchDocuments
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.hook.contract.tool.HookToolInvocation
import io.github.stream29.codex.lite.hook.contract.tool.NoOpToolHooks
import io.github.stream29.codex.lite.hook.contract.turn.NoOpTurnHooks
import io.github.stream29.codex.lite.hook.contract.tool.PostToolUseRequest
import io.github.stream29.codex.lite.hook.contract.tool.PreToolUseResult
import io.github.stream29.codex.lite.hook.contract.tool.ToolHooks
import io.github.stream29.codex.lite.mcp.contract.McpService
import io.github.stream29.codex.lite.mcp.contract.McpTool
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.PlanItemArg
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesApiTool
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.StepStatus
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchToolClient
import io.github.stream29.codex.lite.tool.applypatch.ApplyPatchTools
import io.github.stream29.codex.lite.tool.contract.Tool
import io.github.stream29.codex.lite.tool.plan.PlanTools
import io.github.stream29.codex.lite.tool.plan.updatePlanTool
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchEngine
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecToolClient
import io.github.stream29.codex.lite.tool.unifiedexec.UnifiedExecTools
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.coroutines.supervisorChildScope
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.schema.json.PropertyBuilder
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

val codexToolRuntimeTest by testSuite {
    testFixture {
        ToolRuntimeTestContext(
            scope = testSuiteCoroutineScope.supervisorChildScope(),
        )
    } closeWith {
        cancelAndJoin()
    } asContextForEach {
    test("request tool search spec is projected without writing settings") {
        val mcpService = TestMcpService(listOf(RuntimeTestTool("mcp__shared", "deferred")))
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val client = mockOpenAiClient {
            createResponse { request ->
                requests += request
                flowOf(
                    ResponsesStreamEvent.OutputItemDone(0, assistantMessage()),
                    ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = true)),
                )
            }
        }
        val state = CodexAgentState(
            client = client,
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = mcpService,
        )
        state.appendUserMessage(listOf(ContentItem.InputText("Use a tool.")))
        val runtime = RequestOnlyRuntime(state)
            .testToolRuntime(mcpService, NoOpToolHooks)

        runtime.resume().toList()

        assertEquals(1, requests.size)
        val toolSearchSpec = assertIs<ToolSpec.ToolSearch>(requests.single().tools.last())
        assertTrue(toolSearchSpec.description.contains("- shared: Tools exposed by shared."))
        assertTrue(toolSearchSpec.description.contains("Codex Lite local tools").not())
        assertTrue(toolSearchSpec.description.contains("MCP servers").not())
        assertEquals(listOf(0), storage.settings.indexes().toList())
    }

    test("deferred search output and handler use the active catalog") {
        val dynamicTool = RuntimeTestTool("mcp__shared", "dynamic")
        val mcpService = TestMcpService(listOf(dynamicTool))
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val searchCall = ResponseItem.ClientToolSearchCall(
            callId = "call_search",
            arguments = buildJsonObject { put("query", JsonPrimitive("dynamic")) },
        )
        val dynamicCall = ResponseItem.FunctionCall(
            name = "dynamic",
            namespace = "mcp__shared",
            arguments = "{}",
            callId = "call_dynamic",
        )
        val client = mockOpenAiClient {
            createResponse { request ->
                requests += request
                when (requests.size) {
                    1 -> flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, searchCall),
                        ResponsesStreamEvent.Completed(Response(id = "response_1", endTurn = false)),
                    )

                    2 -> flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, dynamicCall),
                        ResponsesStreamEvent.Completed(Response(id = "response_2", endTurn = false)),
                    )

                    3 -> flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, assistantMessage()),
                        ResponsesStreamEvent.Completed(Response(id = "response_3", endTurn = true)),
                    )

                    else -> error("Unexpected request count ${requests.size}.")
                }
            }
        }
        val state = CodexAgentState(
            client = client,
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = mcpService,
        )
        state.appendUserMessage(listOf(ContentItem.InputText("Find and use the dynamic tool.")))
        val runtime = RequestOnlyRuntime(state)
            .testToolRuntime(mcpService, NoOpToolHooks)

        runtime.resume().toList()

        assertEquals(3, requests.size)
        assertEquals(requests.first().tools, requests.last().tools)
        val history = storage.history.indexes().toList().map { index -> storage.history[index] }
        val searchOutput = assertIs<ResponseItem.ClientToolSearchOutput>(history[2])
        val namespace = assertIs<ResponsesApiNamespace>(searchOutput.tools.single())
        assertEquals("dynamic", assertIs<ResponsesApiTool>(namespace.tools.single()).name)
        assertEquals(
            FunctionCallOutputBody.Text("done"),
            assertIs<ResponseItem.FunctionCallOutput>(history[4]).output.body,
        )
    }

    test("dynamic tools refresh handlers between resumes") {
        val handledTools = mutableListOf<String>()
        val alpha = RuntimeTestTool("mcp__server", "alpha") { call ->
            handledTools += call.name
            ResponseItem.FunctionCallOutput(
                callId = call.callId,
                output = FunctionCallOutputPayload.fromText("alpha"),
            )
        }
        val beta = RuntimeTestTool("mcp__server", "beta") { call ->
            handledTools += call.name
            ResponseItem.FunctionCallOutput(
                callId = call.callId,
                output = FunctionCallOutputPayload.fromText("beta"),
            )
        }
        val mcpService = TestMcpService(listOf(alpha))
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        var requestCount = 0
        val client = mockOpenAiClient {
            createResponse {
                requestCount += 1
                when (requestCount) {
                    1 -> toolCallResponse("alpha", "call_alpha", endTurn = false)
                    2 -> assistantResponse("response_alpha")
                    3 -> toolCallResponse("beta", "call_beta", endTurn = false)
                    4 -> assistantResponse("response_beta")
                    else -> error("Unexpected request count $requestCount.")
                }
            }
        }
        val state = CodexAgentState(
            client = client,
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = mcpService,
        )
        val runtime = RequestOnlyRuntime(state)
            .testToolRuntime(mcpService, NoOpToolHooks)

        state.appendUserMessage(listOf(ContentItem.InputText("Use alpha.")))
        runtime.resume().toList()
        mcpService.tools.value = listOf(beta)
        state.appendUserMessage(listOf(ContentItem.InputText("Use beta.")))
        runtime.resume().toList()

        assertEquals(listOf("alpha", "beta"), handledTools)
    }

    test("pending calls are handled before requesting another response") {
        val mcpService = TestMcpService(listOf(RuntimeTestTool("mcp__shared", "dynamic")))
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
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
                                namespace = "mcp__shared",
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
        val state = CodexAgentState(
            client = client,
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = mcpService,
        )
        state.appendUserMessage(listOf(ContentItem.InputText("Use the tool.")))
        state.requestResponseApi().toList()

        RequestOnlyRuntime(state)
            .testToolRuntime(mcpService, NoOpToolHooks)
            .resume()
            .toList()

        assertEquals(2, requestCount)
        val history = storage.history.indexes().toList().map { index -> storage.history[index] }
        assertEquals(
            FunctionCallOutputBody.Text("done"),
            assertIs<ResponseItem.FunctionCallOutput>(history[2]).output.body,
        )
    }

    test("removed MCP routes complete with an explicit failure") {
        val mcpService = TestMcpService()
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        var requestCount = 0
        val client = mockOpenAiClient {
            createResponse {
                requestCount += 1
                when (requestCount) {
                    1 -> flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            0,
                            ResponseItem.FunctionCall(
                                name = "removed",
                                namespace = "mcp__server",
                                arguments = "{}",
                                callId = "call_removed",
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
        val state = CodexAgentState(
            client = client,
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = mcpService,
        )
        state.appendUserMessage(listOf(ContentItem.InputText("Use MCP.")))

        RequestOnlyRuntime(state)
            .testToolRuntime(mcpService, NoOpToolHooks)
            .resume()
            .toList()

        val history = storage.history.indexes().toList().map { index -> storage.history[index] }
        val output = assertIs<ResponseItem.FunctionCallOutput>(history[2])
        assertEquals(false, output.output.success)
    }

    test("tool hooks preserve handler input and observe successful output") {
        val hookInvocations = mutableListOf<HookToolInvocation>()
        val postRequests = mutableListOf<PostToolUseRequest>()
        val handledCalls = mutableListOf<ResponseItem.FunctionCall>()
        val hooks = object : ToolHooks {
            override suspend fun onPreToolUse(invocation: HookToolInvocation): PreToolUseResult {
                hookInvocations += invocation
                return PreToolUseResult.Continue
            }

            override suspend fun onPostToolUse(request: PostToolUseRequest) {
                postRequests += request
            }
        }
        val tool = RuntimeTestTool("mcp__shared", "dynamic") { call ->
            handledCalls += call
            ResponseItem.FunctionCallOutput(
                callId = call.callId,
                output = FunctionCallOutputPayload.fromText("original").copy(success = true),
            )
        }
        val call = ResponseItem.FunctionCall(
            name = "dynamic",
            namespace = "mcp__shared",
            arguments = "{\"value\":\"before\"}",
            callId = "call_hooked",
        )
        val mcpService = TestMcpService(listOf(tool))
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
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
        val state = CodexAgentState(
            client = client,
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = mcpService,
        )
        state.appendUserMessage(listOf(ContentItem.InputText("Use the tool.")))
        val runtime = RequestOnlyRuntime(state)
            .testToolRuntime(mcpService, hooks)
            .turnHookRuntime(NoOpTurnHooks)

        runtime.resume().toList()

        assertEquals("mcp__shared__dynamic", hookInvocations.single().toolName)
        assertEquals(JsonPrimitive("before"), (hookInvocations.single().input as JsonObject)["value"])
        assertEquals("{\"value\":\"before\"}", handledCalls.single().arguments)
        assertEquals(JsonPrimitive("original"), postRequests.single().response)
        val history = storage.history.indexes().toList().map { index -> storage.history[index] }
        val output = history.filterIsInstance<ResponseItem.FunctionCallOutput>().single()
        assertEquals(FunctionCallOutputBody.Text("original"), output.output.body)
        assertEquals(true, output.output.success)
    }

    test("pre tool hook block skips the handler") {
        var handlerCalls = 0
        val tool = RuntimeTestTool("mcp__shared", "dynamic") { call ->
            handlerCalls += 1
            ResponseItem.FunctionCallOutput(
                callId = call.callId,
                output = FunctionCallOutputPayload.fromText("should not run"),
            )
        }
        val hooks = object : ToolHooks {
            override suspend fun onPreToolUse(invocation: HookToolInvocation): PreToolUseResult =
                PreToolUseResult.Block("denied")

            override suspend fun onPostToolUse(request: PostToolUseRequest): Unit =
                error("PostToolUse must not run for a blocked call.")
        }
        val mcpService = TestMcpService(listOf(tool))
        val storage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        var requestCount = 0
        val client = mockOpenAiClient {
            createResponse {
                requestCount += 1
                when (requestCount) {
                    1 -> flowOf(
                        ResponsesStreamEvent.OutputItemDone(
                            0,
                            ResponseItem.FunctionCall(
                                name = "dynamic",
                                namespace = "mcp__shared",
                                arguments = "{}",
                                callId = "call_blocked",
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
        val state = CodexAgentState(
            client = client,
            storage = storage,
            contextSettings = TestAgentContextSettings,
            mcpService = mcpService,
        )
        state.appendUserMessage(listOf(ContentItem.InputText("Use the tool.")))

        RequestOnlyRuntime(state)
            .testToolRuntime(mcpService, hooks)
            .turnHookRuntime(NoOpTurnHooks)
            .resume()
            .toList()

        assertEquals(0, handlerCalls)
        val output = storage.history.indexes().toList()
            .map { index -> storage.history[index] }
            .filterIsInstance<ResponseItem.FunctionCallOutput>()
            .single()
        assertEquals(false, output.output.success)
        assertEquals(FunctionCallOutputBody.Text("denied"), output.output.body)
    }

    test("custom tool uses the latest cwd and preserves original input through hooks") {
        val invocations = mutableListOf<HookToolInvocation>()
        val hooks = RecordingToolHooks(
            pre = { invocation ->
                invocations += invocation
                PreToolUseResult.Continue
            },
        )
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
        val mcpService = TestMcpService()
        val initialSettings = CodexAgentSettings(
            model = OpenAiModelId("test-model"),
            turnId = "turn_started",
            cwd = initialRoot,
        )
        val fixture = testStateWithCalls(
            mcpService = mcpService,
            settings = initialSettings,
            call,
        )
        try {
            val runtime = RequestOnlyRuntime(fixture.state)
                .testToolRuntime(mcpService, hooks)
                .turnHookRuntime(NoOpTurnHooks)
            fixture.state.updateSettings(initialSettings.copy(cwd = updatedRoot))

            runtime.resume().toList()

            assertEquals("apply_patch", invocations.single().toolName)
            assertEquals(emptyList(), invocations.single().matcherAliases)
            assertEquals(JsonPrimitive(patch), invocations.single().input)
            assertEquals(null, SystemCoroutineFileSystem.metadataOrNull(Path(initialRoot, "hook.txt")))
            assertEquals(
                "hook input\n",
                SystemCoroutineFileSystem.readString(Path(updatedRoot, "hook.txt")),
            )
            val output = fixture.state.storage.history.indexes().toList()
                .map { index -> fixture.state.storage.history[index] }
                .filterIsInstance<ResponseItem.CustomToolCallOutput>()
                .single()
            assertEquals(true, output.output.success)
            assertEquals(FunctionCallOutputBody.Text("Success. Patch applied."), output.output.body)
        } finally {
            deleteRecursively(initialRoot)
            deleteRecursively(updatedRoot)
        }
    }

    test("exec command and write stdin run independent tool hooks") {
        val preInvocations = mutableListOf<HookToolInvocation>()
        val postRequests = mutableListOf<PostToolUseRequest>()
        val hooks = RecordingToolHooks(
            pre = { invocation ->
                preInvocations += invocation
                PreToolUseResult.Continue
            },
            post = { request ->
                postRequests += request
            },
        )
        val execCall = ResponseItem.FunctionCall(
            name = "exec_command",
            arguments = "{\"cmd\":\"sleep 1\",\"yield_time_ms\":250}",
            callId = "call_exec",
        )
        val writeCall = ResponseItem.FunctionCall(
            name = "write_stdin",
            arguments = "{\"session_id\":1,\"chars\":\"\"}",
            callId = "call_write",
        )
        val mcpService = TestMcpService()
        val fixture = testStateWithCalls(mcpService, calls = arrayOf(execCall, writeCall))

        RequestOnlyRuntime(fixture.state)
            .testToolRuntime(mcpService, hooks)
            .turnHookRuntime(NoOpTurnHooks)
            .resume()
            .toList()

        assertEquals(
            listOf("exec_command", "write_stdin"),
            preInvocations.map(HookToolInvocation::toolName),
        )
        assertEquals(
            JsonPrimitive("sleep 1"),
            (preInvocations[0].input as JsonObject)["cmd"],
        )
        assertEquals(
            JsonPrimitive(1),
            (preInvocations[1].input as JsonObject)["session_id"],
        )
        assertEquals(
            listOf("call_exec", "call_write"),
            postRequests.map { request -> request.invocation.toolUseId },
        )
        assertTrue(postRequests.all { request -> request.response is JsonPrimitive })
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
        val mcpService = TestMcpService()
        val fixture = testStateWithCalls(mcpService, calls = arrayOf(call))

        RequestOnlyRuntime(fixture.state)
            .testToolRuntime(mcpService, NoOpToolHooks)
            .resume()
            .toList()

        assertEquals(plan, fixture.state.storage.settings.latestValue().plan)
        val output = fixture.state.storage.history.indexes().toList()
            .map { index -> fixture.state.storage.history[index] }
            .filterIsInstance<ResponseItem.FunctionCallOutput>()
            .single()
        assertEquals(true, output.output.success)
        assertEquals(FunctionCallOutputBody.Text("Plan updated"), output.output.body)
    }

    test("update plan remains unavailable in plan mode") {
        val originalPlan = UpdatePlanArgs(plan = emptyList())
        val call = ResponseItem.FunctionCall(
            name = PlanTools.Name,
            arguments = OpenAiJsonCodec.encodeToString(
                UpdatePlanArgs(
                    plan = listOf(PlanItemArg("Do not store", StepStatus.Pending)),
                ),
            ),
            callId = "call_plan",
        )
        val mcpService = TestMcpService()
        val fixture = testStateWithCalls(
            mcpService = mcpService,
            settings = CodexAgentSettings(
                model = OpenAiModelId("test-model"),
                turnId = "turn_started",
                collaborationMode = ModeKind.Plan,
                plan = originalPlan,
            ),
            calls = arrayOf(call),
        )

        RequestOnlyRuntime(fixture.state)
            .testToolRuntime(mcpService, NoOpToolHooks)
            .resume()
            .toList()

        assertEquals(originalPlan, fixture.state.storage.settings.latestValue().plan)
        val output = fixture.state.storage.history.indexes().toList()
            .map { index -> fixture.state.storage.history[index] }
            .filterIsInstance<ResponseItem.FunctionCallOutput>()
            .single()
        assertEquals(false, output.output.success)
        assertTrue(
            (output.output.body as FunctionCallOutputBody.Text).text
                .contains("not allowed in Plan mode"),
        )
    }
    }
}

private class ToolRuntimeTestContext(
    scope: CoroutineScope,
) : CoroutineScope by scope {
    suspend fun CodexAgentRuntime.testToolRuntime(
        mcpService: McpService,
        hooks: ToolHooks,
    ): CodexToolRuntime {
        val workingDirectory = MutableStateFlow(storage.settings.latestValue().cwd)
        val fixedTools = buildList {
            add(ApplyPatchTools.createTool(ApplyPatchToolClient(workingDirectory)))
            add(updatePlanTool())
            addAll(
                UnifiedExecTools.createTools(
                    UnifiedExecToolClient(
                        settings = MutableStateFlow(TestShellSettings()),
                        workingDirectory = workingDirectory,
                    ),
                ),
            )
        }.map { tool ->
            object : Tool by tool {
                override suspend fun handle(
                    call: ResponseItem.ToolCall,
                ): ResponseItem.ToolCallOutput {
                    workingDirectory.value = storage.settings.latestValue().cwd
                    return tool.handle(call)
                }
            }
        }
        coroutineContext.job.invokeOnCompletion {
            fixedTools.asReversed().forEach { tool -> runCatching { tool.close() } }
        }
        val toolSearch = mcpService.tools
            .map { tools -> ToolSearchEngine(tools.toDeferredToolSearchDocuments()) }
            .stateIn(
                scope = this@ToolRuntimeTestContext,
                started = SharingStarted.Eagerly,
                initialValue = ToolSearchEngine(
                    mcpService.tools.value.toDeferredToolSearchDocuments(),
                ),
            )
        return toolRuntime(
            fixedTools = fixedTools,
            dynamicTools = mcpService.tools,
            toolSearch = toolSearch,
            toolHooks = hooks,
        )
    }
}

private class RequestOnlyRuntime(
    private val delegate: CodexAgentStateContract,
) : CodexAgentRuntime, CodexAgentStateContract by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = flow {
        emitAll(requestResponseApi())
    }
}

private data class TestShellSettings(
    override val shell: Shell = Shell.default,
) : ShellSettings

private class RuntimeTestTool(
    namespace: String,
    name: String,
    private val handler: suspend (ResponseItem.FunctionCall) -> ResponseItem.ToolCallOutput = { call ->
        ResponseItem.FunctionCallOutput(
            callId = call.callId,
            output = FunctionCallOutputPayload.fromText("done"),
        )
    },
) : McpTool {
    override val serverName: String = namespace.removePrefix("mcp__")
    override val serverInstructions: String = "Tools exposed by $serverName."
    override val spec: ToolSpec = runtimeNamespace(namespace, name)

    override suspend fun handle(call: ResponseItem.ToolCall): ResponseItem.ToolCallOutput {
        val functionCall = assertIs<ResponseItem.FunctionCall>(call)
        return handler(functionCall)
    }

    override fun close(): Unit = Unit
}

private class RecordingToolHooks(
    private val pre: suspend (HookToolInvocation) -> PreToolUseResult = {
        PreToolUseResult.Continue
    },
    private val post: suspend (PostToolUseRequest) -> Unit = {},
) : ToolHooks {
    override suspend fun onPreToolUse(invocation: HookToolInvocation): PreToolUseResult =
        pre(invocation)

    override suspend fun onPostToolUse(request: PostToolUseRequest) {
        post(request)
    }
}

private data class ToolCallTestState(
    val state: CodexAgentStateContract,
    val client: OpenAiClient,
)

private suspend fun CoroutineScope.testStateWithCalls(
    mcpService: McpService,
    settings: CodexAgentSettings = CodexAgentSettings(
        model = OpenAiModelId("test-model"),
        turnId = "turn_started",
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
    val state = CodexAgentState(
        client = client,
        storage = InMemoryCodexAgentStorage(settings),
        contextSettings = TestAgentContextSettings,
        mcpService = mcpService,
    )
    state.appendUserMessage(listOf(ContentItem.InputText("Use the tool.")))
    return ToolCallTestState(state, client)
}

private fun runtimeNamespace(namespace: String, name: String): ResponsesApiNamespace =
    ResponsesApiNamespace(
        name = namespace,
        description = "$namespace tools",
        tools = listOf(
            ResponsesApiTool(
                name = name,
                description = "$name tool",
                parameters = PropertyBuilder().obj { additionalProperties = false },
            ),
        ),
    )

private fun assistantMessage(): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText("Done.")),
    )

private fun toolCallResponse(
    name: String,
    callId: String,
    endTurn: Boolean,
): Flow<ResponsesStreamEvent> = flowOf(
    ResponsesStreamEvent.OutputItemDone(
        outputIndex = 0,
        item = ResponseItem.FunctionCall(
            name = name,
            namespace = "mcp__server",
            arguments = "{}",
            callId = callId,
        ),
    ),
    ResponsesStreamEvent.Completed(Response(id = "response_$name", endTurn = endTurn)),
)

private fun assistantResponse(responseId: String): Flow<ResponsesStreamEvent> = flowOf(
    ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = assistantMessage()),
    ResponsesStreamEvent.Completed(Response(id = responseId, endTurn = true)),
)

private suspend fun deleteRecursively(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        SystemCoroutineFileSystem.list(path).forEach { child -> deleteRecursively(child) }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}
