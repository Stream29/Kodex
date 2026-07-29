package io.github.stream29.codex.lite.integrationtest

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.agentruntime.compact.compactionRuntime
import io.github.stream29.codex.lite.agentruntime.contract.ResumableAgent
import io.github.stream29.codex.lite.agentsession.contract.AgentPathResolver
import io.github.stream29.codex.lite.agentsession.composition.CodexAgentDependencies
import io.github.stream29.codex.lite.agentsession.composition.buildAgentRuntime
import io.github.stream29.codex.lite.agentcontext.prefix.render.render as renderCollaborationMode
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentStateValue
import io.github.stream29.codex.lite.agentstate.contract.forcedCompact
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestAgentContextSettings
import io.github.stream29.codex.lite.agentstate.test.TestMcpService
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.contract.latestValue
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.cli.auth.InMemoryCodexAuthStore
import io.github.stream29.codex.lite.hook.contract.NoOpCodexHooks
import io.github.stream29.codex.lite.mcp.contract.McpService
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.FunctionCallOutputContentItem
import io.github.stream29.codex.lite.openai.FunctionCallOutputPayload
import io.github.stream29.codex.lite.openai.ImageDetail
import io.github.stream29.codex.lite.openai.OpenAiSubscriptionAuthState
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ModeKind
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.SearchCommands
import io.github.stream29.codex.lite.openai.ToolSpec
import io.github.stream29.codex.lite.openai.ToolChoice
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.client.OpenAiClient as RealOpenAiClient
import io.github.stream29.codex.lite.openai.client.OpenAiClientConfig
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.codexclistorage.CodexAuthJson
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenNamespace
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenToolArguments
import io.github.stream29.codex.lite.tool.imagegeneration.ImageGenToolName
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.codex.lite.tool.requestuserinput.RequestUserInputTools
import io.github.stream29.codex.lite.tool.viewimage.ViewImageToolArguments
import io.github.stream29.codex.lite.tool.viewimage.ViewImageTools
import io.github.stream29.codex.lite.tool.webrun.WebRunNamespace
import io.github.stream29.codex.lite.tool.webrun.WebRunToolName
import io.github.stream29.codex.lite.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import io.github.stream29.codex.lite.utils.osenvironment.userHomeDirectory
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import io.github.stream29.codex.lite.utils.shellclient.Shell
import io.github.stream29.codex.lite.utils.shellclient.ShellSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private class RecordingOpenAiClient(
    private val delegate: OpenAiClient,
) : OpenAiClient by delegate {
    val requests: MutableList<RecordedCodexResponse> = mutableListOf()
    val remoteCompactionV2Requests: MutableList<RecordedCodexResponse> = mutableListOf()

    override suspend fun createResponse(
        request: ResponsesApiRequest,
        installationId: String?,
        turnMetadata: String,
        windowId: String,
    ): Flow<ResponsesStreamEvent> {
        requests += RecordedCodexResponse(request, installationId, turnMetadata, windowId)
        return delegate.createResponse(request, installationId, turnMetadata, windowId)
    }

    override suspend fun createRemoteCompactionV2Response(
        request: ResponsesApiRequest,
        installationId: String?,
        turnMetadata: String,
        windowId: String,
    ): RemoteCompactionV2Response {
        remoteCompactionV2Requests += RecordedCodexResponse(request, installationId, turnMetadata, windowId)
        return delegate.createRemoteCompactionV2Response(request, installationId, turnMetadata, windowId)
    }
}

private data class RecordedCodexResponse(
    val request: ResponsesApiRequest,
    val installationId: String?,
    val turnMetadata: String,
    val windowId: String,
)

private class RequestOnlyRuntime(
    private val delegate: CodexAgentStateContract,
) : ResumableAgent, CodexAgentStateContract by delegate {
    override fun resume(): Flow<ResponsesStreamEvent> = flow {
        emitAll(requestResponseApi())
    }
}

internal fun CodexAgentStateContract.integrationResumableAgent(
    client: OpenAiClient,
    modelCatalog: OpenAiModelCatalog,
    mcpService: McpService,
): ResumableAgent =
    buildAgentRuntime(
        dependencies = CodexAgentDependencies(
            client = client,
            modelCatalog = modelCatalog,
            contextSettings = TestAgentContextSettings,
            shellSettings = MutableStateFlow(IntegrationShellSettings),
            mcpService = mcpService,
            hooks = NoOpCodexHooks,
        ),
        agentPathResolver = AgentPathResolver { null },
    )

