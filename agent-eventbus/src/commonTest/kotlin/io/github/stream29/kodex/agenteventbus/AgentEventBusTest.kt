@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.stream29.kodex.agenteventbus

import de.infix.testBalloon.framework.core.TestCompartment
import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.testScope
import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentruntime.contract.AgentStopEvent
import io.github.stream29.kodex.agentsession.inmemory.InMemoryKodexSessionRepository
import io.github.stream29.kodex.agentsession.test.testKodexAgentDependencies
import io.github.stream29.kodex.agentstate.contract.KodexAgentStateValue
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAskUserEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwaitEventResult
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableAwait
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableScheduleEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.StableTerminalExitedEvent
import io.github.stream29.kodex.agentstorage.cleanmodels.unstable.PendingAwait
import io.github.stream29.kodex.agentstorage.contract.ext.initialize
import io.github.stream29.kodex.agentstorage.contract.getAskUsersAt
import io.github.stream29.kodex.agentstorage.contract.getCommitsIn
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.kodex.tool.awaitevent.AwaitEventArguments
import io.github.stream29.kodex.tool.awaitevent.AwaitEventTools
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputAnswer
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputArgs
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputQuestion
import io.github.stream29.kodex.tool.requestuserinput.RequestUserInputResponse
import io.github.stream29.kodex.tool.unifiedexec.ExecCommandArguments
import io.github.stream29.kodex.tool.unifiedexec.UnifiedExecMinimumYieldTimeMillis
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

val agentEventBusTest by testSuite(
    testConfig = TestConfig.testScope(isEnabled = false),
) {
    test("submitAskUserAnswer and submitScheduleTrigger reopen evicted session, complete parked await_event, and emit AssistantMessage stopEvent") {
        runTest {
            var requestCount = 0
            val client = mockOpenAiClient {
                createResponse { _ ->
                    requestCount += 1
                    when (requestCount) {
                        1 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.FunctionCall(
                                    name = AwaitEventTools.Name,
                                    callId = "await_1",
                                    arguments = OpenAiJsonCodec.encodeToString(
                                        AwaitEventArguments.serializer(),
                                        AwaitEventArguments(),
                                    ),
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "resp_1", endTurn = false)),
                        )
                        2 -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.FunctionCall(
                                    name = AwaitEventTools.Name,
                                    callId = "await_2",
                                    arguments = OpenAiJsonCodec.encodeToString(
                                        AwaitEventArguments.serializer(),
                                        AwaitEventArguments(),
                                    ),
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "resp_2", endTurn = false)),
                        )
                        else -> flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.Message(
                                    role = MessageRole.Assistant,
                                    content = listOf(ContentItem.OutputText("All events handled.")),
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "resp_3", endTurn = true)),
                        )
                    }
                }
            }

            val repository = InMemoryKodexSessionRepository(
                dependencies = testKodexAgentDependencies(client),
                sessionTtl = 10.seconds,
                timeSource = testScheduler.timeSource,
            )
            try {
                val bus = AgentEventBus(repository)
                val stopEvents = mutableListOf<AgentStopEvent>()
                val collector = backgroundScope.async {
                    repository.stopEvents.collect { stopEvents += it.event }
                }

                val agentId = repository.create()
                val session = repository.open(agentId)
                session.runtime.modify { storage ->
                    storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                }
                val createdAsk = session.runtime.createAskUser(
                    RequestUserInputArgs(
                        questions = listOf(
                            RequestUserInputQuestion(id = "q1", header = "Target", question = "Deploy now?"),
                        ),
                    ),
                )

                bus.submitUserMessage(agentId, listOf(ContentItem.InputText("Wait for answer then schedule.")))
                session.runtime.runningTurn.value?.join()
                runCurrent()

                // First response parked on await_1: runningTurn is null and no stopEvent was emitted.
                assertNull(session.runtime.runningTurn.value)
                val parkedState = assertIs<KodexAgentStateValue.ToolPending>(session.runtime.state.value)
                assertEquals("await_1", assertIs<PendingAwait>(parkedState.events.single()).callId)
                assertTrue(stopEvents.isEmpty())

                // Let TTL expire so the session unloads while parked on await_1.
                advanceTimeBy(10_000L)
                runCurrent()
                assertFalse(agentId in repository.openSessions.value)

                // Answering ask_user reopens the session, completes await_1, consumes StableAskUserEvent, and parks on await_2.
                val response = RequestUserInputResponse(
                    answers = mapOf("q1" to RequestUserInputAnswer(answers = listOf("yes"))),
                )
                bus.submitAskUserAnswer(agentId, createdAsk.askId, response)
                val reopened = assertNotNull(repository.openSessions.value[agentId])
                reopened.runtime.runningTurn.value?.join()
                runCurrent()

                assertNull(reopened.runtime.runningTurn.value)
                assertEquals(response, reopened.storage.getAskUsersAt(reopened.runtime.latestIndex.value).single().answer?.response)
                assertFailsWith<IllegalStateException> {
                    bus.submitAskUserAnswer(agentId, createdAsk.askId, response)
                }
                assertTrue(stopEvents.isEmpty())

                // Schedule trigger wakes await_2 and finishes with AssistantMessage stop event.
                bus.submitScheduleTrigger(agentId, scheduleId = 42L, description = "Nightly check")
                reopened.runtime.runningTurn.value?.join()
                runCurrent()

                assertEquals(KodexAgentStateValue.AssistantMessage, reopened.runtime.state.value)
                assertEquals(1, stopEvents.size)
                val stop = assertIs<AgentStopEvent.AssistantMessage>(stopEvents.single())
                assertEquals(
                    "All events handled.",
                    stop.message.content.filterIsInstance<ContentItem.OutputText>().single().text,
                )

                val historyCommits = reopened.storage.getCommitsIn(
                    0..reopened.runtime.latestIndex.value,
                    AgentCommitType.IndexTypes + AgentCommitType.WorkTypes,
                ).map { it.commit }
                assertEquals(1, historyCommits.filterIsInstance<StableAskUserEvent>().size)
                assertEquals(1, historyCommits.filterIsInstance<StableScheduleEvent>().size)
                val completedAwaits = historyCommits.filterIsInstance<StableAwait>()
                assertEquals(2, completedAwaits.size)
                assertTrue(completedAwaits.all { it.result is StableAwaitEventResult.Output })

                collector.cancel()
            } finally {
                repository.cancelAndJoin()
            }
        }
    }

    test("unknown terminal ID in await_event fails immediately within the same turn") {
        runTest {
            var requestCount = 0
            val client = mockOpenAiClient {
                createResponse { _ ->
                    requestCount += 1
                    if (requestCount == 1) {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.FunctionCall(
                                    name = AwaitEventTools.Name,
                                    callId = "await_bad",
                                    arguments = OpenAiJsonCodec.encodeToString(
                                        AwaitEventArguments.serializer(),
                                        AwaitEventArguments(terminals = listOf(99999)),
                                    ),
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "resp_1", endTurn = false)),
                        )
                    } else {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.Message(
                                    role = MessageRole.Assistant,
                                    content = listOf(ContentItem.OutputText("Handled invalid terminal.")),
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "resp_2", endTurn = true)),
                        )
                    }
                }
            }

            val repository = InMemoryKodexSessionRepository(
                dependencies = testKodexAgentDependencies(client),
                timeSource = testScheduler.timeSource,
            )
            try {
                val bus = AgentEventBus(repository)
                val agentId = repository.create()
                val session = repository.open(agentId)
                session.runtime.modify { storage ->
                    storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
                }

                bus.submitUserMessage(agentId, listOf(ContentItem.InputText("Watch missing terminal.")))
                session.runtime.runningTurn.value?.join()
                runCurrent()

                assertEquals(2, requestCount)
                assertEquals(KodexAgentStateValue.AssistantMessage, session.runtime.state.value)
                val completed = session.storage.getCommitsIn(
                    0..session.runtime.latestIndex.value,
                    setOf(AgentCommitType.AwaitEventToolEvent),
                ).single().commit as StableAwait
                assertIs<StableAwaitEventResult.Failure>(completed.result)
            } finally {
                repository.cancelAndJoin()
            }
        }
    }
}

