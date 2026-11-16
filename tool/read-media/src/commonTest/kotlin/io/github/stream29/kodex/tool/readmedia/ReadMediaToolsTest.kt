package io.github.stream29.kodex.tool.readmedia

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

val readMediaToolsTest by testSuite {
    test("spec retains output schema without sending it to Responses") {
        val encoded = json.parseToJsonElement(json.encodeToString<ResponsesApiTool>(ReadMediaTools.spec)).jsonObject

        assertEquals("read_media", encoded["name"]?.jsonPrimitive?.content)
        assertEquals(ReadMediaTools.Description, ReadMediaTools.spec.description)
        assertNotNull(ReadMediaTools.spec.outputSchema)
        assertFalse("output_schema" in encoded)
    }

    test("detail property follows canRequestOriginalImageDetail") {
        val defaultProperties = json.parseToJsonElement(
            json.encodeToString(ReadMediaTools.spec.parameters),
        ).jsonObject.getValue("properties").jsonObject
        assertTrue("path" in defaultProperties)
        assertTrue("detail" in defaultProperties)

        val restrictedProperties = json.parseToJsonElement(
            json.encodeToString(
                ReadMediaTools.toolSpec(ReadMediaToolOptions(canRequestOriginalImageDetail = false)).parameters,
            ),
        ).jsonObject.getValue("properties").jsonObject
        assertTrue("path" in restrictedProperties)
        assertFalse("detail" in restrictedProperties)
    }
}