private data object IntegrationShellSettings : ShellSettings {
    override val shell: Shell = Shell.default
}

private val defaultCollaborationInput: ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Developer,
        content = listOf(ContentItem.InputText(ModeKind.Default.renderCollaborationMode())),
    )

private fun requestInput(vararg durableItems: ResponseItem): List<ResponseItem> =
    listOf(defaultCollaborationInput, *durableItems)

private suspend fun OpenAiClient.collectResponseProbe(input: List<ResponseItem>): List<ResponsesStreamEvent> =
    createResponse(
        ResponsesApiRequest(
            model = testCodexModel(),
            input = input,
            store = false,
        ),
    ).toList()

internal suspend fun realOpenAiClient(): RealOpenAiClient =
    RealOpenAiClient(
        authStore = InMemoryCodexAuthStore(
            testCodexStorage().readAuthOrNull().toSubscriptionAuthStateOrThrow(),
        ),
        config = OpenAiClientConfig(
            clientVersion = testCodexClientVersion(),
        ),
    )

private fun CodexAuthJson?.toSubscriptionAuthStateOrThrow(): OpenAiSubscriptionAuthState {
    val tokens = this?.tokens ?: error("Codex CLI auth tokens are required.")
    return OpenAiSubscriptionAuthState(
        accessToken = tokens.accessToken,
        accountId = tokens.accountId?.takeIf(String::isNotBlank),
    )
}

private fun List<ResponsesStreamEvent>.completedResponseOrFail(probeName: String): Response {
    filterIsInstance<ResponsesStreamEvent.Failed>().firstOrNull()?.let { event ->
        fail("$probeName failed: ${event.response.error?.message ?: event.response}")
    }
    filterIsInstance<ResponsesStreamEvent.Incomplete>().firstOrNull()?.let { event ->
        fail("$probeName was incomplete: ${event.response}")
    }
    return filterIsInstance<ResponsesStreamEvent.Completed>().lastOrNull()?.response
        ?: fail("$probeName did not emit response.completed. Events: $this")
}

private fun List<ResponsesStreamEvent>.assistantText(): String =
    filterIsInstance<ResponsesStreamEvent.OutputItemDone>()
        .mapNotNull { event -> event.item as? ResponseItem.Message }
        .filter { message -> message.role == MessageRole.Assistant }
        .joinToString(separator = "") { message -> message.text() }

private fun List<ResponsesStreamEvent>.outputItems(): List<ResponseItem> =
    filterIsInstance<ResponsesStreamEvent.OutputItemDone>()
        .map { event -> event.item }

private fun List<ResponseItem>.typeNames(): List<String> =
    map { item -> item.typeName() }

private fun ResponseItem.typeName(): String =
    when (this) {
        is ResponseItem.AdditionalTools -> "additional_tools"
        is ResponseItem.Message -> "message"
        is ResponseItem.AgentMessage -> "agent_message"
        is ResponseItem.Reasoning -> "reasoning"
        is ResponseItem.LocalShellCall -> "local_shell_call"
        is ResponseItem.FunctionCall -> "function_call"
        is ResponseItem.ClientToolSearchCall,
        is ResponseItem.ServerToolSearchCall,
        -> "tool_search_call"
        is ResponseItem.FunctionCallOutput -> "function_call_output"
        is ResponseItem.McpToolCallOutput -> "mcp_tool_call_output"
        is ResponseItem.CustomToolCall -> "custom_tool_call"
        is ResponseItem.CustomToolCallOutput -> "custom_tool_call_output"
        is ResponseItem.ClientToolSearchOutput,
        is ResponseItem.ServerToolSearchOutput,
        -> "tool_search_output"
        is ResponseItem.WebSearchCall -> "web_search_call"
        is ResponseItem.ImageGenerationCall -> "image_generation_call"
        is ResponseItem.Compaction -> "compaction"
        is ResponseItem.CompactionSummary -> "compaction_summary"
        ResponseItem.CompactionTrigger -> "compaction_trigger"
        is ResponseItem.ContextCompaction -> "context_compaction"
        ResponseItem.Other -> "other"
    }

private fun testCodexDirectory(): Path {
    val explicitCodexHome = environmentVariable("CODEX_HOME")?.takeIf(String::isNotBlank)
    if (explicitCodexHome != null) {
        return Path(explicitCodexHome)
    }
    return userHomeDirectory()?.let { home -> Path(home, ".codex") }
        ?: throw IllegalStateException("CODEX_HOME or a readable user home directory must be set for real OpenAI integration tests.")
}

