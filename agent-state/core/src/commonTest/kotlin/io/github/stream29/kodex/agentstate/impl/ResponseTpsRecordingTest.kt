package io.github.stream29.kodex.agentstate.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.agentstate.contract.RequestFinish
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.AgentCommitType
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.KodexAgentSettings
import io.github.stream29.kodex.agentstorage.cleanmodels.stable.LlmRequestMeta
import io.github.stream29.kodex.agentstorage.contract.getCommitAtOrBefore
import io.github.stream29.kodex.agentstorage.contract.getLlmRequestMetaAtOrNull
import io.github.stream29.kodex.agentstorage.inmemory.InMemoryKodexAgentStorage
import io.github.stream29.kodex.openai.ContentItem
import io.github.stream29.kodex.openai.FailedResponse
import io.github.stream29.kodex.openai.MessageRole
import io.github.stream29.kodex.openai.OpenAiModelId
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.TokenUsage
import io.github.stream29.kodex.openai.client.contract.ObservedResponseCompletion
import io.github.stream29.kodex.openai.client.contract.ObservedResponseEvent
import io.github.stream29.kodex.openai.client.contract.OpenAiResponseObservationSource
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.utils.coroutines.cancelAndJoin
import io.github.stream29.kodex.utils.coroutines.supervisorChildScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.microseconds