val agentEventBusRealTerminalTest by testSuite(
    compartment = { TestCompartment.RealTime },
    testConfig = TestConfig.testScope(isEnabled = false, timeout = 30.seconds),
) {
    test("watched background terminal exit automatically wakes parked await_event with StableTerminalExitedEvent") {
        val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
        try {
            var watchedSessionId: Int? = null
            var requestCount = 0
            val client = mockOpenAiClient {
                createResponse { _ ->
                    requestCount += 1
                    if (requestCount == 1) {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.FunctionCall(
                                    name = AwaitEventTools.Name,
                                    callId = "await_term",
                                    arguments = OpenAiJsonCodec.encodeToString(
                                        AwaitEventArguments.serializer(),
                                        AwaitEventArguments(terminals = listOf(requireNotNull(watchedSessionId))),
                                    ),
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "resp_1", endTurn = false)),
                        )
                    } else {
                        flowOf(
                            ResponsesStreamEvent.OutputItemDone(
                                outputIndex = 0,
                                item = ResponseItem.Message(
                                    role = MessageRole.Assistant,
                                    content = listOf(ContentItem.OutputText("Terminal exited cleanly.")),
                                ),
                            ),
                            ResponsesStreamEvent.Completed(Response(id = "resp_2", endTurn = true)),
                        )
                    }
                }
            }

            val repository = scope.InMemoryKodexSessionRepository(testKodexAgentDependencies(client))
            val bus = AgentEventBus(repository)
            val agentId = repository.create()
            val session = repository.open(agentId)
            session.runtime.modify { storage ->
                storage.initialize(KodexAgentSettings(model = OpenAiModelId("test-model")))
            }

            val execOut = session.runtime.unifiedExecToolClient.execCommand(
                ExecCommandArguments(
                    command = "IFS= read -r line; printf 'done:%s\\n' \"\$line\"",
                    name = "interactive-worker",
                    yieldTimeMillis = UnifiedExecMinimumYieldTimeMillis,
                ),
            )
            val termId = assertNotNull(execOut.sessionId)
            watchedSessionId = termId

            bus.submitUserMessage(agentId, listOf(ContentItem.InputText("Wait for worker.")))
            withTimeout(5.seconds) {
                session.runtime.state.first { state ->
                    state is KodexAgentStateValue.ToolPending &&
                        state.events.any { it is PendingAwait }
                }
                session.runtime.runningTurn.first { it == null }
            }

            val proc = assertNotNull(session.runtime.unifiedExecToolClient.activeSessions.value[termId])
            proc.writeStdin("finish\n", closeInput = true)

            withTimeout(10.seconds) {
                session.runtime.state.first { it == KodexAgentStateValue.AssistantMessage }
                session.runtime.runningTurn.first { it == null }
            }

            val exitedEvents = session.storage.getCommitsIn(
                0..session.runtime.latestIndex.value,
                setOf(AgentCommitType.EventTerminalExited),
            ).map { it.commit as StableTerminalExitedEvent }
            assertEquals(1, exitedEvents.size)
            assertEquals(termId, exitedEvents.single().sessionId)
            assertEquals("interactive-worker", exitedEvents.single().name)
            assertEquals(0, exitedEvents.single().exitCode)
        } finally {
            scope.cancelAndJoin()
        }
    }
}
