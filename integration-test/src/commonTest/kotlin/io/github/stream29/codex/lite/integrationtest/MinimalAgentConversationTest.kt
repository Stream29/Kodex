package io.github.stream29.codex.lite.integrationtest

import io.github.stream29.codex.lite.agentstate.impl.CodexAgentStateImpl
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.CompactionCheckpoint
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.CompactionInput
import io.github.stream29.codex.lite.openai.CompactionResponse
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MutableOpenAiSubscriptionAuthSession
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResponseResult
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Request
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.UpdatePlanArgs
import io.github.stream29.codex.lite.openai.client.contract.OpenAiClient
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.client.OpenAiClient as RealOpenAiClient
import io.github.stream29.codex.lite.openai.client.OpenAiClientConfig
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorageException
import io.github.stream29.codex.lite.openai.codexclistorage.defaultCodexDirectory
import io.github.stream29.codex.lite.utils.osenvironment.environmentVariable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MinimalAgentConversationTest {
    @Test
    fun conversationLoopPersistsHistoryInStorage() = runTest {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(OpenAiModelId("test-model")))
        val requests = mutableListOf<ResponsesApiRequest>()
        val client = scriptedConversationClient(requests)
        val agent = CodexAgentStateImpl(
            client = client,
            storage = storage,
        )
        val user = userMessage("Answer with a short greeting.")

        try {
            agent.appendResponseItem(user, Instant.fromEpochSeconds(0), tokenCount = null)
            agent.resume().collect()
            assertEquals(1, requests.size)
            agent.resume().collect()
        } finally {
            client.close()
        }

        assertEquals("Hello from the storage-backed loop.", storage.lastAssistantMessage())
        assertEquals(3, storage.latestIndex())
        assertEquals(emptyList(), requests[0].tools)
        assertEquals(emptyList(), requests[1].tools)
        assertEquals(
            listOf(user),
            requests[0].input,
        )
        assertEquals(
            listOf(
                user,
                assistantMessage("Preparing the greeting."),
            ),
            requests[1].input,
        )
        assertIs<ResponseItem.Message>(storage.history[1])
        assertIs<ResponseItem.Message>(storage.history[2])
        assertIs<ResponseItem.Message>(storage.history[3])
        assertEquals(OpenAiModelId("test-model"), storage.settings[2].model)
        assertEquals(emptyList(), storage.settings[2].tools)
        assertEquals(0, storage.compaction[3].historyBaseIndex)
        assertTrue(storage.timestamp[3] > Instant.fromEpochSeconds(0))
        assertEquals(-1, storage.tokenCount.latestIndex())
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
}

class OpenAiStoryContinuationProbeTest {
    @Test
    fun realClientContinuesStoryFromStorage() = runTest(timeout = 180.seconds) {
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(CodexAgentSettings(model = testCodexModel()))
        val client = RecordingOpenAiClient(
            RealOpenAiClient(
                authProvider = MutableOpenAiSubscriptionAuthSession(testCodexStorage().readAuth()),
                config = OpenAiClientConfig(
                    clientVersion = testCodexClientVersion(),
                ),
            ),
        )
        val agent = CodexAgentStateImpl(
            client = client,
            storage = storage,
        )

        val firstStory: String
        val continuation: String
        try {
            firstStory = withContext(Dispatchers.Default) {
                agent.appendUserMessage(storage, "请用中文讲一个两句以内的微型故事，只讲故事本身。")
                agent.resume().collect()
                storage.lastAssistantMessage()
            } ?: fail("Expected the first response to contain an assistant story.")

            continuation = withContext(Dispatchers.Default) {
                agent.appendUserMessage(storage, "请基于上一段故事继续写两句以内，不要重讲开头。")
                agent.resume().collect()
                storage.lastAssistantMessage()
            } ?: fail("Expected the second response to contain a continuation.")
        } finally {
            client.close()
        }

        println("story probe first response: $firstStory")
        println("story probe continuation: $continuation")

        assertEquals(2, client.requests.size)
        assertEquals(emptyList(), client.requests[0].tools)
        assertEquals(emptyList(), client.requests[1].tools)
        assertTrue(firstStory.isNotBlank(), "Expected a non-empty first story.")
        assertTrue(continuation.isNotBlank(), "Expected a non-empty continuation.")
        assertNotEquals(firstStory, continuation, "Expected the continuation to add new text.")
        assertTrue(storage.latestIndex() >= 3, "Expected both turns to be persisted.")
        assertTrue(
            client.requests[1].input.any { item ->
                item is ResponseItem.Message &&
                    item.role == MessageRole.Assistant &&
                    item.text() == firstStory
            },
            "Expected the second request to include the first assistant story from storage.",
        )
    }
}

