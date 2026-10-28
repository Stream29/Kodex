package io.github.stream29.kodex.openai.client

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.FailedResponse
import io.github.stream29.kodex.openai.ResponseError
import io.github.stream29.kodex.openai.ResponsesStreamEvent
import io.github.stream29.kodex.openai.IncompleteDetails
import io.github.stream29.kodex.openai.IncompleteResponse
import io.github.stream29.kodex.openai.OpenAiFailureReason
import io.github.stream29.kodex.openai.OpenAiFailureTermination
import io.github.stream29.kodex.openai.OpenAiResponseStreamIncompleteException
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

val openAiModerationTest by testSuite {
    test("only explicit moderation markers and codes are blocked") {
        fun metadata(json: String) = ResponsesStreamEvent.Metadata(metadata = Json.parseToJsonElement(json).jsonObject)
        assertTrue(metadata("""{"openai_chatgpt_moderation_metadata":{"prompt":{"cyber_security":{"outputs":[{"is_blocked":true,"cyber_trusted_access":false}]}}}}""").isExplicitModerationBlock())
        assertFalse(metadata("""{"openai_chatgpt_moderation_metadata":{"prompt":{"cyber_security":{"outputs":[{"cyber_trusted_access":false}]}}}}""").isExplicitModerationBlock())
        assertFalse(metadata("""{"openai_chatgpt_moderation_metadata":{"prompt":{"cyber_security":{"outputs":[{"is_blocked":"true"}]}}}}""").isExplicitModerationBlock())
        assertFalse(metadata("""{"unrelated":{"is_blocked":true}}""").isExplicitModerationBlock())
        assertTrue(metadata("""{"openai_chatgpt_moderation_metadata":{"response":{"other_policy":{"outputs":[{"is_blocked":true}]}}}}""").isExplicitModerationBlock())
        assertFalse(metadata("""{"openai_chatgpt_moderation_metadata":{"prompt":{"other_policy":{"is_blocked":true}}}}""").isExplicitModerationBlock())
        assertTrue(ResponsesStreamEvent.Failed(FailedResponse(ResponseError(code = "content_policy_violation"))).isExplicitModerationBlock())
        assertFalse(ResponsesStreamEvent.Failed(FailedResponse(ResponseError(message = "blocked"))).isExplicitModerationBlock())
        assertTrue(ResponsesStreamEvent.Other(Json.parseToJsonElement("""{"type":"error","code":"content_policy_violation"}""")).isExplicitModerationBlock())
        assertTrue(ResponsesStreamEvent.Other(Json.parseToJsonElement("""{"type":"error","error":{"code":"content_policy_violation"}}""")).isExplicitModerationBlock())
        assertFalse(ResponsesStreamEvent.Other(Json.parseToJsonElement("""{"type":"other","code":"content_policy_violation"}""")).isExplicitModerationBlock())
    }

    test("blocked compaction stops without retries and does not expose echoed text") {
        var attempts = 0
        val error = assertFailsWith<OpenAiModerationBlockedException> {
            retryOpenAiStreamingTransport(OpenAiClientRetryConfig(maxRetries = 3)) {
                attempts++
                flowOf(
                    RemoteCompactionStreamEvent.ResponseEvent(
                        ResponsesStreamEvent.Failed(FailedResponse(ResponseError(
                            code = "content_policy_violation",
                            message = "Authorization: Bearer secret; private prompt",
                        ))),
                    ),
                ).collectRemoteCompactionV2Response()
            }
        }
        assertEquals(1, attempts)
        assertFalse(error.message.orEmpty().contains("secret"))
        assertFalse(error.message.orEmpty().contains("private prompt"))
    }

    test("explicit metadata block stops compaction") {
        val metadata = Json.parseToJsonElement(
            """{"openai_chatgpt_moderation_metadata":{"prompt":{"cyber_security":{"outputs":[{"is_blocked":true}]}}}}""",
        ).jsonObject
        assertFailsWith<OpenAiModerationBlockedException> {
            flowOf(
                RemoteCompactionStreamEvent.ResponseEvent(ResponsesStreamEvent.Metadata(metadata = metadata)),
            ).collectRemoteCompactionV2Response()
        }
    }

    test("diagnostic projection retains supplied fields alongside fixed meanings") {
        val sensitive = listOf(
            "Bearer access-token", "Cookie=session-cookie", "encrypted-turn-state",
            "complete private prompt", "private prompt", "access-token",
        )
        for (echo in sensitive) {
            val event = ResponsesStreamEvent.Failed(
                FailedResponse(ResponseError(message = echo, code = echo, type = echo), id = "resp_fixture"),
            )
            val diagnostic = requireNotNull(event.toFailureDiagnostic())
            assertEquals(OpenAiFailureTermination.ResponseFailed, diagnostic.termination)
            assertEquals(null, diagnostic.reason)
            assertEquals(echo, diagnostic.errorCode)
            assertEquals(echo, diagnostic.errorType)
            assertEquals(echo, diagnostic.errorMessage)
            assertTrue(diagnostic.toString().contains(echo))
            val incomplete = ResponsesStreamEvent.Incomplete(
                IncompleteResponse(IncompleteDetails(echo), id = "resp_incomplete"),
            )
            assertEquals(null, incomplete.toFailureDiagnostic()?.reason)
            assertEquals(echo, incomplete.toFailureDiagnostic()?.incompleteReason)
            assertEquals("resp_incomplete", incomplete.toFailureDiagnostic()?.responseId)
            assertFalse(OpenAiResponseStreamIncompleteException(IncompleteDetails(echo)).message.orEmpty().contains(echo))
        }
        val safe = ResponsesStreamEvent.Failed(
            FailedResponse(ResponseError(code = "context_length_exceeded", message = sensitive.first())),
        ).toFailureDiagnostic()
        assertEquals(OpenAiFailureReason.ContextLengthExceeded, safe?.reason)
        assertEquals(
            OpenAiFailureReason.MaxOutputTokens,
            ResponsesStreamEvent.Incomplete(IncompleteResponse(IncompleteDetails("max_output_tokens")))
                .toFailureDiagnostic()?.reason,
        )
        assertEquals(null, ResponsesStreamEvent.Failed(FailedResponse()).toFailureDiagnostic()?.reason)
    }

    test("HTTP reasons require exact structured string values") {
        assertEquals(
            OpenAiFailureReason.RateLimitExceeded,
            """{"error":{"code":"rate_limit_exceeded","message":"Bearer secret"}}""".safeHttpFailureReason(),
        )
        assertEquals(
            OpenAiFailureReason.ContentPolicyViolation,
            """{"error":{"code":"server_error","type":"content_policy_violation"}}""".safeHttpFailureReason(),
        )
        for (body in listOf(
            """{"error":{"code":"rate_limit_exceeded private prompt"}}""",
            """{"error":{"code":true}}""",
            """{"other":{"code":"content_policy_violation"}}""",
            "{\"error\":{\"code\":\"content_policy_violation\"",
            """{"error":{"message":"content_policy_violation"}}""",
        )) {
            assertEquals(null, body.safeHttpFailureReason())
        }
    }

    test("last compaction failure retains its category and retry count") {
        var attempts = 0
        val failure = assertFailsWith<OpenAiRemoteCompactionV2StreamFailureException> {
            retryOpenAiStreamingTransportWithBudget(
                OpenAiClientRetryConfig(maxRetries = 1, baseDelayMillis = 1, maxDelayMillis = 1),
            ) { budget ->
                attempts++
                budget.recordRequestId("req_$attempts")
                budget.recordResponseId("resp_$attempts")
                val event = ResponsesStreamEvent.Failed(
                    FailedResponse(ResponseError(code = if (attempts == 1) "server_error" else "context_length_exceeded")),
                )
                budget.recordFailure(event.toFailureDiagnostic())
                flowOf(RemoteCompactionStreamEvent.ResponseEvent(event)).collectRemoteCompactionV2Response()
            }
        }
        assertEquals(2, attempts)
        assertEquals(1, failure.retries)
        assertEquals(OpenAiFailureReason.ContextLengthExceeded, failure.failureDiagnostic?.reason)
        assertEquals(OpenAiFailureTermination.ResponseFailed, failure.failureDiagnostic?.termination)
        assertEquals("req_2", failure.failureDiagnostic?.requestId)
        assertEquals("resp_2", failure.failureDiagnostic?.responseId)
        assertTrue(failure.operationId != null)
    }
}
