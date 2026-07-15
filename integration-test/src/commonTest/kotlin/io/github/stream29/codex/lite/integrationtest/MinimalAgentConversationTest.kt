package io.github.stream29.codex.lite.integrationtest

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.agentruntime.impl.CodexAgentLoopImpl
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentContextPrefixProvider
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentEnvironment
import io.github.stream29.codex.lite.agentcontext.prefix.contract.AgentsMdInstruction
import io.github.stream29.codex.lite.agentcontext.prefix.contract.EnvironmentContext
import io.github.stream29.codex.lite.agentcontext.skill.contract.AvailableSkill
import io.github.stream29.codex.lite.agentstate.contract.CodexAgentState as CodexAgentStateContract
import io.github.stream29.codex.lite.agentstate.contract.forcedCompact
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.contract.latestIndex
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MutableOpenAiSubscriptionAuthSession
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.ResponsesApiRequest
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.io.files.Path
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

private val testContextPrefixProvider: AgentContextPrefixProvider =
    object : AgentContextPrefixProvider {
        override val environmentContext: EnvironmentContext =
            EnvironmentContext(
            environments = listOf(
                AgentEnvironment(
                    id = "test",
                    cwd = Path("/workspace"),
                    shell = "bash",
                ),
            ),
            currentDate = LocalDate(2026, 7, 15),
            timeZone = TimeZone.UTC,
            )

        override val availableSkills: List<AvailableSkill> = emptyList()

        override val agentMd: List<AgentsMdInstruction> = emptyList()
    }

private val testContextInput: ResponseItem.Message =
    ResponseItem.Message(
        role = MessageRole.User,
        content = listOf(
            ContentItem.InputText(
                "<environment_context>\n" +
                    "  <cwd>/workspace</cwd>\n" +
                    "  <shell>bash</shell>\n" +
                    "  <current_date>2026-07-15</current_date>\n" +
                    "  <timezone>UTC</timezone>\n" +
                    "</environment_context>",
            ),
        ),
    )

private fun requestInput(vararg durableItems: ResponseItem): List<ResponseItem> =
    listOf(testContextInput, *durableItems)

private suspend fun OpenAiClient.collectResponseProbe(input: List<ResponseItem>): List<ResponsesStreamEvent> =
    createResponse(
        ResponsesApiRequest(
            model = testCodexModel(),
            input = input,
            store = false,
        ),
    ).toList()

private suspend fun realOpenAiClient(): RealOpenAiClient =
    RealOpenAiClient(
        authProvider = MutableOpenAiSubscriptionAuthSession(testCodexStorage().readAuth()),
        config = OpenAiClientConfig(
            clientVersion = testCodexClientVersion(),
        ),
    )

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

private suspend fun CodexAgentStateContract.appendUserMessage(text: String): Int =
    appendUserMessage(listOf(ContentItem.InputText(text)))

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
        val testAgent = CodexAgentState(
            client = testClient,
            storage = testStorage,
            contextPrefixProvider = testContextPrefixProvider,
        )
        object {
            val storage = testStorage
            val requests = testRequests
            val client = testClient
            val agent = testAgent
            val runtime = CodexAgentLoopImpl(agent)
            val user = userMessage("Answer with a short greeting.")
        }
    } closeWith {
        client.close()
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
            assertEquals(emptyList(), storage.settings[2].tools)
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
                contextPrefixProvider = testContextPrefixProvider,
            )
            val firstStory = withContext(Dispatchers.Default) {
                agent.appendUserMessage("请用中文讲一个两句以内的微型故事，只讲故事本身。")
                agent.requestResponseApi().collect()
                storage.lastAssistantMessage()
            } ?: fail("Expected the first response to contain an assistant story.")
            val continuation = withContext(Dispatchers.Default) {
                agent.appendUserMessage("请基于上一段故事继续写两句以内，不要重讲开头。")
                agent.requestResponseApi().collect()
                storage.lastAssistantMessage()
            } ?: fail("Expected the second response to contain a continuation.")

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
                contextPrefixProvider = testContextPrefixProvider,
            )

            val compactIndex = withContext(Dispatchers.Default) {
                agent.forcedCompact()
            }

            val checkpoint = storage.compaction[compactIndex]
            assertEquals(1, client.remoteCompactionV2Requests.size)
            val request = client.remoteCompactionV2Requests.single()
            assertEquals(model, request.request.model)
            assertTrue(
                request.request.input.last() == ResponseItem.CompactionTrigger,
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
            val events = withContext(Dispatchers.Default) {
                client.collectResponseProbe(
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
}

val openAiCompactionItemProbeTest by testSuite {
    testFixture { realOpenAiClient() } asParameterForEach {
        test(
            "real normal response does not emit compaction item",
            testConfig = TestConfig.testScope(isEnabled = true, timeout = 180.seconds),
        ) { client ->
            val marker = "NORMAL_RESPONSE_WITHOUT_COMPACTION_ITEM"
            val events = withContext(Dispatchers.Default) {
                client.collectResponseProbe(
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
    }
}