private fun testCodexStorage(): CodexCliStorage =
    CodexCliStorage(testCodexDirectory())

private suspend fun testCodexClientVersion(): String =
    testCodexStorage().readModelsCacheOrNull()?.clientVersion
        ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
        ?: "0.1.0"

internal suspend fun testCodexModel(): OpenAiModelId {
    val storage = testCodexStorage()
    val configuredModel = storage.readConfigTomlOrNull()?.model
    val cachedModels = storage.readModelsCacheOrNull()
        ?.models
        .orEmpty()
        .map { model -> model.slug.value }
    return OpenAiModelId(
        configuredModel
            ?: cachedModels.firstOrNull { it.contains("codex", ignoreCase = true) }
            ?: cachedModels.firstOrNull()
            ?: fail("Codex CLI models_cache.json must contain at least one model.")
    )
}

private suspend fun CodexAgentStateContract.appendUserMessage(text: String): Int {
    markNewTurn()
    return appendUserMessage(listOf(ContentItem.InputText(text)))
}

private suspend fun InMemoryCodexAgentStorage.lastAssistantMessage(): String? {
    var message: String? = null
    history.indexes().collect { index ->
        val item = history[index]
        if (item is ResponseItem.Message && item.role == MessageRole.Assistant) {
            message = item.text()
        }
    }
    return message
}

private suspend fun InMemoryCodexAgentStorage.historyItems(): List<ResponseItem.HistoryItem> =
    history.indexes().toList().map { index -> history[index] }

private fun userMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
        content = listOf(ContentItem.InputText(text)),
    )

internal fun testModelCatalog(): OpenAiModelCatalog =
    OpenAiModelCatalog(
        client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
        },
        codexCliStorage = CodexCliStorage(Path(".codex-lite-test-model-catalog")),
    )

private fun assistantMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
    )

private const val ViewImageProbeFileName: String = "runtime-probe.png"
private const val ViewImageProbeMarker: String = "VIEW_IMAGE_RUNTIME_COMPLETED"
private const val ViewImageProbePngBase64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAgklEQVR4Xu3QoRHDABADQeNgY+MUkf6rcC82XxKskcASgZ/5Oz7n9TQ7HNosgEObBXBoswAObf4GuH/faP6jBXCQB9P4jxbAQR5M4z9aAAd5MI3/aAEc5ME0/qMFcJAH0/iPFsBBHkzjP1oAB3kwjf9oARzaLIBDmwVwaLMADm3qA7y8LuS12WzThwAAAABJRU5ErkJggg=="
private const val ImageGenerationProbeMarker: String = "IMAGE_GENERATION_RUNTIME_COMPLETED"
private const val WebRunProbeMarker: String = "WEB_RUN_RUNTIME_COMPLETED"
private const val RequestUserInputProbeMarker: String = "REQUEST_USER_INPUT_RUNTIME_COMPLETED"

private suspend fun createViewImageProbeRoot(): Path {
    val root = Path(
        "build/tmp/view-image-agent-runtime-${Random.nextLong().toString().replace('-', '0')}",
    )
    SystemCoroutineFileSystem.createDirectories(root)
    SystemCoroutineFileSystem.writeBytes(
        Path(root, ViewImageProbeFileName),
        Base64.decode(ViewImageProbePngBase64),
    )
    return root
}

private suspend fun deleteViewImageProbeRoot(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        for (child in SystemCoroutineFileSystem.list(path)) {
            deleteViewImageProbeRoot(child)
        }
    }
    SystemCoroutineFileSystem.delete(path, mustExist = false)
}

private fun ResponseItem.Message.text(): String =
    content.joinToString(separator = "") { item ->
        when (item) {
            is ContentItem.InputText -> item.text
            is ContentItem.OutputText -> item.text
            is ContentItem.InputImage -> ""
        }
    }

