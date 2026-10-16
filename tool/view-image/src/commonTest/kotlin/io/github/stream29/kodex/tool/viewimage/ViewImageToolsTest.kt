package io.github.stream29.kodex.tool.viewimage

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.kodex.openai.ResponsesApiTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val json = Json { explicitNulls = false }

val viewImageToolsTest by testSuite {
    test("spec retains output schema without sending it to Responses") {
        val encoded = json.parseToJsonElement(json.encodeToString<ResponsesApiTool>(ViewImageTools.spec)).jsonObject

        assertEquals("view_image", encoded["name"]?.toString()?.trim('"'))
        assertEquals(
            "View a local image file from the filesystem when visual inspection is needed. Use this for images already available on disk.",
            ViewImageTools.spec.description,
        )
        assertNotNull(ViewImageTools.spec.outputSchema)
        assertFalse("output_schema" in encoded)
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
        assertEquals(
            "Image detail level. Defaults to `high`; use `original` to preserve exact resolution.",
            expandedProperties.getValue("detail").jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "Environment id from <environment_context>. Omit to use the primary environment.",
            expandedProperties.getValue("environment_id").jsonObject.getValue("description").jsonPrimitive.content,
        )

        val outputProperties = json.parseToJsonElement(
            json.encodeToString(ViewImageTools.spec.outputSchema),
        ).jsonObject.getValue("properties").jsonObject
        assertEquals(
            "Data URL for the loaded image.",
            outputProperties.getValue("image_url").jsonObject.getValue("description").jsonPrimitive.content,
        )
        assertEquals(
            "Image detail hint returned by view_image. Returns `high` for default resized behavior or `original` when original resolution is preserved.",
            outputProperties.getValue("detail").jsonObject.getValue("description").jsonPrimitive.content,
        )
    }
}
