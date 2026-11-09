@file:Suppress("DEPRECATION")

package io.github.stream29.kodex.cli.agent

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAgentMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAssistantMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableContextCompaction
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableDeveloperMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StablePlanUpdate
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableRequestUserInputToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableUserMessage
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskToolEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableSuggestSubagentTaskResult
import io.github.stream29.kodex.tool.multiagent.SuggestSubagentTaskArgs
import io.github.stream29.kodex.tool.multiagent.SuggestedSubagentTask
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.CommitTimestamp
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.contract.KodexAgentStorage
import io.github.stream29.kodex.app.agent.contract.HistoryIndexDependencies
import io.github.stream29.kodex.app.agent.contract.HistoryIndexViewModel
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.app.agent.contract.HistoryIndexEntryKind
import io.github.stream29.kodex.openai.AgentMessageInputContent
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessagePhase
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.PlanItemArg
import io.github.stream29.kodex.openai.StepStatus
import io.github.stream29.kodex.openai.UpdatePlanArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestionOption
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

val historyIndexViewModelTest by testSuite {
    test("only exact Message timestamps are exposed and replacement invalidates requests") {
        val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test")))
        val time = kotlin.time.Instant.parse("2026-09-08T01:02:03Z")
        storage.commit(StableUserMessage(listOf(ContentItem.InputText("message"))), kotlin.time.Instant.DISTANT_PAST)
        storage.commit(StableContextCompaction(encryptedContent = "enc"), kotlin.time.Instant.DISTANT_PAST)
        val latest = MutableStateFlow(2)
        val state = MutableStateFlow<KodexAgentStateValue>(KodexAgentStateValue.Empty)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val model = storageIndex(storage, latest, state, scope)
        try {
            val initialWindow = model.awaitIndexes(listOf(1, 2))
            assertNull(assertNotNull(initialWindow.entryAtOrNull(1)).messageTimestamp)
            storage.revert(1)
            storage.commit(
                listOf(
                    StableUserMessage(listOf(ContentItem.InputText("message"))),
                    CommitTimestamp(time + 1.seconds),
                ),
            )
            storage.commit(
                listOf(
                    StableContextCompaction(encryptedContent = "enc"),
                    CommitTimestamp(time + 2.seconds),
                ),
            )
            state.value = KodexAgentStateValue.ExternalWrite
            state.value = KodexAgentStateValue.Empty
            val refreshed = withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5.seconds) {
                    model.window.first { it.revision > initialWindow.revision }
                }
            }
            assertEquals(time + 1.seconds, assertNotNull(refreshed.entryAtOrNull(1)).messageTimestamp)
            assertNull(assertNotNull(refreshed.entryAtOrNull(2)).messageTimestamp)
            storage.revert(1)
            latest.value = 0
            model.awaitIndexes(emptyList())
            assertFalse(model.contains(refreshed.generation, 1))
        } finally {
            scope.cancelAndJoin()
        }
    }

    test("suggestions use only the simple sidebar label") {
        val storage = InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("test")))
        storage.commit(
            StableSuggestSubagentTaskToolEvent(
                "suggest",
                arguments = SuggestSubagentTaskArgs(listOf(SuggestedSubagentTask("Hidden name", "Hidden prompt"))),
                result = StableSuggestSubagentTaskResult.Failure("Hidden reason"),
            ),
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val model = storageIndex(
            storage = storage,
            latestIndex = MutableStateFlow(1),
            agentState = MutableStateFlow<KodexAgentStateValue>(KodexAgentStateValue.Empty),
            scope = scope,
        )
        try {
            val window = model.awaitIndexes(listOf(1))
            val entry = assertNotNull(window.entryAtOrNull(1))
            assertEquals(HistoryIndexEntryKind.SuggestSubagents, entry.kind)
            assertEquals("suggest subagents", entry.summary)
            assertEquals("suggest subagents", entry.detail.content)
            assertEquals(null, entry.detail.requestUserInput)
        } finally {
            scope.cancelAndJoin()
        }
    }

    test("tracks sparse index entries incrementally and invalidates on revert") {
        coroutineScope {
            val storage = InMemoryKodexAgentStorage(
                KodexAgentSettings(model = OpenAiModelId("test")),
            )
            val latestIndex = MutableStateFlow(0)
            val agentState = MutableStateFlow<KodexAgentStateValue>(KodexAgentStateValue.Empty)
            val cacheNonce = MutableStateFlow(11L)
            val childScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val viewModel = storageIndex(
                storage = storage,
                latestIndex = latestIndex,
                agentState = agentState,
                scope = childScope,
                cacheNonce = cacheNonce,
            )
            try {
                assertEquals(emptyList(), viewModel.awaitIndexes(emptyList()).indexes)

                storage.commit(CommitTimestamp(kotlin.time.Instant.DISTANT_PAST))
                storage.commit(
                    StableUserMessage(
                        content = listOf(ContentItem.InputText("question\nwith   spaces")),
                    ),
                )
                storage.commit(CommitTimestamp(kotlin.time.Instant.DISTANT_PAST))
                latestIndex.value = 3
                val firstWindow = viewModel.awaitIndexes(listOf(2))
                assertEquals(
                    "question with spaces",
                    assertNotNull(firstWindow.entryAtOrNull(2)).summary,
                )

                storage.commit(CommitTimestamp(kotlin.time.Instant.DISTANT_PAST))
                storage.commit(
                    StableAssistantMessage(
                        content = listOf(ContentItem.OutputText("answer")),
                        phase = MessagePhase.FinalAnswer,
                    ),
                )
                latestIndex.value = 5
                val appended = viewModel.awaitIndexes(listOf(2, 5))
                assertEquals(
                    HistoryIndexEntryKind.UserMessage,
                    assertNotNull(appended.entryAtOrNull(2)).kind,
                )
                assertEquals(
                    HistoryIndexEntryKind.AssistantFinal,
                    assertNotNull(appended.entryAtOrNull(5)).kind,
                )

                storage.revert(4)
                latestIndex.value = 3
                cacheNonce.value = 12L
                val reverted = viewModel.awaitGenerationAtLeast(12)
                assertEquals(12L, reverted.generation)
                assertEquals(listOf(2), reverted.indexes)

                storage.revert(2)
                storage.commit(
                    StableUserMessage(
                        content = listOf(ContentItem.InputText("rewritten")),
                    ),
                )
                storage.commit(CommitTimestamp(kotlin.time.Instant.DISTANT_PAST))
                cacheNonce.value = 13L
                val refreshed = viewModel.awaitGenerationAtLeast(13)
                assertEquals(
                    "rewritten",
                    assertNotNull(refreshed.entryAtOrNull(2)).summary,
                )
            } finally {
                childScope.cancelAndJoin()
            }
        }
    }

    test("projects summaries and complete hover details") {
        coroutineScope {
            val storage = InMemoryKodexAgentStorage(
                KodexAgentSettings(model = OpenAiModelId("test")),
            )
            val latestIndex = MutableStateFlow(8)
            val agentState = MutableStateFlow<KodexAgentStateValue>(KodexAgentStateValue.Empty)
            storage.commit(StableContextCompaction(encryptedContent = "enc"))
            storage.commit(
                StableDeveloperMessage(
                    content = listOf(
                        ContentItem.InputText("developer"),
                        ContentItem.InputImage("image"),
                    ),
                ),
            )
            storage.commit(
                StableAgentMessage(
                    author = "one",
                    recipient = "two",
                    content = listOf(
                        AgentMessageInputContent.InputText("private"),
                        AgentMessageInputContent.EncryptedContent("ciphertext"),
                    ),
                ),
            )
            storage.commit(
                StableRequestUserInputToolEvent(
                    callId = "request",
                    arguments = RequestUserInputArgs(
                        questions = listOf(
                            RequestUserInputQuestion(
                                id = "secret",
                                header = "Credential",
                                question = "Enter token",
                                isSecret = true,
                                options = listOf(
                                    RequestUserInputQuestionOption(
                                        label = "Saved",
                                        description = "Use saved token",
                                    ),
                                ),
                            ),
                        ),
                    ),
                    result = StableRequestUserInputResult.Answered(
                        RequestUserInputResponse(
                            answers = mapOf(
                                "secret" to RequestUserInputAnswer(listOf("do-not-render")),
                            ),
                        ),
                    ),
                ),
            )
            storage.commit(
                StablePlanUpdate(
                    callId = "plan",
                    arguments = UpdatePlanArgs(
                        explanation = "Updated",
                        plan = listOf(
                            PlanItemArg("done", StepStatus.Completed),
                            PlanItemArg("current", StepStatus.InProgress),
                            PlanItemArg("later", StepStatus.Pending),
                        ),
                    ),
                ),
            )
            storage.commit(
                StablePlanUpdate(
                    callId = "pending-plan",
                    arguments = UpdatePlanArgs(
                        plan = listOf(
                            PlanItemArg("first", StepStatus.Pending),
                            PlanItemArg("second", StepStatus.Pending),
                        ),
                    ),
                ),
            )
            storage.commit(StableUserMessage(content = emptyList()))
            storage.commit(
                StableAssistantMessage(
                    content = listOf(ContentItem.OutputText("plain")),
                ),
            )

            val childScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val viewModel = storageIndex(
                storage = storage,
                latestIndex = latestIndex,
                agentState = agentState,
                scope = childScope,
            )
            try {
                val window = viewModel.awaitIndexes((1..8).toList())
                assertEquals("Context compacted", assertNotNull(window.entryAtOrNull(1)).summary)
                assertEquals("developer[image]", assertNotNull(window.entryAtOrNull(2)).summary)
                assertEquals(
                    "private[encrypted content]",
                    assertNotNull(window.entryAtOrNull(3)).summary,
                )
                assertEquals("Enter token", assertNotNull(window.entryAtOrNull(4)).summary)
                assertEquals("current", assertNotNull(window.entryAtOrNull(5)).summary)
                assertEquals("first", assertNotNull(window.entryAtOrNull(6)).summary)
                assertEquals("[empty]", assertNotNull(window.entryAtOrNull(7)).summary)
                assertEquals(
                    HistoryIndexEntryKind.AssistantMessage,
                    assertNotNull(window.entryAtOrNull(8)).kind,
                )

                val agentDetail = assertNotNull(window.entryAtOrNull(3)).detail.content
                assertEquals(
                    "Author: one\nRecipient: two\n\nprivate[encrypted content]",
                    agentDetail,
                )
                val requestDetail = assertNotNull(window.entryAtOrNull(4)).detail.content
                assertEquals(true, requestDetail.contains("Enter token"))
                assertEquals(true, requestDetail.contains("[hidden]"))
                assertEquals(false, requestDetail.contains("do-not-render"))
                assertEquals(
                    "Updated\n\n[x] done\n[>] current\n[ ] later",
                    assertNotNull(window.entryAtOrNull(5)).detail.content,
                )
            } finally {
                childScope.cancelAndJoin()
            }
        }
    }
}