private fun scriptedConversationClient(
    requests: MutableList<ResponsesApiRequest>,
): OpenAiClient =
    mockOpenAiClient {
        createResponse { request, _, _, _ ->
            requests += request

            when (requests.size) {
                1 -> flowOf(
                    ResponsesStreamEvent.OutputItemDone(
                        outputIndex = 0,
                        item = assistantMessage("Preparing the greeting."),
                    ),
                    ResponsesStreamEvent.Completed(
                        response = Response(
                            id = "response_1",
                            endTurn = false,
                        ),
                    ),
                )

                2 -> flowOf(
                    ResponsesStreamEvent.OutputItemDone(
                        outputIndex = 0,
                        item = assistantMessage("Hello from the storage-backed loop."),
                    ),
                    ResponsesStreamEvent.Completed(
                        response = Response(
                            id = "response_2",
                            endTurn = true,
                        ),
                    ),
                )

                else -> fail("Unexpected extra sampling request.")
            }
        }
    }

val minimalAgentConversationTest by testSuite {
    testFixture {
        val testStorage = InMemoryCodexAgentStorage(CodexAgentSettings(OpenAiModelId("test-model")))
        val testRequests = mutableListOf<ResponsesApiRequest>()
        val testClient = scriptedConversationClient(testRequests)
        val testAgent = testSuiteCoroutineScope.CodexAgentState(
            client = testClient,
            storage = testStorage,
            contextSettings = TestAgentContextSettings,
            mcpService = TestMcpService(),
        )
        object {
            val storage = testStorage
            val requests = testRequests
            val client = testClient
            val agent = testAgent
            val runtime = agent.compactionRuntime(testModelCatalog())
            val user = userMessage("Answer with a short greeting.")
        }
    } closeWith {
        try {
            agent.cancelAndJoin()
        } finally {
            client.close()
        }
    } asContextForEach {
        test("conversation loop persists history in storage") {
            agent.appendUserMessage(user.content)
            runtime.resume().collect()

            assertEquals("Hello from the storage-backed loop.", storage.lastAssistantMessage())
            assertEquals(3, storage.latestIndex())
            assertEquals(emptyList(), requests[0].tools)
            assertEquals(emptyList(), requests[1].tools)
            assertEquals(
                requestInput(user),
                requests[0].input,
            )
            assertEquals(
                requestInput(
                    user,
                    assistantMessage("Preparing the greeting."),
                ),
                requests[1].input,
            )
            assertIs<ResponseItem.Message>(storage.history[1])
            assertIs<ResponseItem.Message>(storage.history[2])
            assertIs<ResponseItem.Message>(storage.history[3])
            assertEquals(OpenAiModelId("test-model"), storage.settings[2].model)
            assertEquals(0, storage.compaction[3].historyBaseIndex)
            assertTrue(storage.timestamp[3] > Instant.fromEpochSeconds(0))
            assertEquals(-1, storage.tokenCount.latestIndex())
        }
    }
}

val openAiStoryContinuationProbeTest by testSuite {
    testFixture {
        RecordingOpenAiClient(realOpenAiClient())
    } asParameterForEach {
        test(
            "real client continues story from storage",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val storage = InMemoryCodexAgentStorage(CodexAgentSettings(model = testCodexModel()))
            val agent = CodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
                mcpService = TestMcpService(),
            )
            agent.appendUserMessage("请用中文讲一个两句以内的微型故事，只讲故事本身。")
            agent.requestResponseApi().collect()
            val firstStory = storage.lastAssistantMessage()
                ?: fail("Expected the first response to contain an assistant story.")
            agent.appendUserMessage("请基于上一段故事继续写两句以内，不要重讲开头。")
            agent.requestResponseApi().collect()
            val continuation = storage.lastAssistantMessage()
                ?: fail("Expected the second response to contain a continuation.")

            println("story probe first response: $firstStory")
            println("story probe continuation: $continuation")

            assertEquals(2, client.requests.size)
            assertEquals(emptyList(), client.requests[0].request.tools)
            assertEquals(emptyList(), client.requests[1].request.tools)
            assertTrue(firstStory.isNotBlank(), "Expected a non-empty first story.")
            assertTrue(continuation.isNotBlank(), "Expected a non-empty continuation.")
            assertNotEquals(firstStory, continuation, "Expected the continuation to add new text.")
            assertTrue(storage.latestIndex() >= 3, "Expected both turns to be persisted.")
            assertTrue(
                client.requests[1].request.input.any { item ->
                    item is ResponseItem.Message &&
                        item.role == MessageRole.Assistant &&
                        item.text() == firstStory
                },
                "Expected the second request to include the first assistant story from storage.",
            )
        }
    }
}

