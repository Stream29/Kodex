package io.github.stream29.codex.lite.tool.webrun

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val json = OpenAiJsonCodec

val webRunToolsTest by testSuite {
    test("spec declares the web.run namespace function") {
        val namespace = WebRunTools.spec as ResponsesApiNamespace
        val encoded = json.parseToJsonElement(json.encodeToString(namespace)).jsonObject
        val tool = encoded.getValue("tools").jsonArray.single().jsonObject

        assertEquals(WebRunNamespace, encoded["name"]?.toString()?.trim('"'))
        assertEquals(WebRunToolName, tool["name"]?.toString()?.trim('"'))
        assertEquals(JsonPrimitive(false), tool["strict"])
    }

    test("schema includes every supported command group") {
        val encoded = json.parseToJsonElement(
            json.encodeToString(WebRunParametersSchema),
        ).jsonObject
        val properties = encoded.getValue("properties").jsonObject

        assertTrue(
            setOf(
                "search_query",
                "image_query",
                "open",
                "click",
                "find",
                "screenshot",
                "finance",
                "weather",
                "sports",
                "time",
                "response_length",
            ).all(properties::containsKey),
        )
    }
}
