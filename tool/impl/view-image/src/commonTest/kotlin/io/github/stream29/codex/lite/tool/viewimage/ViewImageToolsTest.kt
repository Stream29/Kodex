package io.github.stream29.codex.lite.tool.viewimage

import io.github.stream29.codex.lite.openai.ResponsesApiTool
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ViewImageToolsTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun specDeclaresStructuredOutputSchema() {
        val encoded = json.parseToJsonElement(json.encodeToString<ResponsesApiTool>(ViewImageTools.spec)).jsonObject

        assertEquals("view_image", encoded["name"]?.toString()?.trim('"'))
        assertNotNull(encoded["output_schema"])
    }

    @Test
    fun detailAndEnvironmentIdAreOptionallyIncluded() {
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
