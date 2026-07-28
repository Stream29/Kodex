package io.github.stream29.codex.lite.agentruntime.sessionhook

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.agentruntime.compact.compactionRuntime
import io.github.stream29.codex.lite.agentruntime.turnhook.turnHookRuntime
import io.github.stream29.codex.lite.agentstate.impl.CodexAgentState
import io.github.stream29.codex.lite.agentstate.test.TestContextPrefixProvider
import io.github.stream29.codex.lite.agentstorage.contract.MutableCodexAgentStorage
import io.github.stream29.codex.lite.agentstorage.contract.indexes
import io.github.stream29.codex.lite.agentstorage.inmemory.InMemoryCodexAgentStorage
import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHookRequest
import io.github.stream29.codex.lite.hook.contract.compaction.CompactionHooks
import io.github.stream29.codex.lite.hook.contract.session.SessionEndRequest
import io.github.stream29.codex.lite.hook.contract.session.SessionLifecycleHooks
import io.github.stream29.codex.lite.hook.contract.session.SessionStartRequest
import io.github.stream29.codex.lite.hook.contract.turn.StopRequest
import io.github.stream29.codex.lite.hook.contract.turn.StopResult
import io.github.stream29.codex.lite.hook.contract.turn.TurnHooks
import io.github.stream29.codex.lite.hook.contract.turn.UserPromptSubmitRequest
import io.github.stream29.codex.lite.hook.contract.turn.UserPromptSubmitResult
import io.github.stream29.codex.lite.openai.CodexAgentSettings
import io.github.stream29.codex.lite.openai.ContentItem
import io.github.stream29.codex.lite.openai.MessageRole
import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiModelId
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.github.stream29.codex.lite.openai.RemoteCompactionV2Response
import io.github.stream29.codex.lite.openai.Response
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.ResponsesStreamEvent
import io.github.stream29.codex.lite.openai.client.test.mockOpenAiClient
import io.github.stream29.codex.lite.openai.codexclistorage.CodexCliStorage
import io.github.stream29.codex.lite.openai.modelcatalog.OpenAiModelCatalog
import io.github.stream29.codex.lite.tool.toolsearch.ToolSearchTools
import io.github.stream29.codex.lite.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.io.files.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs

