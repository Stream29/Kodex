package io.github.stream29.codex.lite.tool.getcontextremaining

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.codex.lite.openai.FunctionCallOutputBody
import io.github.stream29.codex.lite.openai.ResponseItem
import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertTrue

val getContextRemainingToolsTest by testSuite {
    test("spec declares the Rust-aligned nullable output union") {
        val schema = OpenAiJsonCodec.parseToJsonElement(
            OpenAiJsonCodec.encodeToString(GetContextRemainingTools.spec.outputSchema),
        ).jsonObject
        val tokensLeft = schema.getValue("properties").jsonObject
            .getValue("tokens_left")
            .jsonObject

        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertTrue(JsonPrimitive("tokens_left") in schema.getValue("required").jsonArray)
        assertEquals(
            listOf(JsonPrimitive("integer"), JsonPrimitive("null")),
            tokensLeft.getValue("anyOf").jsonArray.map { option ->
                option.jsonObject.getValue("type")
            },
        )
    }

    test("writes the Direct-mode unknown-budget fragment") {
        val output = GetContextRemainingTools.createTool { null }.handle(
            ResponseItem.FunctionCall(
                name = GetContextRemainingTools.Name,
                arguments = "{}",
                callId = "call_context",
            ),
        ) as ResponseItem.FunctionCallOutput

        assertEquals(true, output.output.success)
        assertEquals(
            "You have unknown tokens left in this context window.",
            (output.output.body as FunctionCallOutputBody.Text).text,
        )
    }

    test("writes the Direct-mode remaining-budget fragment") {
        val output = GetContextRemainingTools.createTool { 123L }.handle(
            ResponseItem.FunctionCall(
                name = GetContextRemainingTools.Name,
                arguments = "{}",
                callId = "call_context",
            ),
        ) as ResponseItem.FunctionCallOutput

        assertEquals(
            "You have 123 tokens left in this context window.",
            (output.output.body as FunctionCallOutputBody.Text).text,
        )
    }
}
