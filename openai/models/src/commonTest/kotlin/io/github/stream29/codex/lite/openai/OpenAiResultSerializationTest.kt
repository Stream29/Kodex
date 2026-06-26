package io.github.stream29.codex.lite.openai

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenAiResultSerializationTest {
    @Test
    fun decodesSuccessfulResponseAsSuccess() {
        val result = OpenAiJson.default.decodeFromString<OpenAiResponseResult<ModelsResponse>>(
            """{"models":[{"slug":"gpt-test"}]}""",
        )

        val success = assertIs<OpenAiResult.Success<*>>(result)
        val response = assertIs<ModelsResponse>(success.value)
        assertEquals("gpt-test", response.models.single().slug)
    }

    @Test
    fun decodesNestedErrorResponseAsFailure() {
        val result = OpenAiJson.default.decodeFromString<OpenAiResponseResult<ModelsResponse>>(
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

    @Test
    fun decodesFlatErrorResponseAsFailure() {
        val result = OpenAiJson.default.decodeFromString<OpenAiResponseResult<ModelsResponse>>(
            """{"message":"rate limited","code":"rate_limit"}""",
        )

        val failure = assertIs<OpenAiResult.Failure<*>>(result)
        val error = assertIs<OpenAiErrorResponse>(failure.error)
        assertEquals("rate limited", error.messageText)
        assertEquals("rate_limit", error.codeText)
    }

    @Test
    fun encodesResultAsRawPayload() {
        val encoded = OpenAiJson.default.encodeToString<OpenAiResponseResult<ModelsResponse>>(
            OpenAiResult.Success(ModelsResponse(listOf(ModelInfo(slug = "gpt-test")))),
        )

        assertEquals("""{"models":[{"slug":"gpt-test"}]}""", encoded)
    }
}
