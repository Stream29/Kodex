package io.github.stream29.kodex.openai

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs



private val json = OpenAiJsonCodec

val openAiResultSerializationTest by testSuite {
    test("diagnostic identifiers remain exact and controls are reversibly escaped") {
        val id = "provider/nonstandard:é\\tail\n\u0000"
        val diagnostic = OpenAiFailureDiagnostic(OpenAiFailureTermination.ResponseFailed, responseId = id)
        assertEquals(id, diagnostic.responseId)
        assertEquals("provider/nonstandard:é\\\\tail\\u000a\\u0000", escapeOpenAiDiagnosticText(id))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", escapeOpenAiDiagnosticText("123e4567-e89b-12d3-a456-426614174000"))
        assertEquals("resp_" + "x".repeat(5000), escapeOpenAiDiagnosticText("resp_" + "x".repeat(5000)))
    }
    test("diagnostic fields retain exact values and escape controls in rendering") {
        val value = "custom\\tail\n\u0000\u0085\u2028"
        val diagnostic = OpenAiFailureDiagnostic(
            OpenAiFailureTermination.ResponseIncomplete,
            errorCode = value,
            errorType = "",
            errorMessage = "ordinary " + "x".repeat(5000),
            incompleteReason = value,
        )
        assertEquals(value, diagnostic.errorCode)
        assertEquals("", diagnostic.errorType)
        assertEquals("ordinary " + "x".repeat(5000), diagnostic.errorMessage)
        kotlin.test.assertTrue(diagnostic.toString().contains("errorCode=custom\\\\tail\\u000a\\u0000\\u0085\\u2028"))
        kotlin.test.assertTrue(diagnostic.toString().contains("incompleteReason=custom\\\\tail\\u000a\\u0000\\u0085\\u2028"))
        kotlin.test.assertTrue(diagnostic.toString().contains(diagnostic.errorMessage!!))
    }
    test("failed and incomplete events retain optional response identifiers") {
        val failed = json.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.failed","response":{"id":"resp_test","error":{"code":"server_error"}}}""",
        )
        assertEquals("resp_test", assertIs<ResponsesStreamEvent.Failed>(failed).response.id)
        val incomplete = json.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.incomplete","response":{"id":"resp_other","incomplete_details":{}}}""",
        )
        assertEquals("resp_other", assertIs<ResponsesStreamEvent.Incomplete>(incomplete).response.id)
        assertEquals(null, json.decodeFromString<FailedResponse>("{}").id)
        assertEquals(null, json.decodeFromString<IncompleteResponse>("{}").id)
        val crossFields = json.decodeFromString<ResponsesStreamEvent>(
            """{"type":"response.incomplete","response":{"status":"incomplete","error":{"code":"custom","type":"","message":"hi"},"incomplete_details":{"reason":"custom_reason"}}}""",
        )
        val response = assertIs<ResponsesStreamEvent.Incomplete>(crossFields).response
        assertEquals("custom", response.error?.code)
        assertEquals("", response.error?.type)
        assertEquals("hi", response.error?.message)
        assertEquals("custom_reason", response.incompleteDetails?.reason)
        val failedFields = json.decodeFromString<FailedResponse>(
            """{"status":"failed","incomplete_details":{"reason":"other"}}""",
        )
        assertEquals("other", failedFields.incompleteDetails?.reason)
    }
    test("decodes successful response as success") {
        val result = json.decodeFromString<OpenAiResponseResult<ModelsResponse>>(
            """{"models":[{"slug":"gpt-test","display_name":"GPT Test"}]}""",
        )

        val success = assertIs<OpenAiResult.Success<*>>(result)
        val response = assertIs<ModelsResponse>(success.value)
        assertEquals(OpenAiModelId("gpt-test"), response.models.single().slug)
        assertEquals("GPT Test", response.models.single().displayName)
    }

    test("decodes nested error response as failure") {
        val result = json.decodeFromString<OpenAiResponseResult<ModelsResponse>>(
            """
            {
              "error": {
                "message": "bad request",
                "code": "invalid_request",
                "type": "invalid_request_error"
              }
            }
            """.trimIndent(),
        )

        val failure = assertIs<OpenAiResult.Failure<*>>(result)
        val error = assertIs<OpenAiErrorResponse>(failure.error)
        assertEquals("bad request", error.messageText)
        assertEquals("invalid_request", error.codeText)
        assertEquals("invalid_request_error", error.typeText)
    }

    test("decodes flat error response as failure") {
        val result = json.decodeFromString<OpenAiResponseResult<ModelsResponse>>(
            """{"message":"rate limited","code":"rate_limit"}""",
        )

        val failure = assertIs<OpenAiResult.Failure<*>>(result)
        val error = assertIs<OpenAiErrorResponse>(failure.error)
        assertEquals("rate limited", error.messageText)
        assertEquals("rate_limit", error.codeText)
    }

    test("encodes result as raw payload") {
        val encoded = json.encodeToString<OpenAiResponseResult<ModelsResponse>>(
            OpenAiResult.Success(
                ModelsResponse(
                    listOf(ModelInfo(slug = OpenAiModelId("gpt-test"), displayName = "GPT Test")),
                ),
            ),
        )

        assertEquals("""{"models":[{"slug":"gpt-test","display_name":"GPT Test"}]}""", encoded)
    }

    test("get or throw raises the structured error") {
        val error = OpenAiErrorResponse(message = "bad request", code = "invalid_request")
        val result: OpenAiResponseResult<ModelsResponse> = OpenAiResult.Failure(error)

        val failure = assertFailsWith<OpenAiResponseResultException> {
            result.getOrThrow()
        }

        assertEquals(error, failure.error)
    }
}