private suspend fun HistoryIndexViewModel.awaitIndexes(
    expected: List<Int>,
) = withContext(Dispatchers.Default.limitedParallelism(1)) {
    withTimeout(5.seconds) {
        window.first { state -> state.indexes == expected }
    }
}

private suspend fun HistoryIndexViewModel.awaitGenerationAtLeast(
    expected: Long,
) = withContext(Dispatchers.Default.limitedParallelism(1)) {
    withTimeout(5.seconds) {
        window.first { state -> state.generation >= expected }
    }
}

/** Real in-memory storage with the old local-generation and production-nonce regression modes. */
private fun storageIndex(
    storage: KodexAgentStorage,
    latestIndex: StateFlow<Int>,
    agentState: StateFlow<KodexAgentStateValue>,
    scope: CoroutineScope,
    cacheNonce: StateFlow<Long>? = null,
): HistoryIndexViewModel = createHistoryIndexViewModel(object : HistoryIndexDependencies {
    override val storage = storage
    override val latestIndex = latestIndex
    override val cacheNonce = cacheNonce
    override val externalWrite = agentState.map { it == KodexAgentStateValue.ExternalWrite }
        .stateIn(scope, SharingStarted.Eagerly, agentState.value == KodexAgentStateValue.ExternalWrite)
    override fun requestScrollToStorageIndex(index: Int) = Unit
}, scope)
