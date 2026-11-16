package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.mcp.contract.McpCatalogTool
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

val mcpSchemaProjectionTest by testSuite {
    test("SDK tools list preserves typed object schema keywords") {
        val result = McpJson.decodeFromString<ListToolsResult>(
            """{"tools":[{"name":"complex","inputSchema":$ComplexInputSchema}]}""",
        )
        val encoded = McpJson.encodeToJsonElement(result.tools.single().inputSchema).jsonObject

        assertEquals(false, encoded["additionalProperties"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(2, assertNotNull(encoded["oneOf"]).jsonArray.size)
        assertNotNull(encoded["${'$'}defs"])
        assertEquals(false, encoded["unevaluatedProperties"]?.jsonPrimitive?.content?.toBoolean())
    }

    test("kotlinx schema projection preserves MCP applicators and object constraints") {
        val raw = Json.parseToJsonElement(ComplexInputSchema).jsonObject
        val projected = McpJson.decodeFromJsonElement<ObjectPropertyDefinition>(raw)
        val encoded = OpenAiJsonCodec.encodeToJsonElement(projected).jsonObject

        assertEquals(raw["additionalProperties"], encoded["additionalProperties"])
        assertEquals(2, assertNotNull(encoded["oneOf"]).jsonArray.size)
        assertNotNull(encoded["${'$'}defs"])
        assertEquals(raw["unevaluatedProperties"], encoded["unevaluatedProperties"])
    }

    test("McpCatalogTool round trips input and optional output schemas") {
        val inputDefinition = McpJson.decodeFromJsonElement<ObjectPropertyDefinition>(
            Json.parseToJsonElement(ComplexInputSchema).jsonObject,
        )
        val outputDefinition = McpJson.decodeFromJsonElement<ObjectPropertyDefinition>(
            Json.parseToJsonElement(ComplexOutputSchema).jsonObject,
        )
        val withOutput = McpCatalogTool(
            serverName = "alpha-beta",
            serverInstructions = "docs",
            toolName = "x.y",
            description = "structured tool",
            inputSchema = inputDefinition,
            outputSchema = outputDefinition,
        )
        val roundTripped = OpenAiJsonCodec.decodeFromString<McpCatalogTool>(
            OpenAiJsonCodec.encodeToString(withOutput),
        )
        assertEquals(withOutput, roundTripped)

        val withoutOutput = withOutput.copy(outputSchema = null)
        val decodedWithoutOutput = OpenAiJsonCodec.decodeFromString<McpCatalogTool>(
            OpenAiJsonCodec.encodeToString(withoutOutput),
        )
        assertNull(decodedWithoutOutput.outputSchema)
        assertEquals(withoutOutput, decodedWithoutOutput)
    }
}

private val ComplexInputSchema: String =
    """
    {
      "type": "object",
      "properties": {
        "target": {"${'$'}ref": "#/${'$'}defs/target"}
      },
      "required": ["target"],
      "additionalProperties": false,
      "oneOf": [
        {"required": ["target"]},
        {"properties": {"target": {"const": "default"}}}
      ],
      "${'$'}defs": {
        "target": {"type": "string", "minLength": 1}
      },
      "unevaluatedProperties": false
    }
    """.trimIndent()

private val ComplexOutputSchema: String =
    """
    {
      "type": "object",
      "properties": {"message": {"type": "string"}},
      "required": ["message"],
      "additionalProperties": false,
      "oneOf": [{"required": ["message"]}]
    }
    """.trimIndent()
