package io.github.stream29.codex.lite.tool.viewimage

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.ResponsesApiTool
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue



private val json = Json { explicitNulls = false }

val viewImageToolsTest by testSuite {
    test("spec declares structured output schema") {
        val encoded = json.parseToJsonElement(json.encodeToString<ResponsesApiTool>(ViewImageTools.spec)).jsonObject

        assertEquals("view_image", encoded["name"]?.toString()?.trim('"'))
        assertNotNull(encoded["output_schema"])
    }

    test("detail and environment id are optionally included") {
        val defaultSchema = json.parseToJsonElement(
            json.encodeToString(ViewImageTools.spec.parameters),
        ).jsonObject
        val defaultProperties = defaultSchema.getValue("properties").jsonObject
        assertFalse("detail" in defaultProperties)
        assertFalse("environment_id" in defaultProperties)

        val expandedSchema = json.parseToJsonElement(
            json.encodeToString(
                ViewImageTools.toolSpec(
                    ViewImageToolOptions(
                        canRequestOriginalImageDetail = true,
                        includeEnvironmentId = true,
                    ),
                ).parameters,
            ),
        ).jsonObject
        val expandedProperties = expandedSchema.getValue("properties").jsonObject
        assertTrue("detail" in expandedProperties)
        assertTrue("environment_id" in expandedProperties)
    }
}
