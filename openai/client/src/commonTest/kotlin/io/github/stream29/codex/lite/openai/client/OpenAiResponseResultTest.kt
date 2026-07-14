package io.github.stream29.codex.lite.openai.client

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.ModelsResponse
import io.github.stream29.codex.lite.openai.OpenAiResult
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlin.test.assertIs



val openAiResponseResultTest by testSuite {
    test("non success http status always produces failure") {
        val result = decodeOpenAiResponseResult(
            status = HttpStatusCode.BadRequest,
            payload = Json.parseToJsonElement("""{"models":[]}"""),
            successSerializer = ModelsResponse.serializer(),
        )

        assertIs<OpenAiResult.Failure<*>>(result)
    }

    test("success http status still recognizes structured error payload") {
        val result = decodeOpenAiResponseResult(
            status = HttpStatusCode.OK,
            payload = Json.parseToJsonElement("""{"message":"rate limited"}"""),
            successSerializer = ModelsResponse.serializer(),
        )

        assertIs<OpenAiResult.Failure<*>>(result)
    }

    test("successful status and payload produce success") {
        val result = decodeOpenAiResponseResult(
            status = HttpStatusCode.OK,
            payload = Json.parseToJsonElement("""{"models":[]}"""),
            successSerializer = ModelsResponse.serializer(),
        )

        assertIs<OpenAiResult.Success<*>>(result)
    }
}
