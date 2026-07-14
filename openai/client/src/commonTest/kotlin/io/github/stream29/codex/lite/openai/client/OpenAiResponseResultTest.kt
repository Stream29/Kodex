package io.github.stream29.codex.lite.openai.client

import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs

class OpenAiResponseResultTest {
    @Test
    fun nonSuccessHttpStatusAlwaysProducesFailure() {
        val result = decodeOpenAiResponseResult(
            status = HttpStatusCode.BadRequest,
            payload = Json.parseToJsonElement("""{"models":[]}"""),
            successSerializer = ModelsResponse.serializer(),
        )

        assertIs<OpenAiResult.Failure<*>>(result)
    }

    @Test
    fun successHttpStatusStillRecognizesStructuredErrorPayload() {
        val result = decodeOpenAiResponseResult(
            status = HttpStatusCode.OK,
            payload = Json.parseToJsonElement("""{"message":"rate limited"}"""),
            successSerializer = ModelsResponse.serializer(),
        )

        assertIs<OpenAiResult.Failure<*>>(result)
    }

    @Test
    fun successfulStatusAndPayloadProduceSuccess() {
        val result = decodeOpenAiResponseResult(
            status = HttpStatusCode.OK,
            payload = Json.parseToJsonElement("""{"models":[]}"""),
            successSerializer = ModelsResponse.serializer(),
        )

        assertIs<OpenAiResult.Success<*>>(result)
    }
}