val responseTpsRecordingTest by testSuite {
    test("fallback replaces text deltas with completed parts and distinguishes absent from visible empty output") {
        val estimate = ResponseOutputEstimate()
        assertNull(estimate.estimatedTokens(Response(id = "unknown", output = listOf(ResponseItem.Reasoning(encryptedContent = "hidden")))))
        assertEquals(0L, estimate.estimatedTokens(Response(id = "empty", outputText = "")))
        estimate.observe(ResponsesStreamEvent.OutputTextDelta("msg", 1, 0, "first"))
        estimate.observe(ResponsesStreamEvent.OutputTextDelta("msg", 1, 0, "second"))
        estimate.observe(ResponsesStreamEvent.OutputTextDone("msg", 1, 0, "whole"))
        assertEquals(2L, estimate.estimatedTokens(Response(id = "delta")))
    }

    testFixture { testSuiteCoroutineScope.supervisorChildScope() } closeWith { cancelAndJoin() } asContextForEach {
        test("successful completion prefers provider output including zero and keeps genuine usage") {
            for (reported in listOf(0L, 21L)) {
                val storage = responseTpsStorage()
                val usage = TokenUsage(inputTokens = 700, outputTokens = reported, totalTokens = 721)
                val response = Response(id = "ok", outputText = "never estimate over genuine usage", usage = usage)
                val stream = flowOf(timedCompletion(response, reported, usage.totalTokens))
                val agent = KodexAgentState(mockOpenAiClient { createResponse { _ -> stream.wireEvents() } }, storage)
                try {
                    agent.appendUserMessage(listOf(ContentItem.InputText("not model output")))
                    assertEquals(RequestFinish.Finish, agent.requestResponseApi())
                    val saved = assertIs<LlmRequestMeta.Response>(storage.getLlmRequestMetaAtOrNull(storage.latestIndex()))
                    assertEquals(500_000L, saved.elapsedNanoseconds)
                    assertEquals(reported, saved.outputTokens)
                    assertEquals(usage, saved.usage)
                    assertEquals(721L, saved.totalTokens)
                } finally {
                    agent.cancelAndJoin()
                }
            }
        }

        test("missing output usage estimates message and tool strings once without replacing the context budget") {
            for (reportedTotal in listOf(null, 670L)) {
                val storage = responseTpsStorage()
                storage.commit(LlmRequestMeta.Legacy(totalTokens = 330L))
                val message = ResponseItem.Message(role = MessageRole.Assistant, content = listOf(ContentItem.OutputText("abcde")))
                val response = Response(
                    id = "estimated",
                    outputText = "abcde",
                    output = listOf(
                        message,
                        ResponseItem.FunctionCall(name = "fixture", arguments = """{"k":"v"}""", callId = "function"),
                        ResponseItem.CustomToolCall(name = "fixture", input = "xy", callId = "custom"),
                        ResponseItem.Reasoning(encryptedContent = "not model-visible output"),
                    ),
                )
                val stream = flowOf(
                    ObservedResponseEvent(ResponsesStreamEvent.OutputTextDelta("msg", 0, 0, "abcde")),
                    ObservedResponseEvent(ResponsesStreamEvent.OutputItemDone(0, message)),
                    timedCompletion(response, null, reportedTotal),
                )
                val agent = KodexAgentState(mockOpenAiClient { createResponse { _ -> stream.wireEvents() } }, storage)
                try {
                    agent.appendUserMessage(listOf(ContentItem.InputText("never estimate me either")))
                    assertEquals(RequestFinish.Finish, agent.requestResponseApi())
                    val saved = assertIs<LlmRequestMeta.Response>(storage.getLlmRequestMetaAtOrNull(storage.latestIndex()))
                    assertEquals(4L, saved.outputTokens) // ceil((5 + 9 + 2) / 4)
                    assertNull(saved.usage)
                    assertEquals(reportedTotal ?: 330L, saved.totalTokens)
                } finally {
                    agent.cancelAndJoin()
                }
            }
        }

        test("a completed wire response without a start preserves only the reported context budget") {
            val storage = responseTpsStorage()
            val response = Response(id = "no-created", usage = TokenUsage(10, 0, 10))
            val agent = KodexAgentState(
                mockOpenAiClient { createResponse { _ -> flowOf(ResponsesStreamEvent.Completed(response)) } },
                storage,
            )
            try {
                agent.appendUserMessage(listOf(ContentItem.InputText("input")))
                assertEquals(RequestFinish.Finish, agent.requestResponseApi())
                assertNull(storage.responseMeasurement())
                assertEquals(LlmRequestMeta.Legacy(10), storage.getLlmRequestMetaAtOrNull(storage.latestIndex()))
            } finally {
                agent.cancelAndJoin()
            }
        }

        test("a trailing failure or cancellation before settlement emits no response measurement") {
            val completion = timedCompletion(Response(id = "not-settled", outputText = "output"), null, null)
            val failedStorage = responseTpsStorage()
            val failedStream = flowOf(completion, ObservedResponseEvent(ResponsesStreamEvent.Failed(FailedResponse(id = "not-settled"))))
            val failedAgent = KodexAgentState(
                mockOpenAiClient { createResponse { _ -> failedStream.wireEvents() } }, failedStorage,
            )
            try {
                failedAgent.appendUserMessage(listOf(ContentItem.InputText("input")))
                assertEquals(RequestFinish.Retryable, failedAgent.requestResponseApi())
                assertNull(failedStorage.responseMeasurement())
            } finally {
                failedAgent.cancelAndJoin()
            }

            val cancelledStorage = responseTpsStorage()
            val completionDelivered = CompletableDeferred<Unit>()
            val cancelledStream = flow {
                emit(completion)
                completionDelivered.complete(Unit)
                awaitCancellation()
            }
            val cancelledAgent = KodexAgentState(
                mockOpenAiClient { createResponse { _ -> cancelledStream.wireEvents() } }, cancelledStorage,
            )
            try {
                cancelledAgent.appendUserMessage(listOf(ContentItem.InputText("input")))
                val request = async { cancelledAgent.requestResponseApi() }
                completionDelivered.await()
                request.cancelAndJoin()
                assertNull(cancelledStorage.responseMeasurement())
            } finally {
                cancelledAgent.cancelAndJoin()
            }
        }
    }
}

private fun responseTpsStorage(): InMemoryKodexAgentStorage =
    InMemoryKodexAgentStorage(KodexAgentSettings(OpenAiModelId("fixture")))

private fun timedCompletion(response: Response, outputTokens: Long?, totalTokens: Long?): ObservedResponseEvent =
    ObservedResponseEvent(ResponsesStreamEvent.Completed(response), ObservedResponseCompletion(500.microseconds, outputTokens, totalTokens))

@OptIn(ExperimentalCoroutinesApi::class)
private fun Flow<ObservedResponseEvent>.wireEvents(): Flow<ResponsesStreamEvent> =
    object : Flow<ResponsesStreamEvent> by map({ it.event }), OpenAiResponseObservationSource {
        override val eventsWithObservations = this@wireEvents
    }

private suspend fun InMemoryKodexAgentStorage.responseMeasurement(): LlmRequestMeta.Response? =
    getCommitAtOrBefore(latestIndex(), setOf(AgentCommitType.LlmRequestMetaResponse))?.commit as? LlmRequestMeta.Response
