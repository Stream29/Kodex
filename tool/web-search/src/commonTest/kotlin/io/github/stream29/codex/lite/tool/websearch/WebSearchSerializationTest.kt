package io.github.stream29.codex.lite.tool.websearch

import io.github.stream29.codex.lite.tool.contract.ToolSpec
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WebSearchSerializationTest {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun specUsesWebNamespace() {
        val encoded = json.parseToJsonElement(json.encodeToString(ToolSpec.serializer(), WebSearchTools.spec))
            .jsonObject

        assertEquals(JsonPrimitive("namespace"), encoded["type"])
        assertEquals(JsonPrimitive("web"), encoded["name"])
        assertEquals(JsonPrimitive("Tools in the web namespace."), encoded["description"])

        val runTool = encoded["tools"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("function"), runTool["type"])
        assertEquals(JsonPrimitive("run"), runTool["name"])
        assertEquals(JsonPrimitive(false), runTool["strict"])

        val parameters = runTool["parameters"]!!.jsonObject
        assertFalse("required" in parameters)

        val properties = parameters["properties"]!!.jsonObject
        assertEquals(JsonPrimitive("array"), properties["search_query"]!!.jsonObject["type"])
        assertEquals(JsonPrimitive("array"), properties["image_query"]!!.jsonObject["type"])
        assertEquals(JsonPrimitive("string"), properties["response_length"]!!.jsonObject["type"])
        assertFalse("searchQuery" in properties)
        assertFalse("imageQuery" in properties)
        assertFalse("responseLength" in properties)
    }
}
