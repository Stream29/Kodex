package io.github.stream29.kodex.tool.readmedia

import de.infix.testBalloon.framework.core.testSuite
import io.github.stream29.kodex.openai.ResponsesApiTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val json = Json { explicitNulls = false }

val readMediaToolsTest by testSuite {
    test("spec serializes read_media function schema with path and detail") {
        val encoded = json.parseToJsonElement(json.encodeToString<ResponsesApiTool>(ReadMediaTools.spec)).jsonObject

        assertEquals("read_media", encoded["name"]?.jsonPrimitive?.content)
        assertEquals(ReadMediaTools.Description, ReadMediaTools.spec.description)
        assertFalse(ReadMediaTools.spec.strict)

        val defaultProperties = json.parseToJsonElement(
            json.encodeToString(ReadMediaTools.spec.parameters),
        ).jsonObject.getValue("properties").jsonObject
        assertTrue("path" in defaultProperties)
        assertTrue("detail" in defaultProperties)
    }
}