class OpenAiForcedCompactProbeTest {
    @Test
    fun realClientForcedCompactInstallsServerCompactionOutput() = runTest(timeout = 180.seconds) {
        val model = testCodexModel()
        val storage = InMemoryCodexAgentStorage()
        storage.initialize(
            CodexAgentSettings(
                model = model,
                instructions = "Summarize the conversation into a compact continuation context.",
                promptCacheKey = "codex-lite-forced-compact-probe",
            ),
        )
        val client = RecordingOpenAiClient(
            RealOpenAiClient(
                authProvider = MutableOpenAiSubscriptionAuthSession(testCodexStorage().readAuth()),
                config = OpenAiClientConfig(
                    clientVersion = testCodexClientVersion(),
                ),
            ),
        )
        val agent = CodexAgentStateImpl(
            client = client,
            storage = storage,
        )

        val compactIndex: Int
        try {
            compactIndex = withContext(Dispatchers.Default) {
                agent.appendResponseItem(
                    userMessage(
                        "请记住：项目代号是 Cedar，目标是把 Kotlin agent state 的上下文压缩链路跑通。",
                    ),
                    Instant.fromEpochSeconds(0),
                    tokenCount = null,
                )
                agent.appendResponseItem(
                    assistantMessage("已记录 Cedar 项目的目标。"),
                    Instant.fromEpochSeconds(1),
                    tokenCount = null,
                )
                agent.forcedCompact()
            }
        } finally {
            client.close()
        }

        val checkpoint = storage.compaction[compactIndex]
        assertEquals(1, client.remoteCompactionV2Requests.size)
        assertEquals(model, client.remoteCompactionV2Requests.single().settings.model)
        assertTrue(
            client.remoteCompactionV2Requests.single().history.none { item -> item == ResponseItem.CompactionTrigger },
            "Remote compaction v2 client request should receive history before wire trigger projection.",
        )
        assertTrue(checkpoint.prefix.isNotEmpty(), "Expected server compaction output to become checkpoint prefix.")
        assertEquals(compactIndex + 1, checkpoint.historyBaseIndex)
        assertIs<ResponseItem.ContextCompaction>(storage.history[compactIndex])
        assertEquals(compactIndex, storage.latestIndex())
    }
}

class OpenAiModelInputProjectionProbeTest {
    @Test
    fun realClientAcceptsEmptyReasoningInputItem() = runTest(timeout = 180.seconds) {
        val marker = "EMPTY_REASONING_INPUT_ACCEPTED"
        val events = withContext(Dispatchers.Default) {
            runRealResponseProbe(
                input = listOf(
                    ResponseItem.Reasoning(summary = emptyList()),
                    userMessage("Reply with exactly this marker and nothing else: $marker"),
                ),
            )
        }
        val outputText = events.assistantText()

        println("empty reasoning input probe output: $outputText")

        events.completedResponseOrFail("empty reasoning input")
        assertTrue(
            outputText.contains(marker),
            "Expected empty reasoning input probe output to contain $marker.",
        )
    }

}