val openAiCurrentTimeToolRoundTripProbeTest by testSuite {
    testFixture {
        RecordingOpenAiClient(realOpenAiClient())
    } asParameterForEach {
        test(
            "real runtime replays a namespaced current-time tool round trip",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val modelCatalog = testModelCatalog()
            val mcpService = TestMcpService()
            val storage = InMemoryCodexAgentStorage(
                CodexAgentSettings(
                    model = testCodexModel(),
                    instructions =
                        "When the user asks you to inspect the system time, call " +
                            "clock.curr_time exactly once before answering.",
                ),
            )
            val state = CodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
                mcpService = mcpService,
            )
            try {
                val runtime = state.integrationResumableAgent(client, modelCatalog, mcpService)

                listOf(
                    "hello",
                    "今天的日期是？",
                    "你看看系统时间",
                ).forEach { message ->
                    state.appendUserMessage(message)
                    runtime.resume().collect()
                }

                val history = storage.historyItems()
                assertTrue(
                    history.any { item ->
                        item is ResponseItem.FunctionCall &&
                            item.namespace == "clock" &&
                            item.name == "curr_time"
                    },
                    "Expected the model to call clock.curr_time.",
                )
                assertTrue(
                    history.any { item -> item is ResponseItem.FunctionCallOutput },
                    "Expected the current-time result to be persisted before the follow-up request.",
                )
                assertIs<CodexAgentStateValue.AssistantMessage>(state.state.value)
                assertTrue(
                    client.requests.size >= 4,
                    "Expected the tool result to trigger a follow-up Responses request.",
                )
            } finally {
                try {
                    state.cancelAndJoin()
                } finally {
                    modelCatalog.close()
                }
            }
        }
    }
}

