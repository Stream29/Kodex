package io.github.stream29.codex.lite.openai

import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OpenAiResultSerializationTest {
    private val json = OpenAiJsonCodec

    @Test
    fun decodesSuccessfulResponseAsSuccess() {
        val result = json.decodeFromString<OpenAiResponseResult<ModelsResponse>>(
            """{"models":[{"slug":"gpt-test","display_name":"GPT Test"}]}""",
        )

        val success = assertIs<OpenAiResult.Success<*>>(result)
        val response = assertIs<ModelsResponse>(success.value)
        assertEquals(OpenAiModelId("gpt-test"), response.models.single().slug)
        assertEquals("GPT Test", response.models.single().displayName)
    }

    @Test
    fun decodesNestedErrorResponseAsFailure() {
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

    @Test
    fun decodesFlatErrorResponseAsFailure() {
        val result = json.decodeFromString<OpenAiResponseResult<ModelsResponse>>(
            """{"message":"rate limited","code":"rate_limit"}""",
        )

        val failure = assertIs<OpenAiResult.Failure<*>>(result)
        val error = assertIs<OpenAiErrorResponse>(failure.error)
        assertEquals("rate limited", error.messageText)
        assertEquals("rate_limit", error.codeText)
    }

    @Test
    fun encodesResultAsRawPayload() {
        val encoded = json.encodeToString<OpenAiResponseResult<ModelsResponse>>(
            OpenAiResult.Success(
                ModelsResponse(
                    listOf(ModelInfo(slug = OpenAiModelId("gpt-test"), displayName = "GPT Test")),
                ),
            ),
        )

        assertEquals("""{"models":[{"slug":"gpt-test","display_name":"GPT Test"}]}""", encoded)
    }

    @Test
    fun getOrThrowRaisesTheStructuredError() {
        val error = OpenAiErrorResponse(message = "bad request", code = "invalid_request")
        val result: OpenAiResponseResult<ModelsResponse> = OpenAiResult.Failure(error)

        val failure = assertFailsWith<OpenAiResponseResultException> {
            result.getOrThrow()
        }

        assertEquals(error, failure.error)
    }
}