class OpenAiCompactionItemProbeTest {
    @Test
    fun realNormalResponseDoesNotEmitCompactionItem() = runTest(timeout = 180.seconds) {
        val marker = "NORMAL_RESPONSE_WITHOUT_COMPACTION_ITEM"
        val events = withContext(Dispatchers.Default) {
            runRealResponseProbe(
                input = listOf(
                    userMessage("Reply with exactly this marker and nothing else: $marker"),
                ),
            )
        }
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

    @Test
    fun realResponsesCompactionV2EmitsCompactionItem() = runTest(timeout = 180.seconds) {
        val metadata = compactionV2TurnMetadata()
        val events = withContext(Dispatchers.Default) {
            runRealResponseProbe(
                request = ResponsesApiRequest(
                    model = testCodexModel(),
                    input = listOf(
                        userMessage("请记住：Kotlin 探针正在确认 remote compaction v2 的输出形状。"),
                        assistantMessage("已记录这个探针目标。"),
                        ResponseItem.CompactionTrigger,
                    ),
                    instructions = "Summarize the conversation into a compact continuation context.",
                    store = false,
                    clientMetadata = compactionV2ClientMetadata(metadata),
                ),
                config = OpenAiClientConfig(
                    clientVersion = testCodexClientVersion(),
                    defaultHeaders = compactionV2Headers(metadata),
                ),
            )
        }
        val outputItems = events.outputItems()
        val compactionItems = outputItems.filterIsInstance<ResponseItem.CompactionItem>()

        println("compaction v2 output item types: ${outputItems.typeNames()}")
        println(
            "compaction v2 encrypted content lengths: " +
                compactionItems.map { item -> item.encryptedContentOrNull()?.length },
        )

        events.completedResponseOrFail("responses compaction v2")
        assertEquals(
            1,
            compactionItems.size,
            "Remote compaction v2 should emit exactly one compaction item. Output items: $outputItems",
        )
        assertTrue(
            compactionItems.single().encryptedContentOrNull()?.isNotBlank() == true,
            "Remote compaction v2 compaction item should carry encrypted content. Output items: $outputItems",
        )
    }
}

private class RecordingOpenAiClient(
    private val delegate: OpenAiClient,
) : OpenAiClient by delegate {
    val requests: MutableList<ResponsesApiRequest> = mutableListOf()
    val compactionRequests: MutableList<CompactionInput> = mutableListOf()
    val remoteCompactionV2Requests: MutableList<RemoteCompactionV2Request> = mutableListOf()

    override suspend fun createResponse(
        request: ResponsesApiRequest,
        extraHeaders: Map<String, String>,
    ): Flow<ResponsesStreamEvent> {
        requests += request
        return delegate.createResponse(request, extraHeaders)
    }

    override suspend fun compactResponse(request: CompactionInput): OpenAiResponseResult<CompactionResponse> {
        compactionRequests += request
        return delegate.compactResponse(request)
    }

    override suspend fun createRemoteCompactionV2Response(
        request: RemoteCompactionV2Request,
    ): RemoteCompactionV2Response {
        remoteCompactionV2Requests += request
        return delegate.createRemoteCompactionV2Response(request)
    }
}

private suspend fun runRealResponseProbe(input: List<ResponseItem>): List<ResponsesStreamEvent> =
    runRealResponseProbe(
        request = ResponsesApiRequest(
            model = testCodexModel(),
            input = input,
            store = false,
        ),
        config = OpenAiClientConfig(
            clientVersion = testCodexClientVersion(),
        ),
    )

private suspend fun runRealResponseProbe(
    request: ResponsesApiRequest,
    config: OpenAiClientConfig,
): List<ResponsesStreamEvent> {
    val client = RealOpenAiClient(
        authProvider = MutableOpenAiSubscriptionAuthSession(testCodexStorage().readAuth()),
        config = config,
    )
    val events = mutableListOf<ResponsesStreamEvent>()
    try {
        client.createResponse(request).collect { event ->
            events += event
        }
    } finally {
        client.close()
    }
    return events
}

private fun compactionV2Headers(metadata: String): Map<String, String> =
    mapOf(
        "x-codex-beta-features" to "remote_compaction_v2",
        "x-codex-installation-id" to CompactionProbeInstallationId,
        "x-codex-turn-metadata" to metadata,
        "x-codex-window-id" to CompactionProbeWindowId,
    )

private fun compactionV2ClientMetadata(metadata: String): Map<String, String> =
    mapOf(
        "x-codex-installation-id" to CompactionProbeInstallationId,
        "session_id" to CompactionProbeSessionId,
        "thread_id" to CompactionProbeThreadId,
        "turn_id" to CompactionProbeTurnId,
        "x-codex-window-id" to CompactionProbeWindowId,
        "x-codex-turn-metadata" to metadata,
    )

private fun compactionV2TurnMetadata(): String =
    """
    {
      "installation_id":"$CompactionProbeInstallationId",
      "session_id":"$CompactionProbeSessionId",
      "thread_id":"$CompactionProbeThreadId",
      "turn_id":"$CompactionProbeTurnId",
      "window_id":"$CompactionProbeWindowId",
      "request_kind":"compaction",
      "compaction":{
        "trigger":"manual",
        "reason":"user_requested",
        "implementation":"responses_compaction_v2",
        "phase":"standalone_turn",
        "strategy":"memento"
      }
    }
    """.trimIndent().lineSequence().joinToString(separator = "") { line -> line.trim() }

private const val CompactionProbeInstallationId: String = "codex-lite-compaction-probe-installation"
private const val CompactionProbeSessionId: String = "codex-lite-compaction-probe-session"
private const val CompactionProbeThreadId: String = "codex-lite-compaction-probe-thread"
private const val CompactionProbeTurnId: String = "codex-lite-compaction-probe-turn"
private const val CompactionProbeWindowId: String = "codex-lite-compaction-probe-thread:1"

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
        is ResponseItem.ToolSearchCall -> "tool_search_call"
        is ResponseItem.FunctionCallOutput -> "function_call_output"
        is ResponseItem.McpToolCallOutput -> "mcp_tool_call_output"
        is ResponseItem.CustomToolCall -> "custom_tool_call"
        is ResponseItem.CustomToolCallOutput -> "custom_tool_call_output"
        is ResponseItem.ToolSearchOutput -> "tool_search_output"
        is ResponseItem.WebSearchCall -> "web_search_call"
        is ResponseItem.ImageGenerationCall -> "image_generation_call"
        is ResponseItem.Compaction -> "compaction"
        is ResponseItem.CompactionSummary -> "compaction_summary"
        ResponseItem.CompactionTrigger -> "compaction_trigger"
        is ResponseItem.ContextCompaction -> "context_compaction"
        ResponseItem.Other -> "other"
    }