val openAiViewImageToolRuntimeProbeTest by testSuite {
    testFixture {
        val root = createViewImageProbeRoot()
        val client = RecordingOpenAiClient(realOpenAiClient())
        object {
            val imageRoot = root
            val openAiClient = client
        }
    } closeWith {
        try {
            openAiClient.close()
        } finally {
            deleteViewImageProbeRoot(imageRoot)
        }
    } asContextForEach {
        test(
            "real tool runtime discovers view_image and reads a local image",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { testScope ->
            val mcpService = TestMcpService()
            val modelCatalog = testModelCatalog()
            var state: CodexAgentStateContract? = null
            try {
                val storage = InMemoryCodexAgentStorage(
                    CodexAgentSettings(
                        model = testCodexModel(),
                        cwd = imageRoot,
                    ),
                )
                val createdState = testScope.CodexAgentState(
                    client = openAiClient,
                    storage = storage,
                    contextSettings = TestAgentContextSettings,
                    mcpService = mcpService,
                )
                state = createdState
                val runtime = createdState.integrationResumableAgent(openAiClient, modelCatalog, mcpService)

                createdState.appendUserMessage(
                    "Use the available image-viewing tool named `view_image` to inspect the local " +
                        "image at relative path `$ViewImageProbeFileName`. You must read the image " +
                        "with that tool before answering. Then reply with exactly $ViewImageProbeMarker.",
                )
                withContext(Dispatchers.Default) {
                    runtime.resume().collect()
                }

                val history = storage.historyItems()
                assertTrue(
                    history.any { item -> item is ResponseItem.ClientToolSearchCall },
                    "Expected the model to discover the deferred view_image tool.",
                )
                val viewImageCall = history
                    .filterIsInstance<ResponseItem.FunctionCall>()
                    .single { call -> call.namespace == null && call.name == ViewImageTools.Name }
                val arguments = OpenAiJsonCodec.decodeFromString(
                    ViewImageToolArguments.serializer(),
                    viewImageCall.arguments,
                )
                assertEquals(ViewImageProbeFileName, arguments.path)

                val output = history
                    .filterIsInstance<ResponseItem.FunctionCallOutput>()
                    .single { item -> item.callId == viewImageCall.callId }
                assertEquals(true, output.output.success)
                val body = assertIs<FunctionCallOutputBody.ContentItems>(output.output.body)
                val image = assertIs<FunctionCallOutputContentItem.InputImage>(body.items.single())
                assertTrue(image.imageUrl.startsWith("data:image/png;base64,"))
                assertEquals(ImageDetail.High, image.detail)
                assertTrue(
                    openAiClient.requests.any { request -> output in request.request.input },
                    "Expected the image output to be sent back to the model.",
                )
                assertTrue(
                    storage.lastAssistantMessage().orEmpty().contains(ViewImageProbeMarker),
                    "Expected a final assistant response after the image tool result.",
                )
                assertIs<CodexAgentStateValue.AssistantMessage>(createdState.state.value)
            } finally {
                try {
                    state?.cancelAndJoin()
                } finally {
                    modelCatalog.close()
                }
            }
        }
    }
}

val openAiImageGenerationToolRuntimeProbeTest by testSuite {
    testFixture {
        RecordingOpenAiClient(realOpenAiClient())
    } closeWith {
        close()
    } asParameterForEach {
        test(
            "real tool runtime discovers image_gen and generates an image",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 300.seconds),
        ) { client ->
            val mcpService = TestMcpService()
            val modelCatalog = testModelCatalog()
            val storage = InMemoryCodexAgentStorage(
                CodexAgentSettings(model = testCodexModel()),
            )
            val state = CodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
                mcpService = mcpService,
            )
            try {
                val runtime = state.integrationResumableAgent(client, modelCatalog, mcpService)

                state.appendUserMessage(
                    "Use `image_gen.imagegen` to generate a new minimal image of one black circle " +
                        "on a plain white background. Do not use referenced images. After the generated " +
                        "image is returned, reply with exactly $ImageGenerationProbeMarker.",
                )
                withContext(Dispatchers.Default) {
                    runtime.resume().collect()
                }

                val history = storage.historyItems()
                assertTrue(
                    history.any { item -> item is ResponseItem.ClientToolSearchCall },
                    "Expected the model to discover the deferred image_gen tool.",
                )
                val call = history
                    .filterIsInstance<ResponseItem.FunctionCall>()
                    .single { item ->
                        item.namespace == ImageGenNamespace && item.name == ImageGenToolName
                    }
                val arguments = OpenAiJsonCodec.decodeFromString(
                    ImageGenToolArguments.serializer(),
                    call.arguments,
                )
                assertTrue(arguments.prompt.isNotBlank())
                assertEquals(null, arguments.referencedImagePaths)
                assertEquals(null, arguments.numLastImagesToInclude)

                val output = history
                    .filterIsInstance<ResponseItem.FunctionCallOutput>()
                    .single { item -> item.callId == call.callId }
                assertEquals(true, output.output.success)
                val body = assertIs<FunctionCallOutputBody.ContentItems>(output.output.body)
                val image = assertIs<FunctionCallOutputContentItem.InputImage>(body.items.first())
                val prefix = "data:image/png;base64,"
                assertTrue(image.imageUrl.startsWith(prefix))
                assertTrue(image.imageUrl.length > prefix.length)
                assertEquals(ImageDetail.High, image.detail)
                assertTrue(
                    client.requests.any { request -> output in request.request.input },
                    "Expected the generated image output to be sent back to the model.",
                )
                assertTrue(
                    storage.lastAssistantMessage().orEmpty().contains(ImageGenerationProbeMarker),
                    "Expected a final assistant response after image generation.",
                )
                assertIs<CodexAgentStateValue.AssistantMessage>(state.state.value)
            } finally {
                try {
                    state.cancelAndJoin()
                } finally {
                    modelCatalog.close()
                }
            }
        }
    }
}

val openAiWebRunToolRuntimeProbeTest by testSuite {
    testFixture {
        RecordingOpenAiClient(realOpenAiClient())
    } closeWith {
        close()
    } asParameterForEach {
        test(
            "real tool runtime executes web.run search",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val mcpService = TestMcpService()
            val modelCatalog = testModelCatalog()
            val storage = InMemoryCodexAgentStorage(
                CodexAgentSettings(model = testCodexModel()),
            )
            val state = CodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
                mcpService = mcpService,
            )
            try {
                val runtime = state.integrationResumableAgent(client, modelCatalog, mcpService)

                state.appendUserMessage(
                    "Use `web.run` with one search_query operation to search for the official OpenAI " +
                        "Codex page. After the tool result is returned, reply with exactly $WebRunProbeMarker.",
                )
                withContext(Dispatchers.Default) {
                    runtime.resume().collect()
                }

                val history = storage.historyItems()
                val call = history
                    .filterIsInstance<ResponseItem.FunctionCall>()
                    .single { item ->
                        item.namespace == WebRunNamespace && item.name == WebRunToolName
                    }
                val commands = OpenAiJsonCodec.decodeFromString(
                    SearchCommands.serializer(),
                    call.arguments,
                )
                assertTrue(commands.searchQuery.orEmpty().isNotEmpty())

                val output = history
                    .filterIsInstance<ResponseItem.FunctionCallOutput>()
                    .single { item -> item.callId == call.callId }
                assertEquals(true, output.output.success)
                assertTrue(assertIs<FunctionCallOutputBody.Text>(output.output.body).text.isNotBlank())
                assertTrue(
                    client.requests.any { request -> output in request.request.input },
                    "Expected the web.run output to be sent back to the model.",
                )
                assertTrue(
                    storage.lastAssistantMessage().orEmpty().contains(WebRunProbeMarker),
                    "Expected a final assistant response after web.run.",
                )
                assertIs<CodexAgentStateValue.AssistantMessage>(state.state.value)
            } finally {
                try {
                    state.cancelAndJoin()
                } finally {
                    modelCatalog.close()
                }
            }
        }
    }
}

