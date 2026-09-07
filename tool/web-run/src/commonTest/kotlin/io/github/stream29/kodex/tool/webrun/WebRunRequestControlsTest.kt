package io.github.stream29.kodex.tool.webrun

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.*
import io.github.stream29.kodex.openai.client.test.mockOpenAiClient
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals

val webRunRequestControlsTest by testSuite {
    test("web run sends its existing medium default explicitly") {
        lateinit var captured: SearchRequest
        val client = mockOpenAiClient {
            search { request ->
                captured = request
                OpenAiResult.Success(SearchResponse(output = "test"))
            }
        }
        WebRunToolClient(client, "session", { OpenAiModelId("test") }).run(SearchCommands())
        val encoded = OpenAiJsonCodec.encodeToJsonElement(SearchRequest.serializer(), captured).jsonObject
        assertEquals(JsonPrimitive("medium"), encoded["reasoning"]!!.jsonObject["effort"])
    }
}