private fun ResponseItem.CompactionItem.encryptedContentOrNull(): String? =
    when (this) {
        is ResponseItem.Compaction -> encryptedContent
        is ResponseItem.CompactionSummary -> encryptedContent
        is ResponseItem.ContextCompaction -> encryptedContent
    }

private fun testCodexDirectory(): Path {
    val explicitCodexHome = environmentVariable("CODEX_HOME")?.takeIf(String::isNotBlank)
    if (explicitCodexHome != null) {
        return Path(explicitCodexHome)
    }
    return defaultCodexDirectory()
        ?: throw IllegalStateException("CODEX_HOME or a readable user home directory must be set for real OpenAI integration tests.")
}

private fun testCodexStorage(): CodexCliStorage =
    CodexCliStorage(testCodexDirectory())

private suspend fun testCodexClientVersion(): String =
    testCodexStorage().readModelsCache().clientVersion
        ?.takeIf { it.matches(Regex("""\d+\.\d+\.\d+""")) }
        ?: "0.1.0"

private suspend fun testCodexModel(): OpenAiModelId {
    val storage = testCodexStorage()
    val configuredModel = try {
        storage.readConfigToml()
            .lineSequence()
            .firstOrNull { it.trimStart().startsWith("model = ") }
            ?.substringAfter("=")
            ?.trim()
            ?.removeSurrounding("\"")
            ?.takeIf(String::isNotBlank)
    } catch (_: CodexCliStorageException) {
        null
    }
    val cachedModels = storage.readModelsCache()
        .models
        .mapNotNull { model -> model.slug?.takeIf(String::isNotBlank) }
    return OpenAiModelId(
        configuredModel
            ?: cachedModels.firstOrNull { it.contains("codex", ignoreCase = true) }
            ?: cachedModels.firstOrNull()
            ?: fail("Codex CLI models_cache.json must contain at least one model.")
    )
}

private suspend fun CodexAgentStateImpl.appendUserMessage(
    storage: InMemoryCodexAgentStorage,
    text: String,
): Int {
    val index = storage.latestIndex() + 1
    return appendResponseItem(
        item = userMessage(text),
        timestamp = Instant.fromEpochSeconds(index.toLong()),
        tokenCount = null,
    )
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

private suspend fun InMemoryCodexAgentStorage.initialize(settings: CodexAgentSettings) {
    this.settings[0] = settings
    this.compaction[0] = CompactionCheckpoint(
        prefix = emptyList(),
        historyBaseIndex = 0,
        windowNumber = 0,
        firstWindowId = "window-0",
        windowId = "window-0",
    )
    this.plan[0] = UpdatePlanArgs(plan = emptyList())
}

private fun userMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
        content = listOf(ContentItem.InputText(text)),
    )

private fun assistantMessage(text: String): ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.Assistant,
        content = listOf(ContentItem.OutputText(text)),
    )

private fun ResponseItem.Message.text(): String =
    content.joinToString(separator = "") { item ->
        when (item) {
            is ContentItem.InputText -> item.text
            is ContentItem.OutputText -> item.text
            is ContentItem.InputImage -> ""
        }
    }