val openAiRequestUserInputProbeTest by testSuite {
    testFixture {
        RecordingOpenAiClient(realOpenAiClient())
    } closeWith {
        close()
    } asParameterForEach {
        test(
            "real request_user_input pauses for and consumes a host answer",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val storage = InMemoryCodexAgentStorage(
                CodexAgentSettings(model = testCodexModel()),
            )
            val state = CodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
                mcpService = TestMcpService(),
            )
            val runtime = RequestOnlyRuntime(state)

            state.appendUserMessage(
                "Before answering, use `request_user_input` to ask whether this integration test " +
                    "should continue. Ask exactly one question with Yes and No options. After the host " +
                    "answers Yes, reply with exactly $RequestUserInputProbeMarker.",
            )
            withContext(Dispatchers.Default) {
                runtime.resume().collect()
            }

            val pending = assertIs<CodexAgentStateValue.ToolPending>(state.state.value)
            val pendingCall = assertIs<ResponseItem.FunctionCall>(pending.calls.single())
            assertEquals(RequestUserInputTools.Name, pendingCall.name)
            val arguments = OpenAiJsonCodec.decodeFromString(
                RequestUserInputArgs.serializer(),
                pendingCall.arguments,
            )
            val question = arguments.questions.single()
            assertTrue(question.options.orEmpty().size >= 2)

            val response = RequestUserInputResponse(
                answers = mapOf(
                    question.id to RequestUserInputAnswer(listOf("Yes")),
                ),
            )
            state.completeToolCall(
                ResponseItem.FunctionCallOutput(
                    callId = pendingCall.callId,
                    output = FunctionCallOutputPayload.fromText(
                        OpenAiJsonCodec.encodeToString(
                            RequestUserInputResponse.serializer(),
                            response,
                        ),
                    ).copy(success = true),
                ),
            )
            assertEquals(CodexAgentStateValue.ToolCompleted, state.state.value)
            withContext(Dispatchers.Default) {
                runtime.resume().collect()
            }

            val history = storage.historyItems()
            val recordedCall = history
                .filterIsInstance<ResponseItem.FunctionCall>()
                .single { item -> item.namespace == null && item.name == "request_user_input" }
            assertEquals(pendingCall.callId, recordedCall.callId)
            val output = history
                .filterIsInstance<ResponseItem.FunctionCallOutput>()
                .single { item -> item.callId == recordedCall.callId }
            assertEquals(true, output.output.success)
            val outputText = assertIs<FunctionCallOutputBody.Text>(output.output.body).text
            assertEquals(
                response,
                OpenAiJsonCodec.decodeFromString(RequestUserInputResponse.serializer(), outputText),
            )
            assertTrue(
                client.requests.any { request -> output in request.request.input },
                "Expected the host answer to be sent back to the model.",
            )
            assertTrue(
                storage.lastAssistantMessage().orEmpty().contains(RequestUserInputProbeMarker),
                "Expected a final assistant response after request_user_input.",
            )
            assertIs<CodexAgentStateValue.AssistantMessage>(state.state.value)
        }
    }
}

