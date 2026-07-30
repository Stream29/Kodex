package io.github.stream29.kodex.mcp.impl

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.jsoncodec.OpenAiJsonCodec
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import kotlinx.schema.json.ObjectPropertyDefinition
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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

    test("MCP output schema is wrapped as a CallToolResult") {
        val structured = Json.parseToJsonElement(ComplexOutputSchema).jsonObject
        val definition = McpJson.decodeFromJsonElement<ObjectPropertyDefinition>(structured)
        val encoded = OpenAiJsonCodec.encodeToJsonElement(
            mcpCallToolResultOutputSchema(definition),
        ).jsonObject

        assertEquals(false, encoded["additionalProperties"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(listOf("content"), encoded.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val properties = encoded.getValue("properties").jsonObject
        assertEquals("array", properties.getValue("content").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(structured, properties.getValue("structuredContent"))
    }

    test("missing MCP output schema accepts arbitrary structured content") {
        val encoded = OpenAiJsonCodec.encodeToJsonElement(
            mcpCallToolResultOutputSchema(null),
        ).jsonObject

        assertEquals(
            emptyMap(),
            encoded.getValue("properties").jsonObject
                .getValue("structuredContent")
                .jsonObject,
        )
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
