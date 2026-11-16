@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.integrationtest

import io.github.stream29.kodex.utils.shellclient.default

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.stream29.kodex.agentruntime.decorator.compact.compactionRuntime
import io.github.stream29.kodex.agentruntime.contract.AgentRuntime
import io.github.stream29.kodex.agentruntime.impl.buildMasterAgentRuntime
import io.github.stream29.kodex.agentsession.contract.KodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentState as KodexAgentStateContract
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstate.contract.forcedCompact
import io.github.stream29.kodex.agentstate.impl.KodexAgentState
import io.github.stream29.kodex.agentstate.test.TestAgentContextSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableCleanEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getCommitExact
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.agentstorage.contract.getSettingsAt
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.FunctionCallOutputBody
import io.github.stream29.kodex.openai.FunctionCallOutputContentItem
import io.github.stream29.kodex.openai.ImageDetail
import io.github.stream29.kodex.openai.OpenAiSubscriptionAuthState
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.OpenAiResult
import io.github.stream29.kodex.openai.ModelsResponse
import io.github.stream29.kodex.openai.Reasoning
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseInclude
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.RemoteCompactionV2Response
import io.github.stream29.kodex.openai.ResponsesApiRequest
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.ServiceTier
import io.github.stream29.kodex.openai.TextControls
import io.github.stream29.kodex.openai.ToolSpec
import io.github.stream29.kodex.openai.ToolChoice
import io.github.stream29.kodex.openai.client.contract.OpenAiClient
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseHeaders
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiClient as RealOpenAiClient
import io.github.stream29.kodex.openai.client.OpenAiClientConfig
import io.github.stream29.kodex.openai.client.test.InMemoryOpenAiAuthStore
import io.github.stream29.kodex.openai.codexclistorage.CodexCliStorage
import io.github.stream29.kodex.openai.codexclistorage.CodexAuthJson
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.kodex.openai.modelcatalog.OpenAiModelCatalogStore
import io.github.stream29.kodex.tool.readmedia.ReadMediaToolArguments
import io.github.stream29.kodex.tool.readmedia.ReadMediaTools
import io.github.stream29.kodex.utils.kotlinxiocoroutines.SystemCoroutineFileSystem
import io.github.stream29.kodex.utils.osenvironment.environmentVariable
import io.github.stream29.kodex.utils.osenvironment.userHomeDirectory
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.shellclient.Shell
import io.github.stream29.kodex.utils.shellclient.ShellSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.assertEquals
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
        model: OpenAiModelId,
        input: List<ResponseItem>,
        instructions: String,
        store: Boolean,
        previousResponseId: String?,
        tools: List<ToolSpec>,
        toolChoice: ToolChoice,
        parallelToolCalls: Boolean,
        reasoning: Reasoning,
        include: Set<ResponseInclude>,
        serviceTier: ServiceTier,
        promptCacheKey: String?,
        text: TextControls,
        installationId: String?,
        sessionId: String?,
        threadId: String?,
        turnId: String?,
        windowId: String?,
        threadSource: String?,
        turnTrigger: String?,
        turnState: String?,
        onResponseHeaders: suspend (OpenAiResponseHeaders) -> Unit,
    ): Flow<ResponsesStreamEvent> {
        requests += RecordedCodexResponse(
            ResponsesApiRequest(
                model = model,
                input = input,
                instructions = instructions,
                store = store,
                previousResponseId = previousResponseId,
                tools = tools,
                toolChoice = toolChoice,
                parallelToolCalls = parallelToolCalls,
                reasoning = reasoning,
                include = include,
                serviceTier = serviceTier,
                promptCacheKey = promptCacheKey,
                text = text,
            ),
        )
        return delegate.createResponse(
            model = model,
            input = input,
            instructions = instructions,
            store = store,
            previousResponseId = previousResponseId,
            tools = tools,
            toolChoice = toolChoice,
            parallelToolCalls = parallelToolCalls,
            reasoning = reasoning,
            include = include,
            serviceTier = serviceTier,
            promptCacheKey = promptCacheKey,
            text = text,
            installationId = installationId,
            sessionId = sessionId,
            threadId = threadId,
            turnId = turnId,
            windowId = windowId,
            threadSource = threadSource,
            turnTrigger = turnTrigger,
            turnState = turnState,
            onResponseHeaders = onResponseHeaders,
        )
    }

    override suspend fun createRemoteCompactionV2Response(
        request: ResponsesApiRequest,
    ): RemoteCompactionV2Response {
        remoteCompactionV2Requests += RecordedCodexResponse(request)
        return delegate.createRemoteCompactionV2Response(request)
    }
}

private data class RecordedCodexResponse(
    val request: ResponsesApiRequest,
)

internal fun KodexAgentStateContract.integrationResumableAgent(
    client: OpenAiClient,
    modelCatalog: OpenAiModelCatalogStore,
): AgentRuntime =
    buildMasterAgentRuntime(
        dependencies = KodexAgentDependencies(
            client = client,
            modelCatalog = modelCatalog,
            contextSettings = TestAgentContextSettings,
            shellSettings = MutableStateFlow(IntegrationShellSettings),
        ),
    )

