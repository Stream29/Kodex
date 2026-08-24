package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.Response
import io.github.stream29.kodex.openai.ResponseItem
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.ktor.client.plugins.sse.SSEClientException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

val openAiRemoteCompactionV2Test by testSuite {
    test("remote compaction v2 requires one output before completed") {
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")

        val response = flowOf(
            RemoteCompactionStreamEvent.ResponseEvent(
                ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = compaction),
            ),
            RemoteCompactionStreamEvent.ResponseEvent(
                ResponsesStreamEvent.Completed(Response(id = "response")),
            ),
        ).collectRemoteCompactionV2Response()

        assertEquals(compaction, response.compactionOutput)
        assertEquals("response", response.completedResponse?.id)
    }

    test("remote compaction v2 stops collecting after completed") {
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")
        val response = flow {
            emit(
                RemoteCompactionStreamEvent.ResponseEvent(
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = compaction),
                ),
            )
            emit(
                RemoteCompactionStreamEvent.ResponseEvent(
                    ResponsesStreamEvent.Completed(Response(id = "response")),
                ),
            )
            error("The stream was read after response.completed.")
        }.collectRemoteCompactionV2Response()

        assertEquals(compaction, response.compactionOutput)
    }

    test("remote compaction v2 retries when the stream ends before completed") {
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")
        var attempts = 0

        val response = retryOpenAiStreamingTransport(
            OpenAiClientRetryConfig(
                maxRetries = 1,
                baseDelayMillis = 1,
                maxDelayMillis = 1,
                randomizationMillis = 0,
            ),
        ) {
            attempts += 1
            if (attempts == 1) {
                flowOf<RemoteCompactionStreamEvent>().collectRemoteCompactionV2Response()
            } else {
                flowOf(
                    RemoteCompactionStreamEvent.ResponseEvent(
                        ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = compaction),
                    ),
                    RemoteCompactionStreamEvent.ResponseEvent(
                        ResponsesStreamEvent.Completed(Response(id = "response")),
                    ),
                ).collectRemoteCompactionV2Response()
            }
        }

        assertEquals(2, attempts)
        assertEquals(compaction, response.compactionOutput)
    }

    test("done after output is still an incomplete stream") {
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")

        assertFailsWith<OpenAiRemoteCompactionV2StreamIncompleteException> {
            flowOf(
                RemoteCompactionStreamEvent.ResponseEvent(
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = compaction),
                ),
                RemoteCompactionStreamEvent.Done,
            ).collectRemoteCompactionV2Response()
        }
    }

    test("multiple compaction outputs before completed are a protocol error") {
        val compaction = ResponseItem.Compaction(encryptedContent = "compact")

        assertFailsWith<OpenAiRemoteCompactionV2ProtocolException> {
            flowOf(
                RemoteCompactionStreamEvent.ResponseEvent(
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 0, item = compaction),
                ),
                RemoteCompactionStreamEvent.ResponseEvent(
                    ResponsesStreamEvent.OutputItemDone(outputIndex = 1, item = compaction),
                ),
                RemoteCompactionStreamEvent.ResponseEvent(
                    ResponsesStreamEvent.Completed(Response(id = "response")),
                ),
            ).collectRemoteCompactionV2Response()
        }
    }

    test("completed without a compaction output is a protocol error") {
        assertFailsWith<OpenAiRemoteCompactionV2ProtocolException> {
            flowOf(
                RemoteCompactionStreamEvent.ResponseEvent(
                    ResponsesStreamEvent.Completed(Response(id = "response")),
                ),
            ).collectRemoteCompactionV2Response()
        }
    }

    test("failed and incomplete response events are retryable") {
        assertFailsWith<OpenAiRemoteCompactionV2StreamFailureException> {
            flowOf(
                RemoteCompactionStreamEvent.ResponseEvent(
                    ResponsesStreamEvent.Failed(
                        response = io.github.stream29.kodex.openai.FailedResponse(),
                    ),
                ),
            ).collectRemoteCompactionV2Response()
        }
        assertFailsWith<OpenAiRemoteCompactionV2StreamFailureException> {
            flowOf(
                RemoteCompactionStreamEvent.ResponseEvent(
                    ResponsesStreamEvent.Incomplete(
                        response = io.github.stream29.kodex.openai.IncompleteResponse(),
                    ),
                ),
            ).collectRemoteCompactionV2Response()
        }
    }

    test("cancellation is not retried") {
        var attempts = 0

        assertFailsWith<CancellationException> {
            retryOpenAiStreamingTransport(
                OpenAiClientRetryConfig(
                    maxRetries = 4,
                    baseDelayMillis = 1,
                    maxDelayMillis = 1,
                    randomizationMillis = 0,
                ),
            ) {
                attempts += 1
                throw CancellationException("cancelled")
            }
        }

        assertEquals(1, attempts)
    }

    test("SSE transport failures wrapped by Ktor remain retryable") {
        val failure = SSEClientException(cause = IOException("socket closed"))

        assertTrue(failure.isRetryableOpenAiTransportException(noRetryConfig().copy(retryTransport = true)))
    }

    test("compaction retry budget allows two retries") {
        var attempts = 0

        assertFailsWith<OpenAiRemoteCompactionV2StreamIncompleteException> {
            retryOpenAiStreamingTransportWithBudget(
                retry = noRetryConfig().copy(maxRetries = 2),
            ) {
                attempts += 1
                throw OpenAiRemoteCompactionV2StreamIncompleteException()
            }
        }

        assertEquals(3, attempts)
    }

    test("compaction deadline is terminal and does not become cancellation") {
        assertFailsWith<OpenAiRemoteCompactionV2DeadlineExceededException> {
            retryOpenAiStreamingTransportWithBudget(
                retry = noRetryConfig().copy(maxRetries = 4),
                deadlineMillis = 50,
            ) {
                delay(500)
                error("The deadline should have cancelled this attempt.")
            }
        }
    }
}

private fun noRetryConfig(): OpenAiClientRetryConfig = OpenAiClientRetryConfig(
    maxRetries = 0,
)