val sessionHooksTest by testSuite {
    test("session start runs before user prompt without changing history") {
        val order = mutableListOf<String>()
        val lifecycle = RecordingSessionHooks(
            start = {
                order += "session"
            },
        )
        val storage = InMemoryCodexAgentStorage(testSettings())
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, assistantMessage("done")),
                        ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)),
                    )
                }
            },
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        val runtime = state
            .compactionRuntime(testModelCatalog())
            .turnHookRuntime(
                hooks = RecordingTurnHooks(
                    onUserPrompt = {
                        order += "user"
                        UserPromptSubmitResult.Continue()
                    },
                ),
            )
        runtime.installSessionHooks(lifecycle)

        assertEquals(listOf("session"), order)
        runtime.appendUserMessage(listOf(ContentItem.InputText("hello")))
        runtime.resume().toList()

        assertEquals(listOf("session", "user"), order)
        val history = storage.history.indexes().toList().map { index -> storage.history[index] }
        assertEquals(listOf(MessageRole.User, MessageRole.Assistant), history.map {
            assertIs<ResponseItem.Message>(it).role
        })
        runtime.cancelAndJoin()
    }

    test("compaction does not create another session start") {
        val order = mutableListOf<String>()
        val lifecycle = RecordingSessionHooks(
            start = {
                order += "session"
            },
        )
        val storage = InMemoryCodexAgentStorage(
            testSettings().copy(autoCompactionTokenLimit = 90),
        )
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createRemoteCompactionV2Response { _, _, _, _ ->
                    RemoteCompactionV2Response(
                        compactionOutput = ResponseItem.Compaction(encryptedContent = "encrypted"),
                        completedResponse = null,
                    )
                }
                createResponse {
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, assistantMessage("done")),
                        ResponsesStreamEvent.Completed(Response(id = "response", endTurn = true)),
                    )
                }
            },
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        val runtime = state
            .compactionRuntime(
                modelCatalog = testModelCatalog(),
                compactionHooks = RecordingCompactionHooks(
                    before = { order += "pre-compact" },
                    after = { order += "post-compact" },
                ),
            )
            .turnHookRuntime(
                hooks = RecordingTurnHooks(
                    onUserPrompt = {
                        order += "user"
                        UserPromptSubmitResult.Continue()
                    },
                ),
            )
        runtime.installSessionHooks(lifecycle)
        val userMessageIndex = state.appendUserMessage(listOf(ContentItem.InputText("next")))
        (storage as MutableCodexAgentStorage).tokenCount[userMessageIndex] = 90

        runtime.resume().toList()

        assertEquals(
            listOf("session", "user", "pre-compact", "post-compact"),
            order,
        )
        runtime.cancelAndJoin()
    }

    test("session hooks follow explicit runtime lifecycle") {
        var startCount = 0
        var endCount = 0
        var responseCount = 0
        val startRequests = mutableListOf<SessionStartRequest>()
        val endRequests = mutableListOf<SessionEndRequest>()
        val lifecycle = RecordingSessionHooks(
            start = { request ->
                startCount += 1
                startRequests += request
            },
            end = { request ->
                endCount += 1
                endRequests += request
            },
        )
        val initialSettings = testSettings().copy(cwd = Path("/initial"))
        val storage = InMemoryCodexAgentStorage(initialSettings)
        val state = CodexAgentState(
            client = mockOpenAiClient {
                createResponse {
                    responseCount += 1
                    flowOf(
                        ResponsesStreamEvent.OutputItemDone(0, assistantMessage("done $responseCount")),
                        ResponsesStreamEvent.Completed(Response(id = "response-$responseCount", endTurn = true)),
                    )
                }
            },
            storage = storage,
            contextPrefixProvider = TestContextPrefixProvider,
            toolSearchToolSpec = { ToolSearchTools.createToolSearchSpec() },
        )
        val runtime = state.compactionRuntime(testModelCatalog())
        runtime.installSessionHooks(lifecycle)

        assertEquals(1, startCount)
        assertEquals(0, responseCount)
        runtime.appendUserMessage(listOf(ContentItem.InputText("first")))
        runtime.resume().toList()
        runtime.appendUserMessage(listOf(ContentItem.InputText("second")))
        runtime.resume().toList()
        state.updateSettings(
            initialSettings.copy(
                model = OpenAiModelId("updated-model"),
                cwd = Path("/updated"),
            ),
        )
        runtime.cancelAndJoin()

        assertEquals(1, startCount)
        assertEquals(1, endCount)
        assertEquals(2, responseCount)
        assertEquals(storage.id, startRequests.single().context.sessionId)
        assertEquals("test-model", startRequests.single().context.model)
        assertEquals(Path("/initial"), startRequests.single().context.cwd)
        assertEquals(storage.id, endRequests.single().context.sessionId)
        assertEquals("updated-model", endRequests.single().context.model)
        assertEquals(Path("/updated"), endRequests.single().context.cwd)
    }
}

private class RecordingSessionHooks(
    private val start: suspend (SessionStartRequest) -> Unit = {},
    private val end: suspend (SessionEndRequest) -> Unit = {},
) : SessionLifecycleHooks {
    override suspend fun onSessionStart(request: SessionStartRequest): Unit = start(request)

    override suspend fun onSessionEnd(request: SessionEndRequest): Unit = end(request)
}

private class RecordingTurnHooks(
    private val onUserPrompt: suspend (UserPromptSubmitRequest) -> UserPromptSubmitResult,
) : TurnHooks {
    override suspend fun onUserPromptSubmit(request: UserPromptSubmitRequest): UserPromptSubmitResult =
        onUserPrompt(request)

    override suspend fun onStop(request: StopRequest): StopResult = StopResult.Finish
}

private class RecordingCompactionHooks(
    private val before: suspend (CompactionHookRequest) -> Unit,
    private val after: suspend (CompactionHookRequest) -> Unit,
) : CompactionHooks {
    override suspend fun onPreCompact(request: CompactionHookRequest) {
        before(request)
    }

    override suspend fun onPostCompact(request: CompactionHookRequest) {
        after(request)
    }
}

private fun testSettings(): CodexAgentSettings = CodexAgentSettings(OpenAiModelId("test-model"))

private fun testModelCatalog(): OpenAiModelCatalog = OpenAiModelCatalog(
    client = mockOpenAiClient {
        listModels { OpenAiResult.Success(ModelsResponse()) }
    },
    codexCliStorage = CodexCliStorage(Path(".codex-lite-test-model-catalog")),
)

private fun assistantMessage(text: String): ResponseItem.Message = ResponseItem.Message(
    role = MessageRole.Assistant,
    content = listOf(ContentItem.OutputText(text)),
)