val openAiForcedCompactProbeTest by testSuite {
    testFixture {
        RecordingOpenAiClient(realOpenAiClient())
    } asParameterForEach {
        test(
            "real client forced compact installs server compaction output",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val model = testCodexModel()
            val storage = InMemoryCodexAgentStorage(
                CodexAgentSettings(
                    model = model,
                    instructions = "Summarize the conversation into a compact continuation context.",
                    promptCacheKey = "codex-lite-forced-compact-probe",
                ),
            )
            storage.history[1] = userMessage(
                "请记住：项目代号是 Cedar，目标是把 Kotlin agent state 的上下文压缩链路跑通。",
            )
            storage.history[2] = assistantMessage("已记录 Cedar 项目的目标。")
            val agent = CodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
                mcpService = TestMcpService(),
            )

            val compactIndex = agent.forcedCompact()

            val checkpoint = storage.compaction[compactIndex]
            assertEquals(1, client.remoteCompactionV2Requests.size)
            val request = client.remoteCompactionV2Requests.single()
            assertEquals(model, request.request.model)
            assertEquals(
                ResponseItem.CompactionTrigger,
                request.request.input.last(),
                "AgentState should project the remote-compaction trigger into the wire request.",
            )
            assertTrue(checkpoint.prefix.isNotEmpty(), "Expected server compaction output to become checkpoint prefix.")
            assertEquals(compactIndex + 1, checkpoint.historyBaseIndex)
            assertIs<ResponseItem.ContextCompaction>(storage.history[compactIndex])
            assertEquals(compactIndex, storage.latestIndex())
        }
    }
}

val openAiModelInputProjectionProbeTest by testSuite {
    testFixture { realOpenAiClient() } asParameterForEach {
        test(
            "real client accepts empty reasoning input item",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val marker = "EMPTY_REASONING_INPUT_ACCEPTED"
            val events = client.collectResponseProbe(
                input = listOf(
                    ResponseItem.Reasoning(summary = emptyList()),
                    userMessage("Reply with exactly this marker and nothing else: $marker"),
                ),
            )
            val outputText = events.assistantText()

            println("empty reasoning input probe output: $outputText")

            events.completedResponseOrFail("empty reasoning input")
            assertTrue(
                outputText.contains(marker),
                "Expected empty reasoning input probe output to contain $marker.",
            )
        }
    }
}

val openAiCompactionItemProbeTest by testSuite {
    testFixture { realOpenAiClient() } asParameterForEach {
        test(
            "real normal response does not emit compaction item",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val marker = "NORMAL_RESPONSE_WITHOUT_COMPACTION_ITEM"
            val events = client.collectResponseProbe(
                input = listOf(
                    userMessage("Reply with exactly this marker and nothing else: $marker"),
                ),
            )
            val outputItems = events.outputItems()

            println("normal response output item types: ${outputItems.typeNames()}")
            println("normal response output text: ${events.assistantText()}")

            events.completedResponseOrFail("normal response")
            assertTrue(
                outputItems.none { item -> item is ResponseItem.CompactionItem },
                "Normal response must not emit compaction items. Output items: $outputItems",
            )
            assertTrue(
                events.assistantText().contains(marker),
                "Expected normal response output to contain $marker.",
            )
        }
    }
}

val openAiHostedWebSearchProbeTest by testSuite {
    testFixture { realOpenAiClient() } asParameterForEach {
        test(
            "real client executes hosted web search",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val events = client.createResponse(
                ResponsesApiRequest(
                    model = testCodexModel(),
                    input = listOf(
                        userMessage(
                            "Search the web for the current official Kotlin release, then reply with its version.",
                        ),
                    ),
                    tools = listOf(ToolSpec.WebSearch(externalWebAccess = true)),
                    toolChoice = ToolChoice.Required,
                    store = false,
                ),
            ).toList()
            val outputItems = events.outputItems()

            println("hosted web search output item types: ${outputItems.typeNames()}")
            println("hosted web search output text: ${events.assistantText()}")

            events.completedResponseOrFail("hosted web search")
            assertTrue(
                outputItems.any { item -> item is ResponseItem.WebSearchCall },
                "Expected hosted search to emit web_search_call. Output items: $outputItems",
            )
            assertTrue(
                events.any { event -> event is ResponsesStreamEvent.WebSearchCallInProgress },
                "Expected hosted search to emit its in-progress stream event.",
            )
            assertTrue(
                events.any { event -> event is ResponsesStreamEvent.WebSearchCallSearching },
                "Expected hosted search to emit its searching stream event.",
            )
            assertTrue(
                events.any { event -> event is ResponsesStreamEvent.WebSearchCallCompleted },
                "Expected hosted search to emit its completed stream event.",
            )
            assertTrue(
                events.assistantText().isNotBlank(),
                "Expected hosted search to produce an assistant response.",
            )
        }
    }
}
