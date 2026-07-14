package io.github.stream29.codex.lite.tool.imagegeneration

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.jsoncodec.OpenAiJsonCodec
import io.github.stream29.codex.lite.openai.ResponsesApiNamespace
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue



private val json = OpenAiJsonCodec

val imageGenerationToolsTest by testSuite {
    test("spec declares image gen namespace tool") {
        val namespace = ImageGenerationTools.spec as ResponsesApiNamespace
        val encoded = json.parseToJsonElement(json.encodeToString(namespace)).jsonObject
        val tool = encoded.getValue("tools").jsonArray.single().jsonObject

        assertEquals(ImageGenNamespace, encoded["name"]?.toString()?.trim('"'))
        assertEquals(ImageGenToolName, tool["name"]?.toString()?.trim('"'))
        assertFalse("output_schema" in tool)
    }

    test("parameters expose prompt and edit inputs") {
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