private data object IntegrationShellSettings : ShellSettings {
    override val shell: Shell = Shell.default
}

private suspend fun OpenAiClient.collectResponseProbe(input: List<ResponseItem>): List<ResponsesStreamEvent> =
    createResponse(
        model = testOpenAiModel(),
        input = input,
        store = false,
    ).toList()

internal suspend fun realOpenAiClient(): RealOpenAiClient =
    RealOpenAiClient(
        authStore = InMemoryOpenAiAuthStore(
            testAuthStorage().readAuthOrNull().toSubscriptionAuthStateOrThrow(),
        ),
        config = OpenAiClientConfig(),
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

private fun testAuthStorage(): CodexCliStorage =
    CodexCliStorage(testCodexDirectory())

internal fun testOpenAiModel(): OpenAiModelId = OpenAiModelId("gpt-5.6-sol")

private suspend fun KodexAgentStateContract.appendUserMessage(text: String): Int {
    return appendUserMessage(listOf(ContentItem.InputText(text)))
}

private suspend fun InMemoryKodexAgentStorage.lastAssistantMessage(): String? {
    var message: String? = null
    for ((_, event) in getCommitsIn(0..latestIndex(), AgentCommitType.IndexTypes)) {
        if (event is StableAssistantMessage) {
            message = event.text
        }
    }
    return message
}

private suspend fun InMemoryKodexAgentStorage.historyItems(): List<ResponseItem.HistoryItem> =
    getCommitsIn(0..latestIndex(), AgentCommitType.CleanEventTypes)
        .flatMap { (it.commit as StableCleanEvent).toResponseHistoryItems() }

private fun userMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
        content = listOf(ContentItem.InputText(text)),
    )

internal fun testModelCatalog(): OpenAiModelCatalogStore =
    OpenAiModelCatalog(
        client = mockOpenAiClient {
            listModels { OpenAiResult.Success(ModelsResponse()) }
        },
    )

private fun assistantMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
    )

private const val ReadMediaProbeFileName: String = "runtime-probe.png"
private const val ReadMediaProbeMarker: String = "READ_MEDIA_RUNTIME_COMPLETED"
private const val ReadMediaProbePngBase64: String =
    "iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAAgklEQVR4Xu3QoRHDABADQeNgY+MUkf6rcC82XxKskcASgZ/5Oz7n9TQ7HNosgEObBXBoswAObf4GuH/faP6jBXCQB9P4jxbAQR5M4z9aAAd5MI3/aAEc5ME0/qMFcJAH0/iPFsBBHkzjP1oAB3kwjf9oARzaLIBDmwVwaLMADm3qA7y8LuS12WzThwAAAABJRU5ErkJggg=="

private suspend fun createReadMediaProbeRoot(): Path {
    val root = Path(
        "build/tmp/read-media-agent-runtime-${Random.nextLong().toString().replace('-', '0')}",
    )
    SystemCoroutineFileSystem.createDirectories(root)
    SystemCoroutineFileSystem.writeBytes(
        Path(root, ReadMediaProbeFileName),
        Base64.decode(ReadMediaProbePngBase64),
    )
    return root
}

