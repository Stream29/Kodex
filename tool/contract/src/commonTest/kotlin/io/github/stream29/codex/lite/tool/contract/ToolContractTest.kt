package io.github.stream29.codex.lite.tool.contract

import de.infix.testBalloon.framework.core.testSuite

import io.github.stream29.codex.lite.openai.ResponseItem
import kotlin.test.assertEquals

val toolContractTest by testSuite {
    test("tool name renders plain and namespaced names") {
        assertEquals("apply_patch", ToolName.plain("apply_patch").toString())
        assertEquals("web.run", ToolName.namespaced("web", "run").toString())
    }

    test("function calls match their complete tool name") {
        val call = ResponseItem.FunctionCall(
            name = "view_image",
            arguments = "{\"path\":\"image.png\"}",
            callId = "call_1",
        )

        assertEquals(true, call.matches(ToolName.plain("view_image")))
        assertEquals(false, call.matches(ToolName.namespaced("image_gen", "view_image")))
    }

    test("custom calls match their complete tool name") {
        val call = ResponseItem.CustomToolCall(
            name = "apply_patch",
            input = "*** Begin Patch",
            callId = "call_1",
        )

        assertEquals(true, call.matches(ToolName.plain("apply_patch")))
    }
}
