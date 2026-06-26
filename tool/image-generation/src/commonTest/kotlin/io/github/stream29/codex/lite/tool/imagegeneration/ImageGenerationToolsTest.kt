package io.github.stream29.codex.lite.tool.imagegeneration

import io.github.stream29.codex.lite.openai.OpenAiJson
import io.github.stream29.codex.lite.tool.contract.ResponsesApiNamespace
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageGenerationToolsTest {
    private val json = OpenAiJson.default

    @Test
    fun specDeclaresImageGenNamespaceTool() {
        val namespace = ImageGenerationTools.spec as ResponsesApiNamespace
        val encoded = json.parseToJsonElement(json.encodeToString(namespace)).jsonObject
        val tool = encoded.getValue("tools").jsonArray.single().jsonObject

        assertEquals(ImageGenNamespace, encoded["name"]?.toString()?.trim('"'))
        assertEquals(ImageGenToolName, tool["name"]?.toString()?.trim('"'))
        assertFalse("output_schema" in tool)
    }

    @Test
    fun parametersExposePromptAndEditInputs() {
        val namespace = ImageGenerationTools.spec as ResponsesApiNamespace
        val tool = namespace.tools.single()
        val encoded = json.parseToJsonElement(json.encodeToString(tool)).jsonObject
        val properties = encoded
            .getValue("parameters")
            .jsonObject
            .getValue("properties")
            .jsonObject

        assertTrue("prompt" in properties)
        assertTrue("referenced_image_paths" in properties)
        assertTrue("num_last_images_to_include" in properties)
    }
}