private suspend fun deleteReadMediaProbeRoot(path: Path) {
    val metadata = SystemCoroutineFileSystem.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        for (child in SystemCoroutineFileSystem.list(path)) {
            deleteReadMediaProbeRoot(child)
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
        createResponse { request ->
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
        val testStorage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test-model")))
        val testRequests = mutableListOf<ResponsesApiRequest>()
        val testClient = scriptedConversationClient(testRequests)
        val testAgent = testSuiteCoroutineScope.KodexAgentState(
            client = testClient,
            storage = testStorage,
            contextSettings = TestAgentContextSettings,
        )
        object {
            val storage = testStorage
            val requests = testRequests
            val client = testClient
            val agent = testAgent
            val runtime = agent.compactionRuntime(
                modelCatalog = testModelCatalog(),
                logger = KotlinLogging.logger {},
            )
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
            runtime.resume()

            assertEquals("Hello from the storage-backed loop.", storage.lastAssistantMessage())
            assertEquals(3, storage.latestIndex())
            assertEquals(user, requests[0].input.last())
            assertEquals(
                listOf(
                    user,
                    assistantMessage("Preparing the greeting."),
                ),
                requests[1].input.takeLast(2),
            )
            assertIs<StableUserMessage>(storage.getCommitExact(1, AgentCommitType.IndexTypes)?.commit)
            assertIs<StableAssistantMessage>(storage.getCommitExact(2, AgentCommitType.IndexTypes)?.commit)
            assertIs<StableAssistantMessage>(storage.getCommitExact(3, AgentCommitType.IndexTypes)?.commit)
            assertEquals(OpenAiModelId("test-model"), checkNotNull(storage.getSettingsAt(2)).model)
            assertTrue(checkNotNull(storage.getCommitExact(3)?.createdAt) > Instant.fromEpochSeconds(0))
            assertEquals(0, storage.getCommitAtOrBefore(storage.latestIndex(), AgentCommitType.LlmRequestMetaTypes)?.index)
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
            val storage = InMemoryKodexAgentStorage(KodexAgentSettings(model = testOpenAiModel()))
            val agent = KodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
            )
            agent.appendUserMessage("请用中文讲一个两句以内的微型故事，只讲故事本身。")
            agent.requestResponseApi()
            val firstStory = storage.lastAssistantMessage()
                ?: fail("Expected the first response to contain an assistant story.")
            agent.appendUserMessage("请基于上一段故事继续写两句以内，不要重讲开头。")
            agent.requestResponseApi()
            val continuation = storage.lastAssistantMessage()
                ?: fail("Expected the second response to contain a continuation.")

            println("story probe first response: $firstStory")
            println("story probe continuation: $continuation")

            assertEquals(2, client.requests.size)
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

val openAiReadMediaToolRuntimeProbeTest by testSuite {
    testFixture {
        val root = createReadMediaProbeRoot()
        val client = RecordingOpenAiClient(realOpenAiClient())
        object {
            val imageRoot = root
            val openAiClient = client
        }
    } closeWith {
        try {
            openAiClient.close()
        } finally {
            deleteReadMediaProbeRoot(imageRoot)
        }
    } asContextForEach {
        test(
            "real tool runtime invokes static read_media and reads a local image",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { testScope ->
            val modelCatalog = testModelCatalog()
            var state: KodexAgentStateContract? = null
            try {
                val storage = InMemoryKodexAgentStorage(
                    KodexAgentSettings(
                        model = testOpenAiModel(),
                        cwd = imageRoot,
                    ),
                )
                val createdState = testScope.KodexAgentState(
                    client = openAiClient,
                    storage = storage,
                    contextSettings = TestAgentContextSettings,
                )
                state = createdState
                val runtime = createdState.integrationResumableAgent(openAiClient, modelCatalog)

                createdState.appendUserMessage(
                    "Use the available media-reading tool named `read_media` to inspect the local " +
                        "image at relative path `$ReadMediaProbeFileName`. You must read the image " +
                        "with that tool before answering. Then reply with exactly $ReadMediaProbeMarker.",
                )
                withContext(Dispatchers.Default) {
                    runtime.resume()
                }

                val history = storage.historyItems()
                val readMediaCall = history
                    .filterIsInstance<ResponseItem.FunctionCall>()
                    .single { call -> call.namespace == null && call.name == ReadMediaTools.Name }
                val arguments = OpenAiJsonCodec.decodeFromString(
                    ReadMediaToolArguments.serializer(),
                    readMediaCall.arguments,
                )
                assertEquals(ReadMediaProbeFileName, arguments.path)

                val output = history
                    .filterIsInstance<ResponseItem.FunctionCallOutput>()
                    .single { item -> item.callId == readMediaCall.callId }
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
                    storage.lastAssistantMessage().orEmpty().contains(ReadMediaProbeMarker),
                    "Expected a final assistant response after the image tool result.",
                )
                assertIs<KodexAgentStateValue.AssistantMessage>(createdState.state.value)
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

val openAiForcedCompactProbeTest by testSuite {
    testFixture {
        RecordingOpenAiClient(realOpenAiClient())
    } asParameterForEach {
        test(
            "real client forced compact installs server compaction output",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val model = testOpenAiModel()
            val storage = InMemoryKodexAgentStorage(
                KodexAgentSettings(
                    model = model,
                    instructions = "Summarize the conversation into a compact continuation context.",
                    promptCacheKey = "kodex-forced-compact-probe",
                ),
            )
            storage.commit(LlmRequestMeta.Initialization(0L), Instant.DISTANT_PAST)
            storage.commit(
                StableUserMessage(
                    userMessage(
                        "请记住：项目代号是 Cedar，目标是把 Kotlin agent state 的上下文压缩链路跑通。",
                    ).content,
                ),
            )
            storage.commit(
                StableAssistantMessage(
                    assistantMessage("已记录 Cedar 项目的目标。").content,
                ),
            )
            val agent = KodexAgentState(
                client = client,
                storage = storage,
                contextSettings = TestAgentContextSettings,
            )

            val compactIndex = agent.forcedCompact()

            assertEquals(1, client.remoteCompactionV2Requests.size)
            val request = client.remoteCompactionV2Requests.single()
            assertEquals(model, request.request.model)
            assertEquals(
                ResponseItem.CompactionTrigger,
                request.request.input.last(),
                "AgentState should project the remote-compaction trigger into the wire request.",
            )
            val contextCompaction =
                assertIs<StableContextCompaction>(storage.getCommitExact(compactIndex, AgentCommitType.IndexTypes)?.commit)
            assertTrue(contextCompaction.encryptedContent.isNotEmpty())
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
                model = testOpenAiModel(),
                input = listOf(
                    userMessage(
                        "Search the web for the current official Kotlin release, then reply with its version.",
                    ),
                ),
                tools = listOf(ToolSpec.WebSearch(externalWebAccess = true)),
                toolChoice = ToolChoice.Required,
                store = false,
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
